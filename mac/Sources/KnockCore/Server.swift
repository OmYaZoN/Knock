import CryptoKit
import Foundation
import Network

/// What the app shows the person. All calls arrive on the main queue.
public protocol KnockServerDelegate: AnyObject {
    /// Show `code` and ask whether it matches the phone. Call `answer` exactly once.
    func confirmPairing(code: String, phoneName: String, answer: @escaping (Bool) -> Void)
    func paired(phoneName: String)
    func roundFinished(ok: Bool, reason: TapRound.Reason)
    func received(file: URL, kind: String, from: String)
    func log(_ line: String)
}

/// Listens on the local network and advertises `_knock._tcp` over Bonjour (protocol §1).
public final class KnockServer {
    public let identity: IdentityStore
    public weak var delegate: KnockServerDelegate?
    public private(set) var port: UInt16?
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: PhoneConnection] = [:]

    public init(identity: IdentityStore) { self.identity = identity }

    public func start() throws {
        let l = try NWListener(using: .tcp)
        let name = Host.current().localizedName ?? "Mac"
        l.service = NWListener.Service(name: name, type: "_knock._tcp", domain: nil,
                                       txtRecord: Self.txt(["v": "1", "id": identity.shortID.hex, "name": name]))
        l.stateUpdateHandler = { [weak self] state in
            if case .ready = state { self?.port = l.port?.rawValue; self?.delegate?.log("Listening on port \(l.port?.rawValue ?? 0)") }
            if case .failed(let e) = state { self?.delegate?.log("Listener failed: \(e)") }
        }
        l.newConnectionHandler = { [weak self] nw in
            guard let self else { return }
            let c = PhoneConnection(nw, server: self)
            self.connections[ObjectIdentifier(c)] = c
            c.start()
        }
        l.start(queue: .main)
        listener = l
    }

    func closed(_ c: PhoneConnection) { connections[ObjectIdentifier(c)] = nil }

    /// DNS-SD TXT record bytes: each entry is a length byte followed by "key=value".
    static func txt(_ entries: [String: String]) -> Data {
        var d = Data()
        for (k, v) in entries.sorted(by: { $0.key < $1.key }) {
            let e = Data("\(k)=\(v)".utf8).prefix(255)
            d.append(UInt8(e.count)); d.append(e)
        }
        return d
    }
}

/// One phone connection: pairing (§3) or a session (§4-7).
final class PhoneConnection {
    private enum State { case fresh, pairing, handshake, session, closed }

    private let nw: NWConnection
    private unowned let server: KnockServer
    private var reader = FrameReader()
    private var state = State.fresh
    private var timeout: DispatchWorkItem?

    // Pairing
    private var commit = Data(), nm = Data()
    private var phonePub = Data(), phoneName = ""
    private var macConfirmed = false, phoneConfirmed = false

    // Session
    private var phoneID = Data(), peerPub = Data(), transcript = Data()
    private var rx: Channel?, tx: Channel?
    private var round: TapRound?
    private var roundPassed = false
    private var file: FileReceiver?

    private var delegate: KnockServerDelegate? { server.delegate }

    init(_ nw: NWConnection, server: KnockServer) { self.nw = nw; self.server = server }

    func start() {
        nw.stateUpdateHandler = { [weak self] s in
            if case .failed = s { self?.close("connection failed") }
            if case .cancelled = s { self?.close(nil) }
        }
        nw.start(queue: .main)
        armTimeout(60)
        receive()
    }

    private func receive() {
        nw.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { [weak self] data, _, done, error in
            guard let self, self.state != .closed else { return }
            if let data { self.reader.append(data) }
            do {
                while let (type, body) = try self.reader.next() { try self.handle(type, body) }
            } catch {
                return self.close("\(error)")
            }
            if done || error != nil { return self.close(nil) }
            self.receive()
        }
    }

    private func send(_ frame: Data) { nw.send(content: frame, completion: .contentProcessed { _ in }) }

    private func sendSealed(_ type: UInt8, _ body: Data) {
        do { send(try tx!.seal(type, body)) } catch { close("seal failed") }
    }

