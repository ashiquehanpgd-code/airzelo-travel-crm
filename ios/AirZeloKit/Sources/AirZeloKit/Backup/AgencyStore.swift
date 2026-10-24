import Foundation
import CryptoKit

/// The agency's files on this device, and backup / restore (port of core/server/backup.py, adapted to iOS).
///
///   <root>/agency/agency.db (+ -wal, -shm)   live books, iOS Data Protection
///   <root>/agency/uploads/, branding/          documents and logos imported from the desktop
///   <root>/backups/backup-<kind>-<stamp>.azbackup   encrypted with the device key (see BackupCrypto)
///
/// A backup's plaintext is exactly a desktop AirZelo backup (.zip: manifest.json, agency.db, uploads/, branding/),
/// so iPhone exports restore in the desktop app and desktop backups import on the iPhone.
public final class AgencyStore {
    public static let appVersion = "0.3.0-ios"
    public let root: URL
    public let deviceKey: SymmetricKey
    public let crypto: FieldCrypto
    public private(set) var agency: Agency?

    public var agencyDir: URL { root.appendingPathComponent("agency") }
    public var dbURL: URL { agencyDir.appendingPathComponent("agency.db") }
    public var backupDir: URL { root.appendingPathComponent("backups") }

    public enum StoreError: Error, CustomStringConvertible {
        case corrupted(String)
        case verification([String])
        public var description: String {
            switch self {
            case .corrupted(let s): return "The books on this device failed their integrity check (\(s)). Restore the latest backup."
            case .verification(let p): return "Backup failed verification: " + p.joined(separator: "; ")
            }
        }
    }

    /// - deviceKey: the Keychain key used for field encryption and device backups.
    public init(root: URL, deviceKey: SymmetricKey) {
        self.root = root
        self.deviceKey = deviceKey
        self.crypto = FieldCrypto(key: deviceKey)
    }

    /// The default store in Application Support, keyed by the device Keychain key.
    public static func standard() throws -> AgencyStore {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return AgencyStore(root: base.appendingPathComponent("AirZelo"), deviceKey: try KeychainKey.loadOrCreate(account: "field-key-v1"))
    }

    public var exists: Bool { FileManager.default.fileExists(atPath: dbURL.path) }

    /// Opens (or creates) the books and checks their integrity first.
    @discardableResult
    public func open() throws -> Agency {
        if let agency { return agency }
        try FileManager.default.createDirectory(at: agencyDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: backupDir, withIntermediateDirectories: true)
        FileSecurity.set(.completeUnlessOpen, agencyDir)
        FileSecurity.protectComplete(backupDir)
        if exists {
            let problem = AgencyStore.quickCheck(dbURL)
            if problem != "ok" { throw StoreError.corrupted(problem) }
        }
        let a = try Agency(url: dbURL, crypto: crypto)
        agency = a
        return a
    }

    public func close() {
        if let a = agency {
            try? a.db.exec("PRAGMA wal_checkpoint(TRUNCATE)")
            a.close()
        }
        agency = nil
    }

    /// PRAGMA quick_check on a file without touching it ("ok" or the problem).
    static func quickCheck(_ url: URL) -> String {
        do {
            let db = try SQLiteDB(path: url.path, readOnly: true)
            defer { db.close() }
            let r = try db.one("PRAGMA quick_check")?.optStr("quick_check") ?? "unknown"
            if r != "ok" { return r }
            _ = try db.scalarInt("SELECT COUNT(*) FROM bookings")
            return "ok"
        } catch {
            return "\(error)"
        }
    }

    // MARK: backups

    public struct BackupInfo: Identifiable, Hashable {
        public let name: String
        public let kind: String
        public let size: Int
        public let created: Date
        public var id: String { name }
    }

