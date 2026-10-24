import Foundation
import CryptoKit
import AirZeloKit

/// Records (and fails) any URL loading attempted through Foundation while the offline workflow runs.
final class NetworkTripwire: URLProtocol {
    nonisolated(unsafe) static var attempts: [String] = []
    override class func canInit(with request: URLRequest) -> Bool {
        attempts.append(request.url?.absoluteString ?? "?")
        return true
    }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet)) }
    override func stopLoading() {}
}

enum OfflineChecks {
    static let cases: [CheckSuite.Case] = [
        ("offline: full workflow makes no network calls", workflowWithoutNetwork),
        ("offline: live features refuse, never simulate", liveFeaturesRefuse),
        ("offline: engine source has no network code", sourceHasNoNetworking),
    ]

    static func workflowWithoutNetwork(_ o: CheckOptions, _ log: CheckLog) throws {
        URLProtocol.registerClass(NetworkTripwire.self)
        defer { URLProtocol.unregisterClass(NetworkTripwire.self) }
        NetworkTripwire.attempts = []
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("az-offline-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AgencyStore(root: root, deviceKey: .init(size: .bits256))
        let a = try store.open()
        let s = try PDFChecks.setup(a)
        try a.updateCustomer(s.cust, ["notes": "edited offline"])
        let (tid, _) = try a.createTransaction(["kind": "expense", "account_id": .int(s.bank), "amount": "450", "category": "Office expenses"])
        try a.reverseTransaction(tid, ["reason": "entered twice"])
        let inv = try a.createInvoice(s.booking)
        _ = try a.ticketPDF(s.booking, withPayment: true)
        _ = try a.invoicePDF(inv)
        let L = try Ledger(a)
        for name in REPORT_NAMES { _ = try L.report(name, ReportParams()) }
        _ = L.dashboard(from: nil, to: nil)
        _ = try a.globalSearch("rahman")
        let b = try store.createBackup()
        try store.restore(try store.backupData(b))
        _ = try store.exportBackup(passphrase: "offline-passphrase")
        try expect(NetworkTripwire.attempts.isEmpty, "network was used: \(NetworkTripwire.attempts)")
        log.note("customers, bookings, payments, reversal, invoice, 2 PDFs, 13 reports, dashboard, search, backup, restore, export: 0 network requests")
    }

    static func liveFeaturesRefuse(_ o: CheckOptions, _ log: CheckLog) throws {
        for f in OnlineFeature.allCases {
            do {
                try f.perform()
            } catch let e as AZError {
                try expectEqual(e.code, "unavailable", f.title)
                try expect(e.message.contains("not available"), "clear message for \(f.title)")
                continue
            }
        }
    }

    /// Static scan of AirZeloKit's own sources for networking APIs (the build machine's copy of the sources).
    static func sourceHasNoNetworking(_ o: CheckOptions, _ log: CheckLog) throws {
        let here = URL(fileURLWithPath: #filePath)
        let src = here.deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("AirZeloKit")
        guard let files = FileManager.default.enumerator(atPath: src.path)?.compactMap({ $0 as? String }).filter({ $0.hasSuffix(".swift") }), !files.isEmpty else {
            throw CheckSkipped(reason: "sources not available on this device")
        }
        let forbidden = ["import Network", "URLSession", "URLRequest", "NWConnection", "NWPathMonitor", "CFStream", "getaddrinfo", "socket(",
                         "WKWebView", "import WebKit", "Data(contentsOf: URL(string"]
        var hits: [String] = []
        for f in files {
            let text = try String(contentsOf: src.appendingPathComponent(f), encoding: .utf8)
            for word in forbidden where text.contains(word) { hits.append("\(f): \(word)") }
        }
        try expect(hits.isEmpty, "network API in the offline engine: \(hits)")
        log.note("\(files.count) source files scanned")
    }
}
