import CryptoKit
import Foundation

/// This Mac's long-term identity key and the phones it has paired with.
/// Stored in ~/Library/Application Support/Knock with owner-only permissions.
public final class IdentityStore {
    public struct Peer: Codable {
        public var publicKeyHex: String
        public var name: String
    }

    public let key: P256.KeyAgreement.PrivateKey
    public private(set) var peers: [String: Peer]   // phone_id hex -> peer
    private let dir: URL

    public var publicKey: Data { key.publicKey.x963Representation }
    public var shortID: Data { KnockCrypto.shortID(publicKey) }

    public init() throws {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("Knock", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let keyURL = dir.appendingPathComponent("identity.key")
        if let raw = try? Data(contentsOf: keyURL),
           let k = try? P256.KeyAgreement.PrivateKey(rawRepresentation: raw) {
            key = k
        } else {
            key = P256.KeyAgreement.PrivateKey()
            try key.rawRepresentation.write(to: keyURL, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: keyURL.path)
        }
        let peersURL = dir.appendingPathComponent("peers.json")
        peers = (try? JSONDecoder().decode([String: Peer].self, from: Data(contentsOf: peersURL))) ?? [:]
    }

    public func peer(phoneID: Data) -> Peer? { peers[phoneID.hex] }

    public func addPeer(publicKey: Data, name: String) throws {
        peers[KnockCrypto.shortID(publicKey).hex] = Peer(publicKeyHex: publicKey.hex, name: name)
        try save()
    }

    public func removeAll() throws { peers = [:]; try save() }

    private func save() throws {
        let url = dir.appendingPathComponent("peers.json")
        try JSONEncoder().encode(peers).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}
