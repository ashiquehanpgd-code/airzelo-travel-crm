import Foundation
import CryptoKit
import CommonCrypto

/// Encrypted container for iPhone backups ("AZBK1").
///
///   magic "AZBK1\0" | header length (UInt32 LE) | header JSON | chunks
///   header: {"v":1, "kdf":"device"|"pbkdf2-sha256", "salt":b64, "iterations":n, "chunk":1048576, "created_at":..., "app_version":...}
///   chunk:  length (UInt32 LE) | AES-256-GCM sealed box (nonce|ciphertext|tag)
///
/// Every chunk is authenticated together with the header, its index and whether it is the last one, so a
/// truncated, reordered or edited file is rejected rather than half restored. The plaintext is a desktop
/// AirZelo backup (.zip with manifest.json), so a decrypted export restores directly on a Mac or PC.
public enum BackupCrypto {
    static let magic = Data("AZBK1\0".utf8)
    static let chunkSize = 1 << 20
    public static let pbkdf2Iterations = 600_000

    public enum KeySource {
        case device(SymmetricKey)
        case passphrase(String)
    }

    public enum CryptoError: Error, CustomStringConvertible {
        case notABackup, wrongKey, damaged, weakPassphrase
        public var description: String {
            switch self {
            case .notABackup: return "This is not an AirZelo iPhone backup."
            case .wrongKey: return "The passphrase is wrong, or the backup was made on another device."
            case .damaged: return "The backup file is damaged or incomplete."
            case .weakPassphrase: return "Use a passphrase of at least 8 characters."
            }
        }
    }

    static func deriveKey(_ passphrase: String, salt: Data, iterations: Int) -> SymmetricKey {
        var out = [UInt8](repeating: 0, count: 32)
        let pw = Array(passphrase.utf8)
        _ = salt.withUnsafeBytes { s in
            CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), pw.map { Int8(bitPattern: $0) }, pw.count,
                                 s.bindMemory(to: UInt8.self).baseAddress, salt.count,
                                 CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(iterations), &out, out.count)
        }
        return SymmetricKey(data: out)
    }

    /// Backups made with the device key use a key derived for this purpose only (never the field key itself).
    static func backupKey(from device: SymmetricKey) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: device, info: Data("AirZelo backup v1".utf8), outputByteCount: 32)
    }

    public static func seal(_ plain: Data, with source: KeySource, appVersion: String) throws -> Data {
        var header: [String: JSONValue] = ["v": 1, "chunk": .int(chunkSize), "created_at": .string(Dates.nowISO()), "app_version": .string(appVersion)]
        let key: SymmetricKey
        switch source {
        case .device(let k):
            header["kdf"] = "device"
            key = backupKey(from: k)
        case .passphrase(let p):
            guard p.count >= 8 else { throw CryptoError.weakPassphrase }
            let salt = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
            header["kdf"] = "pbkdf2-sha256"
            header["salt"] = .string(salt.base64EncodedString())
            header["iterations"] = .int(pbkdf2Iterations)
            key = deriveKey(p, salt: salt, iterations: pbkdf2Iterations)
        }
        let headerData = Data(JSONValue.serialize(.object(header)).utf8)
        var out = magic
        out.le32(UInt32(headerData.count))
        out.append(headerData)
        let n = max(1, (plain.count + chunkSize - 1) / chunkSize)
        for i in 0..<n {
            let part = plain.subdata(in: (i * chunkSize)..<min(plain.count, (i + 1) * chunkSize))
            let box = try AES.GCM.seal(part, using: key, authenticating: aad(headerData, i, last: i == n - 1))
            let c = box.combined!
            out.le32(UInt32(c.count))
            out.append(c)
        }
        return out
    }

    public static func isContainer(_ data: Data) -> Bool { data.count > magic.count && data.prefix(magic.count) == magic }

    /// Header fields without decrypting (to know whether a passphrase is needed).
    public static func header(_ data: Data) throws -> JSONValue {
        guard isContainer(data), data.count >= magic.count + 4 else { throw CryptoError.notABackup }
        let len = Int(data.readLE32(at: magic.count))
        let start = magic.count + 4
        guard start + len <= data.count else { throw CryptoError.damaged }
        return try JSONValue.parse(data.subdata(in: start..<(start + len)))
    }

    public static func open(_ data: Data, deviceKey: SymmetricKey?, passphrase: String?) throws -> Data {
        let h = try header(data)
        let len = Int(data.readLE32(at: magic.count))
        let headerData = data.subdata(in: (magic.count + 4)..<(magic.count + 4 + len))
        let key: SymmetricKey
        if h["kdf"].stringValue == "device" {
            guard let deviceKey else { throw CryptoError.wrongKey }
            key = backupKey(from: deviceKey)
        } else {
            guard let passphrase, let salt = Data(base64Encoded: h["salt"].stringValue ?? ""), let it = h["iterations"].intValue,
                  it >= 100_000 else { throw CryptoError.wrongKey }
            key = deriveKey(passphrase, salt: salt, iterations: it)
        }
        var p = magic.count + 4 + len
        var chunks: [Data] = []
        while p < data.count {
            guard p + 4 <= data.count else { throw CryptoError.damaged }
            let clen = Int(data.readLE32(at: p))
            p += 4
            guard clen > 28, p + clen <= data.count else { throw CryptoError.damaged }
            chunks.append(data.subdata(in: p..<(p + clen)))
            p += clen
        }
        if chunks.isEmpty { throw CryptoError.damaged }
        var out = Data()
        for (i, c) in chunks.enumerated() {
            guard let box = try? AES.GCM.SealedBox(combined: c) else { throw CryptoError.damaged }
            do {
                out.append(try AES.GCM.open(box, using: key, authenticating: aad(headerData, i, last: i == chunks.count - 1)))
            } catch {
                throw i == 0 ? CryptoError.wrongKey : CryptoError.damaged
            }
        }
        return out
    }

    static func aad(_ header: Data, _ index: Int, last: Bool) -> Data {
        var a = header
        a.le32(UInt32(index))
        a.append(last ? 1 : 0)
        return a
    }
}

extension Data {
    func readLE32(at o: Int) -> UInt32 {
        UInt32(self[startIndex + o]) | UInt32(self[startIndex + o + 1]) << 8 | UInt32(self[startIndex + o + 2]) << 16 | UInt32(self[startIndex + o + 3]) << 24
    }
}

public extension SHA256.Digest {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
