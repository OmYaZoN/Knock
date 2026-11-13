import Foundation

/// Frame layout and type codes (protocol/PROTOCOL.md §2).
public enum Frame {
    public static let maxLength = 1 << 20

    // Outer types
    public static let pairCommit: UInt8 = 0x01
    public static let pairMac: UInt8 = 0x02
    public static let pairPhone: UInt8 = 0x03
    public static let pairOK: UInt8 = 0x04
    public static let hello: UInt8 = 0x10
    public static let helloMac: UInt8 = 0x11
    public static let helloFin: UInt8 = 0x12
    public static let sealed: UInt8 = 0x20

    // Inner (sealed) types
    public static let roundStart: UInt8 = 0x30
    public static let show: UInt8 = 0x31
    public static let shown: UInt8 = 0x32
    public static let roundResult: UInt8 = 0x33
    public static let fileHeader: UInt8 = 0x40
    public static let fileChunk: UInt8 = 0x41
    public static let fileEnd: UInt8 = 0x42
    public static let fileSaved: UInt8 = 0x43

    public static func encode(_ type: UInt8, _ body: Data) -> Data {
        UInt32(1 + body.count).bigEndianData + Data([type]) + body
    }
}

/// Collects bytes from the socket and yields whole frames.
public struct FrameReader {
    private var buffer = Data()

    public init() {}

    public mutating func append(_ data: Data) { buffer.append(data) }

    /// Next complete frame, nil if more bytes are needed. Throws on an oversized frame.
    public mutating func next() throws -> (UInt8, Data)? {
        guard buffer.count >= 4 else { return nil }
        let b = [UInt8](buffer.prefix(4))
        let n = Int(b[0]) << 24 | Int(b[1]) << 16 | Int(b[2]) << 8 | Int(b[3])
        guard n >= 1, n <= Frame.maxLength else { throw KnockError.protocolViolation("frame length \(n)") }
        guard buffer.count >= 4 + n else { return nil }
        let start = buffer.startIndex
        let type = buffer[start + 4]
        let body = Data(buffer[(start + 5)..<(start + 4 + n)])
        buffer = Data(buffer[(start + 4 + n)...])
        return (type, body)
    }
}
