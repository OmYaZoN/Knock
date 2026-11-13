import CryptoKit
import Foundation

/// Knock v1 crypto (protocol/PROTOCOL.md §3-5). Mirrors protocol/reference/knock_ref.py
/// and is checked against vectors.json by the KnockVectors tool.
public enum KnockCrypto {
    public static func sha256(_ parts: Data...) -> Data {
        var h = SHA256()
        for p in parts { h.update(data: p) }
        return Data(h.finalize())
    }

    public static func hkdf(_ ikm: Data, salt: Data, info: String, count: Int) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm), salt: salt,
            info: Data(info.utf8), outputByteCount: count)
        return key.withUnsafeBytes { Data($0) }
    }

    public static func ecdh(_ priv: P256.KeyAgreement.PrivateKey, _ peerPub: Data) throws -> Data {
        let pub = try P256.KeyAgreement.PublicKey(x963Representation: peerPub)
        return try priv.sharedSecretFromKeyAgreement(with: pub).withUnsafeBytes { Data($0) }
    }

    /// First 8 bytes of SHA-256 of an identity public key.
    public static func shortID(_ identityPub: Data) -> Data { sha256(identityPub).prefix(8) }

    public static func pairCommit(phonePub: Data, np: Data) -> Data { sha256(phonePub, np) }

    public static func pairCode(own: P256.KeyAgreement.PrivateKey, peerPub: Data, np: Data, nm: Data) throws -> String {
        let z = try ecdh(own, peerPub)
        let okm = hkdf(z, salt: np + nm, info: "knock v1 pair code", count: 4)
        let n = okm.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        return String(format: "%06u", n % 1_000_000)
    }

    public static func transcript(phoneID: Data, ephPhonePub: Data, rp: Data, ephMacPub: Data, rm: Data) -> Data {
        sha256(Data("knock v1 hs".utf8), phoneID, ephPhonePub, rp, ephMacPub, rm)
    }

    public static func sign(_ identity: P256.KeyAgreement.PrivateKey, _ t: Data, role: String) throws -> Data {
        let sk = try P256.Signing.PrivateKey(rawRepresentation: identity.rawRepresentation)
        return try sk.signature(for: t + Data(role.utf8)).derRepresentation
    }

    public static func verify(identityPub: Data, sig: Data, _ t: Data, role: String) -> Bool {
        guard let pk = try? P256.Signing.PublicKey(x963Representation: identityPub),
              let s = try? P256.Signing.ECDSASignature(derRepresentation: sig) else { return false }
        return pk.isValidSignature(s, for: t + Data(role.utf8))
    }

    /// (phone->mac key, mac->phone key)
    public static func sessionKeys(ownEph: P256.KeyAgreement.PrivateKey, peerEphPub: Data, t: Data) throws -> (Data, Data) {
        let ze = try ecdh(ownEph, peerEphPub)
        return (hkdf(ze, salt: t, info: "knock v1 phone->mac", count: 32),
                hkdf(ze, salt: t, info: "knock v1 mac->phone", count: 32))
    }

    public static func random(_ n: Int) -> Data {
        var d = Data(count: n)
        _ = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        return d
    }
}

/// One direction of SEALED frames with its own counter (§5).
public final class Channel {
    private let key: SymmetricKey
    public private(set) var counter: UInt64 = 0

    public init(key: Data) { self.key = SymmetricKey(data: key) }

    private func nonce() throws -> AES.GCM.Nonce {
        var n = Data(count: 4)
        withUnsafeBytes(of: counter.bigEndian) { n.append(contentsOf: $0) }
        return try AES.GCM.Nonce(data: n)
    }

    /// Returns a complete SEALED frame, ready to send.
    public func seal(_ innerType: UInt8, _ body: Data) throws -> Data {
        let plaintext = Data([innerType]) + body
        let length = UInt32(1 + plaintext.count + 16)
        let aad = Data([Frame.sealed]) + length.bigEndianData
        let box = try AES.GCM.seal(plaintext, using: key, nonce: try nonce(), authenticating: aad)
        counter += 1
        return Frame.encode(Frame.sealed, box.ciphertext + box.tag)
    }

    /// Opens the body of a SEALED frame. Throws on any tampering, replay or reordering.
    public func open(_ sealedBody: Data) throws -> (UInt8, Data) {
        guard sealedBody.count >= 17 else { throw KnockError.protocolViolation("short sealed frame") }
        let aad = Data([Frame.sealed]) + UInt32(1 + sealedBody.count).bigEndianData
        let box = try AES.GCM.SealedBox(
            nonce: try nonce(), ciphertext: sealedBody.dropLast(16), tag: sealedBody.suffix(16))
        let pt = try AES.GCM.open(box, using: key, authenticating: aad)
        counter += 1
        guard let t = pt.first else { throw KnockError.protocolViolation("empty plaintext") }
        return (t, Data(pt.dropFirst()))
    }
}

public enum KnockError: Error, CustomStringConvertible {
    case protocolViolation(String)
    public var description: String {
        switch self { case .protocolViolation(let s): return "protocol violation: \(s)" }
    }
}

extension UInt32 {
    var bigEndianData: Data { withUnsafeBytes(of: bigEndian) { Data($0) } }
}

extension Data {
    public var hex: String { map { String(format: "%02x", $0) }.joined() }

    public init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var d = Data(capacity: hex.count / 2)
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            guard let b = UInt8(hex[i..<j], radix: 16) else { return nil }
            d.append(b)
            i = j
        }
        self = d
    }
}
