import Foundation
import CryptoKit
import Security

/// Encrypts sensitive fields (passport numbers) before they are written to the database.
///
/// The whole database file is already protected by iOS Data Protection (see `FileSecurity`); passport
/// numbers get a second layer so they never appear in plain text in the file, its WAL or a crash log.
/// Format: "azenc1:" + base64(AES-256-GCM nonce | ciphertext | tag), with the column name as associated
/// data. Values without the prefix (from a desktop import before re-encryption) are read as plain text.
public final class FieldCrypto {
    public static let prefix = "azenc1:"
    private let key: SymmetricKey

    public init(key: SymmetricKey) { self.key = key }

    /// A throwaway key (tests, previews).
    public static func ephemeral() -> FieldCrypto { FieldCrypto(key: SymmetricKey(size: .bits256)) }

    /// The device key from the Keychain, created on first use.
    public static func keychain(account: String = "field-key-v1") throws -> FieldCrypto {
        FieldCrypto(key: try KeychainKey.loadOrCreate(account: account))
    }

    public func seal(_ plain: String?, column: String) throws -> String? {
        guard let plain, !plain.isEmpty else { return plain }
        if plain.hasPrefix(FieldCrypto.prefix) { return plain }
        let box = try AES.GCM.seal(Data(plain.utf8), using: key, authenticating: Data(column.utf8))
        return FieldCrypto.prefix + box.combined!.base64EncodedString()
    }

    public func open(_ stored: String?, column: String) -> String? {
        guard let stored, stored.hasPrefix(FieldCrypto.prefix) else { return stored }
        guard let raw = Data(base64Encoded: String(stored.dropFirst(FieldCrypto.prefix.count))),
              let box = try? AES.GCM.SealedBox(combined: raw),
              let plain = try? AES.GCM.open(box, using: key, authenticating: Data(column.utf8)) else {
            return "(encrypted - not readable on this device)"
        }
        return String(decoding: plain, as: UTF8.self)
    }

    public static func isSealed(_ s: String?) -> Bool { s?.hasPrefix(prefix) ?? false }
}

/// A 256-bit key kept in the Keychain. It is readable only while the device is unlocked, is never synced
/// to iCloud Keychain, and moves to a new phone only inside an encrypted device backup.
public enum KeychainKey {
    static let service = "com.airzelo.crm.keys"

    public static func loadOrCreate(account: String) throws -> SymmetricKey {
        if let data = try load(account: account) { return SymmetricKey(data: data) }
        let key = SymmetricKey(size: .bits256)
        let data = key.withUnsafeBytes { Data($0) }
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlocked,
            kSecAttrSynchronizable as String: false,
            kSecValueData as String: data,
        ]
        let status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem, let existing = try load(account: account) { return SymmetricKey(data: existing) }
        guard status == errSecSuccess else { throw AZError.validation("Could not create the device encryption key (Keychain error \(status))") }
        return key
    }

    static func load(account: String) throws -> Data? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw AZError.validation("Could not read the device encryption key (Keychain error \(status))") }
        return out as? Data
    }

    /// Removes the key (tests only: the encrypted passport numbers become unreadable).
    public static func delete(account: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
    }
}

/// iOS Data Protection for files on the device.
public enum FileSecurity {
    /// The live database: encrypted by iOS with a key derived from the device passcode. Files already open
    /// stay usable if the phone locks mid-save (`completeUnlessOpen`), new files cannot be opened while locked.
    public static func protectDatabaseFiles(at url: URL) {
        for suffix in ["", "-wal", "-shm"] {
            set(.completeUnlessOpen, URL(fileURLWithPath: url.path + suffix))
        }
        set(.completeUnlessOpen, url.deletingLastPathComponent())
    }

    /// Backups and exported documents: unreadable whenever the device is locked.
    public static func protectComplete(_ url: URL) { set(.complete, url) }

    static func set(_ p: FileProtectionType, _ url: URL) {
        #if os(iOS)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        try? FileManager.default.setAttributes([.protectionKey: p], ofItemAtPath: url.path)
        #endif
    }

    /// The protection class actually set on a file (nil on macOS, where the volume is protected by FileVault).
    public static func protection(of url: URL) -> String? {
        #if os(iOS)
        return (try? FileManager.default.attributesOfItem(atPath: url.path)[.protectionKey] as? FileProtectionType)?.rawValue
        #else
        return nil
        #endif
    }
}
