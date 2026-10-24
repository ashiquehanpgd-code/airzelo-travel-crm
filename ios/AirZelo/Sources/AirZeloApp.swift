import SwiftUI
import AirZeloKit

@main
struct AirZeloApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .task { model.start() }
        }
        .onChange(of: phase) { _, newPhase in model.phaseChanged(newPhase) }
    }
}

/// App state: the agency's books, the lock, and a revision counter that refreshes screens after a change.
@MainActor
final class AppModel: ObservableObject {
    @Published var locked = true
    @Published var hidden = false           // privacy cover in the app switcher
    @Published var agency: Agency?
    @Published var store: AgencyStore?
    @Published var startupProblem: String?
    @Published var corrupted = false
    @Published var revision = 0
    @Published var passcodeMissing = false

    /// UI tests start unlocked with throwaway books (DEBUG builds only).
    var uiTesting: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-uitest")
        #else
        return false
        #endif
    }

    func start() {
        guard store == nil else { return }
        do {
            let s: AgencyStore
            if uiTesting {
                let root = FileManager.default.temporaryDirectory.appendingPathComponent("uitest-" + UUID().uuidString)
                s = AgencyStore(root: root, deviceKey: .init(size: .bits256))
                locked = false
            } else {
                s = try AgencyStore.standard()
            }
            store = s
            agency = try s.open()
        } catch let e as AgencyStore.StoreError {
            if case .corrupted = e { corrupted = true }
            startupProblem = e.description
        } catch {
            startupProblem = "\(error)"
        }
    }

    func recover() {
        guard let store else { return }
        do {
            try store.recoverFromLatestBackup()
            agency = try store.open()
            corrupted = false
            startupProblem = nil
            changed()
        } catch {
            startupProblem = "\(error)"
        }
    }

    func reopen() {
        guard let store else { return }
        do { agency = try store.open(); changed() } catch { startupProblem = "\(error)" }
    }

    func changed() { revision += 1 }

    /// The automatic backup asks iOS for background time, so it is not cut off half way (a cut-off backup is only
    /// ever a .part file: the finished backups are untouched).
    func backupInBackground() {
        #if os(iOS)
        var task = UIBackgroundTaskIdentifier.invalid
        task = UIApplication.shared.beginBackgroundTask(withName: "AirZelo backup") {
            UIApplication.shared.endBackgroundTask(task)
            task = .invalid
        }
        _ = try? store?.autoBackupIfDue()
        if task != .invalid { UIApplication.shared.endBackgroundTask(task) }
        #else
        _ = try? store?.autoBackupIfDue()
        #endif
    }

    func phaseChanged(_ p: ScenePhase) {
        switch p {
        case .background:
            if !uiTesting { locked = true }
            hidden = true
            backupInBackground()
        case .inactive:
            hidden = true
        case .active:
            hidden = false
        @unknown default:
            break
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ZStack {
            if let problem = model.startupProblem {
                StartupProblemView(problem: problem)
            } else if model.locked {
                LockView()
            } else if model.agency != nil {
                MainTabs()
            } else {
                ProgressView("Opening your books…")
            }
            if model.hidden && !model.locked {
                PrivacyCover()
            }
        }
    }
}

struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color.accentColor.ignoresSafeArea()
            VStack(spacing: 10) {
                Image(systemName: "airplane.circle.fill").font(.system(size: 56))
                Text("AirZelo").font(.title2.bold())
            }.foregroundStyle(.white)
        }
    }
}

struct StartupProblemView: View {
    @EnvironmentObject var model: AppModel
    let problem: String
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 44)).foregroundStyle(.orange)
            Text("Your books could not be opened").font(.title3.bold())
            Text(problem).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            if model.corrupted {
                Button("Restore the latest backup") { model.recover() }.buttonStyle(.borderedProminent)
                Text("A copy of the damaged files is kept aside.").font(.footnote).foregroundStyle(.secondary)
            }
        }.padding(32)
    }
}

struct MainTabs: View {
    @EnvironmentObject var model: AppModel
    @State private var tab = "home"

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { DashboardView() }.tabItem { Label("Home", systemImage: "house") }.tag("home")
            NavigationStack { BookingListView() }.tabItem { Label("Bookings", systemImage: "airplane") }.tag("bookings")
            NavigationStack { CustomerListView() }.tabItem { Label("Customers", systemImage: "person.2") }.tag("customers")
            NavigationStack { TransactionListView() }.tabItem { Label("Money", systemImage: "indianrupeesign.circle") }.tag("money")
            NavigationStack { MoreView() }.tabItem { Label("More", systemImage: "ellipsis.circle") }.tag("more")
        }
        .onAppear {
            // UI tests (DEBUG only) can open a given tab directly: -uitest-tab bookings
            let args = ProcessInfo.processInfo.arguments
            if model.uiTesting, let i = args.firstIndex(of: "-uitest-tab"), i + 1 < args.count { tab = args[i + 1] }
        }
    }
}

struct MoreView: View {
    var body: some View {
        List {
            Section("Books") {
                NavigationLink { ReportListView() } label: { Label("Reports", systemImage: "chart.bar.doc.horizontal") }
                NavigationLink { InvoiceListView() } label: { Label("Invoices & credit notes", systemImage: "doc.text") }
                NavigationLink { SupplierListView() } label: { Label("Suppliers", systemImage: "building.2") }
                NavigationLink { AccountListView() } label: { Label("Cash & bank accounts", systemImage: "banknote") }
            }
            Section("Safety") {
                NavigationLink { BackupView() } label: { Label("Backup & restore", systemImage: "externaldrive.badge.checkmark") }
            }
            Section("Settings") {
                NavigationLink { SettingsView() } label: { Label("Company & documents", systemImage: "gearshape") }
                NavigationLink { OnlineFeaturesView() } label: { Label("Online features", systemImage: "wifi.slash") }
            }
        }
        .navigationTitle("More")
    }
}