    private func armTimeout(_ seconds: Double) {
        timeout?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.close("timed out") }
        timeout = w
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: w)
    }

    private func close(_ why: String?) {
        guard state != .closed else { return }
        if let why { delegate?.log("Phone connection closed: \(why)") }
        state = .closed
        timeout?.cancel(); round?.cancel(); file?.discard()
        nw.cancel()
        server.closed(self)
    }

    // MARK: - Dispatch

    private func handle(_ type: UInt8, _ body: Data) throws {
        switch (state, type) {
        case (.fresh, Frame.pairCommit): try pairCommit(body)
        case (.pairing, Frame.pairPhone): try pairPhone(body)
        case (.pairing, Frame.pairOK): phoneConfirmed = true; try finishPairingIfDone()
        case (.fresh, Frame.hello): try hello(body)
        case (.handshake, Frame.helloFin): try helloFin(body)
        case (.session, Frame.sealed):
            let (inner, payload) = try rx!.open(body)
            try handleInner(inner, payload)
        default:
            throw KnockError.protocolViolation("unexpected frame 0x\(String(type, radix: 16)) in \(state)")
        }
    }

    // MARK: - Pairing (§3)

    private func pairCommit(_ body: Data) throws {
        guard body.count == 32 else { throw KnockError.protocolViolation("commit length") }
        commit = body
        nm = KnockCrypto.random(16)
        state = .pairing
        let name = Host.current().localizedName ?? "Mac"
        send(Frame.encode(Frame.pairMac, server.identity.publicKey + nm + Data(name.utf8)))
    }

    private func pairPhone(_ body: Data) throws {
        guard body.count >= 81, phonePub.isEmpty else { throw KnockError.protocolViolation("pair phone") }
        phonePub = Data(body.prefix(65))
        let np = Data(body.dropFirst(65).prefix(16))
        phoneName = String(decoding: body.dropFirst(81), as: UTF8.self)
        guard KnockCrypto.pairCommit(phonePub: phonePub, np: np) == commit else {
            throw KnockError.protocolViolation("commitment mismatch")
        }
        let code = try KnockCrypto.pairCode(own: server.identity.key, peerPub: phonePub, np: np, nm: nm)
        delegate?.confirmPairing(code: code, phoneName: phoneName) { [weak self] ok in
            guard let self, self.state == .pairing else { return }
            guard ok else { return self.close("pairing declined on Mac") }
            self.macConfirmed = true
            self.send(Frame.encode(Frame.pairOK, Data()))
            try? self.finishPairingIfDone()
        }
    }

    private func finishPairingIfDone() throws {
        guard macConfirmed, phoneConfirmed else { return }
        try server.identity.addPeer(publicKey: phonePub, name: phoneName)
        delegate?.paired(phoneName: phoneName)
        close(nil)
    }

    // MARK: - Session handshake (§4)

    private func hello(_ body: Data) throws {
        guard body.count == 8 + 65 + 16 else { throw KnockError.protocolViolation("hello length") }
        phoneID = Data(body.prefix(8))
        guard let peer = server.identity.peer(phoneID: phoneID), let pub = Data(hex: peer.publicKeyHex) else {
            throw KnockError.protocolViolation("unknown phone, pair it first")
        }
        peerPub = pub
        let ephPhonePub = Data(body.dropFirst(8).prefix(65))
        let rp = Data(body.suffix(16))
        let e = P256.KeyAgreement.PrivateKey()
        let rm = KnockCrypto.random(16)
        let ephMacPub = e.publicKey.x963Representation
        transcript = KnockCrypto.transcript(phoneID: phoneID, ephPhonePub: ephPhonePub, rp: rp, ephMacPub: ephMacPub, rm: rm)
        let sig = try KnockCrypto.sign(server.identity.key, transcript, role: "mac")
        // Keys are derived now, but nothing is decrypted until HELLO_FIN proves who the phone is.
        let (p2m, m2p) = try KnockCrypto.sessionKeys(ownEph: e, peerEphPub: ephPhonePub, t: transcript)
        rx = Channel(key: p2m); tx = Channel(key: m2p)
        state = .handshake
        send(Frame.encode(Frame.helloMac, ephMacPub + rm + sig))
    }

    private func helloFin(_ body: Data) throws {
        guard KnockCrypto.verify(identityPub: peerPub, sig: body, transcript, role: "phone") else {
            throw KnockError.protocolViolation("phone signature invalid")
        }
        state = .session
        armTimeout(120)
        delegate?.log("Session open with \(server.identity.peer(phoneID: phoneID)?.name ?? "phone")")
    }

    // MARK: - Tap round and file (§6-7)

    private func handleInner(_ type: UInt8, _ body: Data) throws {
        armTimeout(120)
        switch type {
        case Frame.roundStart:
            guard body.count == 4 else { throw KnockError.protocolViolation("round start") }
            let id = body.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self).bigEndian }
            round?.cancel(); roundPassed = false
            let idData = UInt32(id).bigEndianData
            let r = TapRound(id: id, show: { [weak self] color in
                self?.sendSealed(Frame.show, idData + Data([color]))
            }, finish: { [weak self] ok, reason in
                guard let self else { return }
                self.roundPassed = ok
                self.sendSealed(Frame.roundResult, idData + Data([ok ? 1 : 0, reason.rawValue]))
                self.delegate?.roundFinished(ok: ok, reason: reason)
            })
            round = r
            r.start()
        case Frame.shown:
            guard body.count == 5 else { throw KnockError.protocolViolation("shown") }
            round?.shown(body[body.startIndex + 4])
        case Frame.fileHeader:
            guard roundPassed, file == nil else { throw KnockError.protocolViolation("file without a passed tap") }
            file = try FileReceiver(headerJSON: body)
        case Frame.fileChunk:
            guard let f = file else { throw KnockError.protocolViolation("chunk without header") }
            try f.append(body)
        case Frame.fileEnd:
            guard let f = file else { throw KnockError.protocolViolation("end without header") }
            file = nil; roundPassed = false
            do {
                let url = try f.finish(expectedSHA256: body)
                sendSealed(Frame.fileSaved, Data([1]) + Data(url.lastPathComponent.utf8))
                delegate?.received(file: url, kind: f.header.kind, from: f.header.from ?? "")
            } catch {
                sendSealed(Frame.fileSaved, Data([0]))
                delegate?.log("File rejected: \(error)")
            }
        default:
            throw KnockError.protocolViolation("unexpected inner 0x\(String(type, radix: 16))")
        }
    }
}
