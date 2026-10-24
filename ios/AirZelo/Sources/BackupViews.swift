import SwiftUI
import UniformTypeIdentifiers
import AirZeloKit

extension UTType {
    static let azBackup = UTType(exportedAs: "com.airzelo.backup", conformingTo: .data)
}

struct BackupFile: FileDocument {
    static var readableContentTypes: [UTType] { [.azBackup, .zip] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct BackupView: View {
    @EnvironmentObject var model: AppModel
    @State private var backups: [AgencyStore.BackupInfo] = []
    @State private var message: String?
    @State private var errorText: String?
    @State private var confirmRestore: AgencyStore.BackupInfo?
    @State private var exportPassphrase = ""
    @State private var exportPassphrase2 = ""
    @State private var askExport = false
    @State private var exportDoc: BackupFile?
    @State private var showExporter = false
    @State private var importing = false
    @State private var imported: Data?
    @State private var importPassphrase = ""
    @State private var askImportPassphrase = false
    @State private var confirmImport = false

    var body: some View {
        List {
            Section(footer: Text("Backups on this iPhone are encrypted with this device's key. Automatic backups run when you leave the app (setting: every \(autoHours) hours).")) {
                Button { backupNow() } label: { Label("Back up now", systemImage: "externaldrive.badge.plus") }
            }
            Section(footer: Text("An export is encrypted with a passphrase you choose, so you can keep it in Files, iCloud Drive or on a computer, or open it on another iPhone. Without the passphrase it cannot be read.")) {
                Button { askExport = true } label: { Label("Export a backup…", systemImage: "square.and.arrow.up.on.square") }
                Button { importing = true } label: { Label("Restore or import from a file…", systemImage: "square.and.arrow.down.on.square") }
                Text("Desktop AirZelo backups (.zip) can be imported here too.").font(.footnote).foregroundStyle(.secondary)
            }
            Section("On this iPhone") {
                if backups.isEmpty { Text("No backups yet.").foregroundStyle(.secondary) }
                ForEach(backups) { b in
                    Button { confirmRestore = b } label: {
                        VStack(alignment: .leading) {
                            Text(b.created.formatted(date: .abbreviated, time: .shortened)).foregroundStyle(.primary)
                            Text("\(Fmt.label(b.kind)) · \(ByteCountFormatter.string(fromByteCount: Int64(b.size), countStyle: .file))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            if let message { Section { Text(message).foregroundStyle(.green) } }
            if let errorText { Section { Text(errorText).foregroundStyle(.red) } }
        }
        .navigationTitle("Backup & restore")
        .task(id: model.revision) { backups = model.store?.listBackups() ?? [] }
        .confirmationDialog("Restore this backup?", isPresented: Binding(get: { confirmRestore != nil }, set: { if !$0 { confirmRestore = nil } }),
                            presenting: confirmRestore) { b in
            Button("Restore", role: .destructive) { restore(b) }
        } message: { _ in Text("Your current books are saved as a safety backup first, then replaced by this backup.") }
        .alert("Export passphrase", isPresented: $askExport) {
            SecureField("Passphrase (8+ characters)", text: $exportPassphrase)
            SecureField("Repeat passphrase", text: $exportPassphrase2)
            Button("Export") { export() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Keep this passphrase safe: the export cannot be opened without it.") }
        .fileExporter(isPresented: $showExporter, document: exportDoc, contentType: .azBackup,
                      defaultFilename: "AirZelo-backup-\(Dates.today()).azbackup") { result in
            if case .success = result { message = "Export saved." }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.azBackup, .zip, .data]) { result in
            guard case .success(let url) = result else { return }
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else { errorText = "The file could not be read."; return }
            imported = data
            let v = model.store?.verify(data, passphrase: nil)
            if v?.needsPassphrase == true { askImportPassphrase = true } else { confirmImport = true }
        }
        .alert("Backup passphrase", isPresented: $askImportPassphrase) {
            SecureField("Passphrase", text: $importPassphrase)
            Button("Continue") { confirmImport = true }
            Button("Cancel", role: .cancel) { imported = nil }
        }
        .confirmationDialog("Replace the books on this iPhone?", isPresented: $confirmImport) {
            Button("Restore from file", role: .destructive) { restoreImported() }
        } message: { Text("The file is checked completely first. Your current books are saved as a safety backup before they are replaced.") }
    }

    var autoHours: String { (try? model.agency?.setting("auto_backup_hours", "24")).flatMap { $0 } ?? "24" }

    func backupNow() {
        do {
            let b = try model.store!.createBackup()
            message = "Backup saved and verified (\(ByteCountFormatter.string(fromByteCount: Int64(b.size), countStyle: .file)))."
            errorText = nil
            model.changed()
        } catch { errorText = "\(error)" }
    }

    func export() {
        guard exportPassphrase == exportPassphrase2 else { errorText = "The passphrases do not match."; return }
        do {
            exportDoc = BackupFile(data: try model.store!.exportBackup(passphrase: exportPassphrase))
            exportPassphrase = ""; exportPassphrase2 = ""
            showExporter = true
            errorText = nil
        } catch { errorText = "\(error)" }
    }

    func restore(_ b: AgencyStore.BackupInfo) {
        do {
            try model.store!.restore(try model.store!.backupData(b))
            model.reopen()
            message = "Restored the backup from \(b.created.formatted(date: .abbreviated, time: .shortened))."
            errorText = nil
        } catch { errorText = "\(error)" }
    }

    func restoreImported() {
        guard let data = imported else { return }
        do {
            try model.store!.restore(data, passphrase: importPassphrase.isEmpty ? nil : importPassphrase)
            model.reopen()
            message = "Restored from the file."
            errorText = nil
        } catch { errorText = "\(error)" }
        imported = nil
        importPassphrase = ""
    }
}

// MARK: - settings

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var s: [String: String] = [:]
    @State private var errors = ErrorState()
    @State private var saved = false

    var body: some View {
        Form {
            Section("Company") {
                field("Display name", "business_name")
                field("Legal name", "legal_name")
                field("Tagline", "business_tagline")
                field("Address", "business_address")
                field("Phone", "business_phone")
                field("Email", "business_email")
                field("Website", "business_website")
                field("GSTIN", "business_gstin")
                field("State", "business_state")
                field("Brand colour (#RRGGBB)", "brand_color")
            }
            Section("Document numbers") {
                field("Invoice prefix", "invoice_prefix")
                field("Credit note prefix", "credit_note_prefix")
                field("Booking prefix", "booking_prefix")
                field("Receipt prefix", "receipt_prefix")
                field("Invoice terms", "invoice_terms")
                field("Invoice footer", "invoice_footer")
            }
            Section(header: Text("Payment details on tickets"), footer: Text("Shown to customers on the ticket + payment PDF.")) {
                field("Account name", "pay_account_name")
                field("Account number", "pay_account_number")
                field("IFSC", "pay_ifsc")
                field("Bank", "pay_bank_name")
                field("Branch", "pay_branch")
                field("UPI number", "pay_upi_number")
                field("UPI apps", "pay_upi_apps")
            }
            Section("Backups") {
                field("Automatic backup every (hours, 0 = off)", "auto_backup_hours")
                field("Automatic backups to keep", "backup_keep")
            }
            if saved { Text("Saved.").foregroundStyle(.green) }
        }
        .navigationTitle("Company & documents")
        .toolbar { Button("Save") { save() } }
        .errorAlert($errors)
        .task { s = (try? model.agency?.allSettings()) ?? [:] }
    }

    func field(_ label: String, _ key: String) -> some View {
        TextField(label, text: Binding(get: { s[key] ?? "" }, set: { s[key] = $0; saved = false }), axis: .vertical)
    }

    func save() {
        guard let a = model.agency else { return }
        var f: Fields = [:]
        for k in ["business_name", "legal_name", "business_tagline", "business_address", "business_phone", "business_email", "business_website",
                  "business_gstin", "business_state", "brand_color", "invoice_prefix", "credit_note_prefix", "booking_prefix", "receipt_prefix",
                  "invoice_terms", "invoice_footer", "pay_account_name", "pay_account_number", "pay_ifsc", "pay_bank_name", "pay_branch",
                  "pay_upi_number", "pay_upi_apps", "auto_backup_hours", "backup_keep"] { f[k] = .string(s[k] ?? "") }
        attempt($errors, f, { f in try a.updateSettings(f) }) { r in
            s = r
            saved = true
            model.changed()
        }
    }
}

struct OnlineFeaturesView: View {
    @State private var tried: OnlineFeature?
    @State private var message = ""
    var body: some View {
        List {
            Section(footer: Text("AirZelo works fully offline. These features need a live airline, portal or payment-gateway connection, which this version does not have, so they are switched off - nothing is simulated.")) {
                ForEach(OnlineFeature.allCases) { f in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack { Text(f.title).bold(); Spacer(); Text("Unavailable").font(.caption.bold()).foregroundStyle(.orange) }
                        Text(f.offlineAlternative).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Online features")
    }
}
