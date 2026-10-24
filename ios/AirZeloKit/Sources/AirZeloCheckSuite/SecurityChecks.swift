import Foundation
import CryptoKit
import AirZeloKit

enum SecurityChecks {
    static let cases: [CheckSuite.Case] = [
        ("security: passport numbers encrypted at rest", passportsAtRest),
        ("security: tampered or foreign data rejected", tamper),
        ("security: keychain key and file protection (iOS)", deviceProtection),
    ]

    static func passportsAtRest(_ o: CheckOptions, _ log: CheckLog) throws {
        let t = try TempAgency()
        let a = t.agency
        let cid = try a.createCustomer(["name": "Secret Holder", "passport_no": "k9876543"])
        try a.saveBooking(["customer_id": .int(cid), "status": "confirmed", "passengers": [["name": "Pax One", "passport_no": "q1112223"]]])
        try a.updateCustomer(cid, ["city": "Kochi"])  // re-saves the sealed value
        try expectEqual(try a.getCustomer(cid).customer["passport_no"], "K9876543", "readable in the app")
        let stored = try a.db.one("SELECT passport_no FROM customers WHERE id = ?", [cid])!.str("passport_no")
        try expect(stored.hasPrefix(FieldCrypto.prefix), "stored sealed, got \(stored)")
        try a.db.exec("PRAGMA wal_checkpoint(FULL)")
        for suffix in ["", "-wal"] {
            let url = URL(fileURLWithPath: a.url.path + suffix)
            guard let bytes = try? Data(contentsOf: url) else { continue }
            for secret in ["K9876543", "Q1112223"] {
                try expect(bytes.range(of: Data(secret.utf8)) == nil, "\(secret) found in plain text in agency.db\(suffix)")
            }
        }
        let audit = try a.listAudit().map { $0.str("after_json") + $0.str("before_json") }.joined()
        try expect(!audit.contains("K9876543") && !audit.contains("Q1112223"), "audit log must not hold passport numbers")
    }

    static func tamper(_ o: CheckOptions, _ log: CheckLog) throws {
        let c = FieldCrypto.ephemeral()
        let sealed = try c.seal("P1234567", column: "passport_no")!
        try expectEqual(c.open(sealed, column: "passport_no"), "P1234567")
        try expect(c.open(sealed, column: "notes") != "P1234567", "bound to its column")
        try expect(FieldCrypto.ephemeral().open(sealed, column: "passport_no") != "P1234567", "another device key cannot read it")
        var raw = Data(base64Encoded: String(sealed.dropFirst(FieldCrypto.prefix.count)))!
        raw[raw.count - 1] ^= 1
        try expect(c.open(FieldCrypto.prefix + raw.base64EncodedString(), column: "passport_no") != "P1234567", "edited value rejected")

        let plain = Data((0..<2_500_000).map { UInt8(truncatingIfNeeded: $0 &* 31) })  // 3 chunks
        let box = try BackupCrypto.seal(plain, with: .passphrase("correct horse"), appVersion: "test")
        try expectEqual(try BackupCrypto.open(box, deviceKey: nil, passphrase: "correct horse"), plain)
        func fails(_ d: Data, _ pass: String, _ what: String) throws {
            try expect((try? BackupCrypto.open(d, deviceKey: nil, passphrase: pass)) == nil, what)
        }
        try fails(box, "wrong horse", "wrong passphrase")
        var flipped = box; flipped[flipped.count - 600_000] ^= 0x40
        try fails(flipped, "correct horse", "flipped byte in a later chunk")
        try fails(box.prefix(box.count - 1_100_000), "correct horse", "truncated file (last chunk missing)")
        var hdr = box; hdr[12] ^= 0x01
        try fails(hdr, "correct horse", "edited header")
        do {
            _ = try BackupCrypto.seal(plain, with: .passphrase("short"), appVersion: "test")
            throw CheckFailure(message: "weak passphrase accepted")
        } catch is BackupCrypto.CryptoError {}
        let key = SymmetricKey(size: .bits256)
        let dev = try BackupCrypto.seal(Data("x".utf8), with: .device(key), appVersion: "t")
        try expect((try? BackupCrypto.open(dev, deviceKey: SymmetricKey(size: .bits256), passphrase: nil)) == nil, "other device key")
    }

    static func deviceProtection(_ o: CheckOptions, _ log: CheckLog) throws {
        #if os(iOS)
        let account = "check-\(UUID().uuidString)"
        defer { KeychainKey.delete(account: account) }
        let k1 = try KeychainKey.loadOrCreate(account: account)
        let k2 = try KeychainKey.loadOrCreate(account: account)
        try expect(k1 == k2, "the Keychain returns the same key")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("az-prot-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AgencyStore(root: root, deviceKey: k1)
        try store.open()
        let b = try store.createBackup()
        let dbp = FileSecurity.protection(of: store.dbURL)
        let bkp = FileSecurity.protection(of: store.backupDir.appendingPathComponent(b.name))
        let got = "database \(dbp ?? "none"), backup \(bkp ?? "none")"
        #if targetEnvironment(simulator)
        // the iOS Simulator does not implement Data Protection classes; this part can only be verified on a device
        log.note("Keychain key OK. File protection as reported by the simulator: \(got) (enforced only on a real iPhone)")
        #else
        log.note("file protection: \(got)")
        try expect(dbp == FileProtectionType.completeUnlessOpen.rawValue, "database file protection: \(got)")
        try expect(bkp == FileProtectionType.complete.rawValue, "backup file protection: \(got)")
        #endif
        store.close()
        #else
        throw CheckSkipped(reason: "iOS only (Keychain + Data Protection); runs in the simulator CI job")
        #endif
    }
}
