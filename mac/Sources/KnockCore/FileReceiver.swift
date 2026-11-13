import CryptoKit
import Foundation

/// Writes one incoming file into ~/Knock data (protocol/PROTOCOL.md §7).
public final class FileReceiver {
    public struct Header: Decodable {
        public let name: String
        public let size: Int64
        public let mime: String?
        public let kind: String          // "original" or "screenshot"
        public let from: String?
    }

    public static var folder: URL {
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Knock data", isDirectory: true)
    }

    public let header: Header
    private let tempURL: URL
    private let handle: FileHandle
    private var hasher = SHA256()
    private var received: Int64 = 0

    public init(headerJSON: Data) throws {
        header = try JSONDecoder().decode(Header.self, from: headerJSON)
        try FileManager.default.createDirectory(at: Self.folder, withIntermediateDirectories: true)
        tempURL = Self.folder.appendingPathComponent(".partial-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: tempURL.path, contents: nil)
        handle = try FileHandle(forWritingTo: tempURL)
    }

    public func append(_ chunk: Data) throws {
        received += Int64(chunk.count)
        guard received <= header.size else { throw KnockError.protocolViolation("more bytes than announced") }
        hasher.update(data: chunk)
        try handle.write(contentsOf: chunk)
    }

    /// Checks size and hash, then moves the file into place. Returns its final URL.
    public func finish(expectedSHA256: Data) throws -> URL {
        try handle.close()
        guard received == header.size, Data(hasher.finalize()) == expectedSHA256 else {
            discard()
            throw KnockError.protocolViolation("size or hash mismatch")
        }
        let dest = Self.uniqueURL(for: Self.safeName(header.name))
        try FileManager.default.moveItem(at: tempURL, to: dest)
        return dest
    }

    public func discard() {
        try? handle.close()
        try? FileManager.default.removeItem(at: tempURL)
    }

    /// Strips any path parts so a name like "../../x" can't escape the folder.
    static func safeName(_ name: String) -> String {
        let last = (name as NSString).lastPathComponent
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        let trimmed = last.trimmingCharacters(in: .whitespacesAndNewlines)
        return (trimmed.isEmpty || trimmed.hasPrefix(".")) ? "Knock file\(trimmed)" : trimmed
    }

    static func uniqueURL(for name: String) -> URL {
        let base = (name as NSString).deletingPathExtension, ext = (name as NSString).pathExtension
        var url = folder.appendingPathComponent(name)
        var n = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = folder.appendingPathComponent(ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)")
            n += 1
        }
        return url
    }
}
