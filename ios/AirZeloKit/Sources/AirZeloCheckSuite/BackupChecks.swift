import Foundation
import CryptoKit
import AirZeloKit

public enum BackupChecks {
    static let cases: [CheckSuite.Case] = [
        ("backup: device backup and restore", deviceRoundTrip),
        ("backup: passphrase export to another device", exportToAnotherDevice),
        ("backup: damaged backup refused, books untouched", damagedRefused),
        ("recovery: corrupted books restored from backup", corruptionRecovery),
        ("recovery: crash mid-save loses nothing committed", crashSafety),
        ("backup: desktop backup imports on iPhone", desktopImport),
        ("backup: iPhone export for the desktop app", exportForDesktop),
    ]

    final class TempStore {
        let root: URL
        let store: AgencyStore
        init(key: SymmetricKey = SymmetricKey(size: .bits256)) {
            root = FileManager.default.temporaryDirectory.appendingPathComponent("az-store-" + UUID().uuidString)
            store = AgencyStore(root: root, deviceKey: key)
        }
        deinit {
            store.close()
            try? FileManager.default.removeItem(at: root)
        }
    }

    static func sample(_ a: Agency) throws {
        let x = try ServiceChecks.basics(a)
        let b = try a.saveBooking(["customer_id": .int(x.cust), "supplier_id": .int(x.sup), "status": "ticketed", "purchase_cost": "20000",
                                   "selling_price": "22500", "passengers": [["name": "Backup Pax", "passport_no": "m5554443", "ticket_number": "0651112223334"]]])
        try a.createTransaction(["kind": "customer_receipt", "account_id": .int(x.cash), "amount": "10000", "booking_id": .int(b)])
        try a.createInvoice(b)
    }

    static func dump(_ a: Agency) throws -> JSONValue { try ParityChecks.tableDump(a) }

    static func deviceRoundTrip(_ o: CheckOptions, _ log: CheckLog) throws {
        let t = TempStore()
        let a = try t.store.open()
        try sample(a)
        let before = try dump(a)
        let b = try t.store.createBackup()
        try a.createCustomer(["name": "Added After Backup"])
        let safety = try t.store.restore(try t.store.backupData(b))
        try expect(safety != nil, "a safety backup of the replaced books is made")
        let restored = try t.store.open()
        try expect(ParityChecks.diff(try dump(restored), before).isEmpty, "restored books equal the backed-up books")
        try expectEqual(try restored.db.scalarInt("SELECT COUNT(*) FROM customers WHERE name = 'Added After Backup'"), 0)
        try expectEqual(try restored.integrityCheck(), "ok")
        log.note("backup \(b.size) bytes; \(t.store.listBackups().count) backups on device")
    }

    static func exportToAnotherDevice(_ o: CheckOptions, _ log: CheckLog) throws {
        let phone1 = TempStore(), phone2 = TempStore()
        let a = try phone1.store.open()
        try sample(a)
        let before = try dump(a)
        let file = try phone1.store.exportBackup(passphrase: "kerala-travel-2026")
        try expect(phone2.store.verify(file, passphrase: nil).needsPassphrase, "export asks for its passphrase")
        try expect(!phone2.store.verify(file, passphrase: "wrong passphrase").ok, "wrong passphrase refused")
        try phone2.store.restore(file, passphrase: "kerala-travel-2026")
        let b = try phone2.store.open()
        try expect(ParityChecks.diff(try dump(b), before).isEmpty, "the other phone has the same books, passports readable")
        let sealed = try b.db.one("SELECT passport_no FROM booking_passengers")!.str("passport_no")
        try expect(sealed.hasPrefix(FieldCrypto.prefix), "re-encrypted with the new phone's key")
    }

    static func damagedRefused(_ o: CheckOptions, _ log: CheckLog) throws {
        let t = TempStore()
        let a = try t.store.open()
        try sample(a)
        let before = try dump(a)
        var data = try t.store.backupData(try t.store.createBackup())
        data[data.count / 2] ^= 0xFF
        do {
            try t.store.restore(data)
            throw CheckFailure(message: "a damaged backup was restored")
        } catch is AgencyStore.StoreError {}
        try expect(ParityChecks.diff(try dump(try t.store.open()), before).isEmpty, "current books unchanged")
        let notZip = Data("hello".utf8)
        try expect(!t.store.verify(notZip, passphrase: nil).ok, "random file refused")
    }