    public func listBackups() -> [BackupInfo] {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: backupDir.path) else { return [] }
        return names.filter { $0.hasPrefix("backup-") && $0.hasSuffix(".azbackup") }.compactMap { n in
            let attrs = try? fm.attributesOfItem(atPath: backupDir.appendingPathComponent(n).path)
            let parts = n.split(separator: "-")
            return BackupInfo(name: n, kind: parts.count > 2 ? String(parts[1]) : "manual", size: (attrs?[.size] as? Int) ?? 0,
                              created: (attrs?[.modificationDate] as? Date) ?? .distantPast)
        }.sorted { $0.created > $1.created || ($0.created == $1.created && $0.name > $1.name) }
    }

    private func uniqueName(_ kind: String) -> String {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyyMMdd-HHmmss"
        let base = "backup-\(kind)-\(df.string(from: Dates.now()))"
        var name = base + ".azbackup", n = 1
        while FileManager.default.fileExists(atPath: backupDir.appendingPathComponent(name).path) { n += 1; name = "\(base)-\(n).azbackup" }
        return name
    }

    /// The desktop-format archive of the current books. `portable`: passport numbers decrypted (the archive is
    /// then encrypted with a passphrase, or handed to the desktop app).
    public func archive(portable: Bool, note: String = "") throws -> Data {
        let a = try open()
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("az-snap-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let snap = tmp.appendingPathComponent("agency.db")
        try a.db.backup(to: snap.path)
        if portable {
            let s = try SQLiteDB(path: snap.path)
            for (table, col) in [("customers", "passport_no"), ("booking_passengers", "passport_no")] {
                for r in try s.query("SELECT id, \(col) FROM \(table) WHERE \(col) LIKE 'azenc1:%'") {
                    try s.run("UPDATE \(table) SET \(col) = ? WHERE id = ?", [crypto.open(r.optStr(col), column: col), r.int("id")])
                }
            }
            try s.exec("VACUUM")  // the sealed values do not linger in free pages
            s.close()
        }
        let dbData = try Data(contentsOf: snap)
        var files: [String: JSONValue] = ["agency.db": .string(SHA256.hash(data: dbData).hex)]
        let zip = Zip.Writer()
        zip.add("agency.db", dbData)
        for sub in ["uploads", "branding"] {
            let dir = agencyDir.appendingPathComponent(sub)
            for n in ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).sorted() where !n.hasSuffix(".part") {
                let d = try Data(contentsOf: dir.appendingPathComponent(n))
                zip.add(sub + "/" + n, d)
                files[sub + "/" + n] = .string(SHA256.hash(data: d).hex)
            }
        }
        let manifest: JSONValue = ["app": "AirZelo", "app_version": .string(AgencyStore.appVersion), "schema_version": .int(Agency.schemaVersion),
                                   "kind": .string(portable ? "export" : "device"), "note": .string(note), "created_at": .string(Dates.nowISO()),
                                   "platform": "ios", "files": .object(files)]
        zip.add("manifest.json", Data(JSONValue.serialize(manifest).utf8))
        return zip.finish()
    }

    /// A backup kept on this device, encrypted with the device key. kind: manual | auto | pre-restore | pre-import
    @discardableResult
    public func createBackup(kind: String = "manual", note: String = "") throws -> BackupInfo {
        try FileManager.default.createDirectory(at: backupDir, withIntermediateDirectories: true)
        let sealed = try BackupCrypto.seal(try archive(portable: false, note: note), with: .device(deviceKey), appVersion: AgencyStore.appVersion)
        let name = uniqueName(kind)
        let url = backupDir.appendingPathComponent(name)
        let part = url.appendingPathExtension("part")
        try sealed.write(to: part, options: [.atomic, .completeFileProtection])
        try FileManager.default.moveItem(at: part, to: url)
        let check = verify(try Data(contentsOf: url), passphrase: nil)
        guard check.ok else {
            try? FileManager.default.removeItem(at: url)
            throw StoreError.verification(check.problems)
        }
        return listBackups().first { $0.name == name }!
    }

    /// A portable backup to share or keep elsewhere (Files, AirDrop, a computer), protected by a passphrase.
    public func exportBackup(passphrase: String, note: String = "") throws -> Data {
        try BackupCrypto.seal(try archive(portable: true, note: note), with: .passphrase(passphrase), appVersion: AgencyStore.appVersion)
    }

    /// Only automatic backups are pruned, oldest first.
    @discardableResult
    public func pruneAutoBackups(keep: Int) -> [String] {
        let autos = listBackups().filter { $0.kind == "auto" }
        var removed: [String] = []
        for b in autos.dropFirst(max(keep, 1)) {
            if (try? FileManager.default.removeItem(at: backupDir.appendingPathComponent(b.name))) != nil { removed.append(b.name) }
        }
        return removed
    }

    /// Makes an automatic backup when the last one is older than `hours` (setting auto_backup_hours; 0 = off).
    @discardableResult
    public func autoBackupIfDue() throws -> BackupInfo? {
        let a = try open()
        let hours = Int(try a.setting("auto_backup_hours", "24") ?? "24") ?? 24
        if hours <= 0 { return nil }
        if let last = listBackups().first(where: { $0.kind == "auto" }), Dates.now().timeIntervalSince(last.created) < Double(hours) * 3600 { return nil }
        let b = try createBackup(kind: "auto")
        pruneAutoBackups(keep: Int(try a.setting("backup_keep", "30") ?? "30") ?? 30)
        return b
    }

    // MARK: verify / restore

    public struct Verification {
        public var ok: Bool { problems.isEmpty }
        public var problems: [String] = []
        public var manifest: JSONValue = .null
        public var zip: Data?
        public var needsPassphrase = false
    }

    /// Opens an iPhone backup (.azbackup) or a desktop backup (.zip) and checks it completely:
    /// archive structure, manifest, SHA-256 of every file, database integrity and schema version.
    public func verify(_ data: Data, passphrase: String?) -> Verification {
        var v = Verification()
        var zipData = data
        if BackupCrypto.isContainer(data) {
            do {
                let h = try BackupCrypto.header(data)
                if h["kdf"].stringValue != "device" && passphrase == nil { v.needsPassphrase = true; v.problems.append("Enter the backup passphrase"); return v }
                zipData = try BackupCrypto.open(data, deviceKey: deviceKey, passphrase: passphrase)
            } catch {
                v.problems.append("\(error)")
                return v
            }
        }
        let zip: Zip.Reader
        do { zip = try Zip.Reader(zipData) } catch { v.problems.append("\(error)"); return v }
        for n in zip.names where n.hasPrefix("/") || n.hasPrefix("\\") || n.contains("\\") || n.contains(":") || n.split(separator: "/").contains("..") {
            v.problems.append("Unsafe path in archive: \(n)")
        }
        guard zip.names.contains("manifest.json") else { v.problems.append("manifest.json missing"); return v }
        do {
            v.manifest = try JSONValue.parse(try zip.read("manifest.json"))
        } catch { v.problems.append("manifest.json unreadable"); return v }
        if !["AirZelo", "Travel Agency CRM"].contains(v.manifest["app"].stringValue ?? "") { v.problems.append("Not a backup made by this app") }
        for (name, digest) in v.manifest["files"].objectValue ?? [:] {
            guard zip.names.contains(name) else { v.problems.append("Missing file: \(name)"); continue }
            do {
                if SHA256.hash(data: try zip.read(name)).hex != digest.stringValue { v.problems.append("Checksum mismatch: \(name)") }
            } catch { v.problems.append("Corrupt entry: \(name)") }
        }
        if zip.names.contains("agency.db") && v.problems.isEmpty {
            let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("az-verify-" + UUID().uuidString + ".db")
            defer { try? FileManager.default.removeItem(at: tmp) }
            do {
                try zip.read("agency.db").write(to: tmp)
                let db = try SQLiteDB(path: tmp.path, readOnly: true)
                defer { db.close() }
                let ic = try db.one("PRAGMA integrity_check")?.optStr("integrity_check") ?? "?"
                if ic != "ok" { v.problems.append("Database integrity check failed: \(ic)") }
                let fk = try db.query("PRAGMA foreign_key_check")
                if !fk.isEmpty { v.problems.append("Foreign key problems: \(fk.count)") }
                _ = try db.scalarInt("SELECT COUNT(*) FROM bookings")
                let ver = try db.scalarInt("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")
                if ver > Agency.schemaVersion { v.problems.append("Made by a newer AirZelo (data version \(ver)); update this app first") }
                if ver < Agency.schemaVersion { v.problems.append("Data version \(ver) is older than \(Agency.schemaVersion): open and back it up in the current desktop app first") }
            } catch {
                v.problems.append("Database unreadable: \(error)")
            }
        } else if !zip.names.contains("agency.db") {
            v.problems.append("agency.db missing")
        }
        v.zip = zipData
        return v
    }

    /// Restores a backup (iPhone .azbackup or desktop .zip). Verifies it fully, saves a safety backup of the
    /// current books, swaps the files in, re-encrypts passport numbers and checks the result; on any failure
    /// the previous books are put back. Returns the safety backup (nil when there were no books yet).
    @discardableResult
    public func restore(_ data: Data, passphrase: String? = nil, kind: String = "pre-restore") throws -> BackupInfo? {
        let v = verify(data, passphrase: passphrase)
        guard v.ok, let zipData = v.zip else { throw StoreError.verification(v.problems) }
        let zip = try Zip.Reader(zipData)
        var safety: BackupInfo?
        if exists {
            if agency == nil, AgencyStore.quickCheck(dbURL) != "ok" {
                // damaged books cannot be backed up; keep the files aside instead
                try? FileManager.default.copyItem(at: agencyDir, to: root.appendingPathComponent("damaged-" + UUID().uuidString.prefix(8)))
            } else {
                safety = try createBackup(kind: kind, note: "Automatic safety copy before restoring")
            }
        }
        let staging = root.appendingPathComponent("restore-staging-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: staging) }
        try zip.read("agency.db").write(to: staging.appendingPathComponent("agency.db"))
        for n in zip.names where (n.hasPrefix("uploads/") || n.hasPrefix("branding/logo-")) && !n.hasSuffix("/") {
            let dest = staging.appendingPathComponent(n)
            try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
            try zip.read(n).write(to: dest)
        }
        do {
            // passport numbers in desktop data are plain text: seal them with this device's key; sessions are not used on iPhone
            let s = try SQLiteDB(path: staging.appendingPathComponent("agency.db").path)
            try s.exec("BEGIN")
            for (table, col) in [("customers", "passport_no"), ("booking_passengers", "passport_no")] {
                for r in try s.query("SELECT id, \(col) FROM \(table) WHERE \(col) IS NOT NULL AND \(col) <> '' AND \(col) NOT LIKE 'azenc1:%'") {
                    try s.run("UPDATE \(table) SET \(col) = ? WHERE id = ?", [try crypto.seal(r.optStr(col), column: col), r.int("id")])
                }
            }
            try s.exec("DELETE FROM sessions")
            try s.exec("COMMIT")
            try s.exec("VACUUM")
            s.close()
        }
        close()
        let fm = FileManager.default
        let replaced = root.appendingPathComponent("agency-replaced-" + UUID().uuidString)
        if fm.fileExists(atPath: agencyDir.path) { try fm.moveItem(at: agencyDir, to: replaced) }
        do {
            try fm.moveItem(at: staging, to: agencyDir)
            try fm.createDirectory(at: staging, withIntermediateDirectories: true)  // so the deferred cleanup has something to remove
            let check = AgencyStore.quickCheck(dbURL)
            if check != "ok" { throw StoreError.corrupted(check) }
            try open()
        } catch {
            close()
            try? fm.removeItem(at: agencyDir)
            if fm.fileExists(atPath: replaced.path) { try? fm.moveItem(at: replaced, to: agencyDir) }
            throw error
        }
        try? fm.removeItem(at: replaced)  // its contents are inside the safety backup
        return safety
    }

    /// Restores the newest backup on this device that passes verification (used after the books fail their check).
    @discardableResult
    public func recoverFromLatestBackup() throws -> BackupInfo {
        close()
        for b in listBackups() {
            guard let d = try? Data(contentsOf: backupDir.appendingPathComponent(b.name)), verify(d, passphrase: nil).ok else { continue }
            try restore(d, kind: "pre-recovery")
            return b
        }
        throw StoreError.verification(["No usable backup was found on this device"])
    }

    public func backupData(_ info: BackupInfo) throws -> Data { try Data(contentsOf: backupDir.appendingPathComponent(info.name)) }
}
