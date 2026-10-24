import Foundation
import Compression

/// Minimal ZIP reader / writer (stored and deflate entries, no ZIP64), enough for AirZelo backups, which
/// are the desktop app's format: a .zip with manifest.json, agency.db, uploads/ and branding/.
/// Foundation has no ZIP API on iOS; Apple's Compression framework provides raw DEFLATE.
public enum Zip {
    public struct Entry {
        public let name: String
        public let method: UInt16
        public let crc32: UInt32
        public let compressedSize: Int
        public let size: Int
        let localHeaderOffset: Int
    }

    public enum ZipError: Error, CustomStringConvertible {
        case notZip, corrupt(String), unsupported(String), tooLarge
        public var description: String {
            switch self {
            case .notZip: return "File is not a valid zip archive"
            case .corrupt(let s): return "Corrupt entry: \(s)"
            case .unsupported(let s): return "Unsupported zip feature: \(s)"
            case .tooLarge: return "Archive is too large"
            }
        }
    }

    static let maxEntrySize = 1 << 31

    // MARK: reading

    public struct Reader {
        public let data: Data
        public let entries: [Entry]

        public init(_ data: Data) throws {
            self.data = data
            let bytes = [UInt8](data)
            guard bytes.count >= 22 else { throw ZipError.notZip }
            var eocd = -1
            var i = bytes.count - 22
            let stop = max(0, bytes.count - 22 - 65_535)
            while i >= stop {
                if bytes[i] == 0x50 && bytes[i + 1] == 0x4B && bytes[i + 2] == 0x05 && bytes[i + 3] == 0x06 { eocd = i; break }
                i -= 1
            }
            guard eocd >= 0 else { throw ZipError.notZip }
            func u16(_ o: Int) -> Int { Int(bytes[o]) | Int(bytes[o + 1]) << 8 }
            func u32(_ o: Int) -> Int { Int(bytes[o]) | Int(bytes[o + 1]) << 8 | Int(bytes[o + 2]) << 16 | Int(bytes[o + 3]) << 24 }
            let count = u16(eocd + 10)
            var p = u32(eocd + 16)
            var out: [Entry] = []
            for _ in 0..<count {
                guard p + 46 <= bytes.count, u32(p) == 0x02014B50 else { throw ZipError.notZip }
                let flags = u16(p + 8), method = u16(p + 10)
                let crc = UInt32(u32(p + 16)), csize = u32(p + 20), size = u32(p + 24)
                let nlen = u16(p + 28), xlen = u16(p + 30), clen = u16(p + 32), off = u32(p + 42)
                if flags & 1 != 0 { throw ZipError.unsupported("encrypted entry") }
                if csize == 0xFFFFFFFF || size == 0xFFFFFFFF || off == 0xFFFFFFFF { throw ZipError.unsupported("ZIP64") }
                guard p + 46 + nlen <= bytes.count else { throw ZipError.notZip }
                let name = String(decoding: bytes[(p + 46)..<(p + 46 + nlen)], as: UTF8.self)
                out.append(Entry(name: name, method: UInt16(method), crc32: crc, compressedSize: csize, size: size, localHeaderOffset: off))
                p += 46 + nlen + xlen + clen
            }
            entries = out
        }

        public var names: [String] { entries.map { $0.name } }

        public func read(_ name: String) throws -> Data {
            guard let e = entries.first(where: { $0.name == name }) else { throw ZipError.corrupt(name) }
            return try read(e)
        }

        public func read(_ e: Entry) throws -> Data {
            if e.size > Zip.maxEntrySize { throw ZipError.tooLarge }
            let o = e.localHeaderOffset
            guard o + 30 <= data.count else { throw ZipError.corrupt(e.name) }
            let h = data.subdata(in: o..<(o + 30))
            guard h[0] == 0x50, h[1] == 0x4B, h[2] == 0x03, h[3] == 0x04 else { throw ZipError.corrupt(e.name) }
            let nlen = Int(h[26]) | Int(h[27]) << 8, xlen = Int(h[28]) | Int(h[29]) << 8
            let start = o + 30 + nlen + xlen
            guard start + e.compressedSize <= data.count else { throw ZipError.corrupt(e.name) }
            let raw = data.subdata(in: start..<(start + e.compressedSize))
            let out: Data
            switch e.method {
            case 0: out = raw
            case 8: out = try Zip.inflate(raw, size: e.size)
            default: throw ZipError.unsupported("compression method \(e.method)")
            }
            guard out.count == e.size, CRC32.checksum(out) == e.crc32 else { throw ZipError.corrupt(e.name) }
            return out
        }
    }

    static func inflate(_ data: Data, size: Int) throws -> Data {
        if size == 0 { return Data() }
        var out = Data(count: size)
        let n = out.withUnsafeMutableBytes { dst in
            data.withUnsafeBytes { src in
                compression_decode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, size,
                                          src.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard n == size else { throw ZipError.corrupt("deflate stream") }
        return out
    }

    static func deflate(_ data: Data) -> Data? {
        if data.isEmpty { return nil }
        let cap = data.count + data.count / 10 + 1024
        var out = Data(count: cap)
        let n = out.withUnsafeMutableBytes { dst in
            data.withUnsafeBytes { src in
                compression_encode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, cap,
                                          src.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard n > 0, n < data.count else { return nil }
        return out.prefix(n)
    }

    // MARK: writing

    public final class Writer {
        private var body = Data()
        private var central = Data()
        private var count = 0

        public init() {}

        public func add(_ name: String, _ content: Data) {
            let nameBytes = Data(name.utf8)
            let crc = CRC32.checksum(content)
            let packed = Zip.deflate(content)
            let method: UInt16 = packed == nil ? 0 : 8
            let stored = packed ?? content
            let offset = body.count
            var h = Data()
            h.le32(0x04034B50); h.le16(20); h.le16(0x0800 /* UTF-8 names */); h.le16(method); h.le16(0); h.le16(0x21)
            h.le32(crc); h.le32(UInt32(stored.count)); h.le32(UInt32(content.count)); h.le16(UInt16(nameBytes.count)); h.le16(0)
            body.append(h); body.append(nameBytes); body.append(stored)
            var c = Data()
            c.le32(0x02014B50); c.le16(20); c.le16(20); c.le16(0x0800); c.le16(method); c.le16(0); c.le16(0x21)
            c.le32(crc); c.le32(UInt32(stored.count)); c.le32(UInt32(content.count)); c.le16(UInt16(nameBytes.count))
            c.le16(0); c.le16(0); c.le16(0); c.le16(0); c.le32(0o100644 << 16); c.le32(UInt32(offset))
            central.append(c); central.append(nameBytes)
            count += 1
        }

        public func finish() -> Data {
            var out = body
            let cdOffset = out.count
            out.append(central)
            var e = Data()
            e.le32(0x06054B50); e.le16(0); e.le16(0); e.le16(UInt16(count)); e.le16(UInt16(count))
            e.le32(UInt32(central.count)); e.le32(UInt32(cdOffset)); e.le16(0)
            out.append(e)
            return out
        }
    }
}

extension Data {
    mutating func le16(_ v: UInt16) { append(UInt8(v & 0xFF)); append(UInt8(v >> 8)) }
    mutating func le32(_ v: UInt32) { for s in stride(from: 0, to: 32, by: 8) { append(UInt8((v >> UInt32(s)) & 0xFF)) } }
}

public enum CRC32 {
    static let table: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
        return c
    }
    public static func checksum(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        data.withUnsafeBytes { (p: UnsafeRawBufferPointer) in
            for b in p { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        }
        return c ^ 0xFFFFFFFF
    }
}