    static func corruptionRecovery(_ o: CheckOptions, _ log: CheckLog) throws {
        let t = TempStore()
        let a = try t.store.open()
        try sample(a)
        let before = try dump(a)
        try t.store.createBackup(kind: "auto")
        t.store.close()
        // overwrite the middle of the database file, as a failing disk or an interrupted copy would
        let h = try FileHandle(forWritingTo: t.store.dbURL)
        let size = try FileManager.default.attributesOfItem(atPath: t.store.dbURL.path)[.size] as! Int
        try h.seek(toOffset: UInt64(size / 3))
        h.write(Data(repeating: 0xA5, count: 8192))
        try h.close()
        do {
            try t.store.open()
            throw CheckFailure(message: "corrupted books opened without warning")
        } catch AgencyStore.StoreError.corrupted(let why) {
            log.note("detected: \(why.prefix(80))")
        }
        let used = try t.store.recoverFromLatestBackup()
        let b = try t.store.open()
        try expectEqual(try b.integrityCheck(), "ok")
        try expect(ParityChecks.diff(try dump(b), before).isEmpty, "books equal the last good backup (\(used.name))")
        try expect(FileManager.default.contentsOfDirectory(atPath: t.root.path).contains { $0.hasPrefix("damaged-") }, "the damaged files are kept aside")
    }

    /// A real process is killed in the middle of a save (and right after another) with WAL + synchronous=FULL.
    static func crashSafety(_ o: CheckOptions, _ log: CheckLog) throws {
        #if os(macOS)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("az-crash-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let exe = URL(fileURLWithPath: CommandLine.arguments[0])
        for mode in ["committed", "uncommitted"] {
            let p = Process()
            p.executableURL = exe
            p.arguments = ["--crash-writer", dir.path, mode]
            p.standardError = FileHandle.nullDevice
            try p.run()
            p.waitUntilExit()
            try expect(p.terminationReason == .uncaughtSignal, "child must have crashed (\(mode))")
        }
        let a = try Agency(url: dir.appendingPathComponent("agency.db"), crypto: .ephemeral(), protect: false)
        defer { a.close() }
        try expectEqual(try a.integrityCheck(), "ok")
        try expectEqual(try a.db.scalarInt("SELECT COUNT(*) FROM customers WHERE name LIKE 'Committed %'"), 1, "committed save survived")
        try expectEqual(try a.db.scalarInt("SELECT COUNT(*) FROM customers WHERE name LIKE 'Lost %'"), 0, "unfinished save rolled back")
        #else
        throw CheckSkipped(reason: "needs a child process (macOS run)")
        #endif
    }

    /// Child-process half of crashSafety: `AirZeloChecks --crash-writer <dir> committed|uncommitted`.
    public static func crashWriter(_ dir: String, _ mode: String) -> Never {
        let a = try! Agency(url: URL(fileURLWithPath: dir).appendingPathComponent("agency.db"), crypto: .ephemeral(), protect: false)
        if mode == "committed" {
            _ = try! a.createCustomer(["name": "Committed One"])
            abort()  // straight after COMMIT returned
        }
        _ = try? a.tx { () -> Int in
            for i in 0..<300 { _ = try a.createCustomer(["name": .string("Lost \(i)")]) }
            abort()  // in the middle of an open transaction
        }
        abort()
    }

    static func desktopImport(_ o: CheckOptions, _ log: CheckLog) throws {
        let fx = try ParityChecks.load(o)
        pinClock(fx.today)
        defer { unpinClock() }
        let t = TempStore()
        let zip = try Data(contentsOf: o.parityDir!.appendingPathComponent("desktop-backup.zip"))
        let v = t.store.verify(zip, passphrase: nil)
        try expect(v.ok, "desktop backup verifies: \(v.problems)")
        try t.store.restore(zip)
        let a = try t.store.open()
        let diffs = ParityChecks.diff(try ParityChecks.snapshot(a, today: fx.today), fx.snapshot)
        try expect(diffs.isEmpty, "\(diffs.count) differences after import:\n" + diffs.prefix(20).joined(separator: "\n"))
        let plain = try a.db.scalarInt("SELECT COUNT(*) FROM customers WHERE passport_no IS NOT NULL AND passport_no NOT LIKE 'azenc1:%'")
        try expectEqual(plain, 0, "imported passport numbers are encrypted on the phone")
        log.note("desktop \(v.manifest["app_version"].pyStr) backup: \(zip.count) bytes, \(fx.snapshot["bookings"].objectValue?.count ?? 0) bookings imported")
    }

    /// Replays the desktop's operations through the iPhone services and writes a portable export; the
    /// desktop then restores it (ios/tools/parity.py --check-export) and must reproduce every figure.
    static func exportForDesktop(_ o: CheckOptions, _ log: CheckLog) throws {
        let fx = try ParityChecks.load(o)
        pinClock(fx.today)
        defer { unpinClock() }
        let t = TempStore()
        let a = try t.store.open()
        for op in fx.ops { _ = ParityChecks.outcome { try ParityChecks.apply(a, op["op"].stringValue!, op["args"].arrayValue ?? []) } }
        let zip = try t.store.archive(portable: true, note: "parity export")
        try zip.write(to: o.parityDir!.appendingPathComponent("iphone-export.zip"))
        let r = try Zip.Reader(zip)
        try expect(r.names.contains("agency.db") && r.names.contains("manifest.json"), "desktop archive layout")
        log.note("wrote iphone-export.zip (\(zip.count) bytes); check it with: python3 ios/tools/parity.py --check-export \(o.parityDir!.path)")
    }
}
