import SwiftUI
import AirZeloKit

let REPORT_TITLES: [(String, String, String)] = [
    ("profit", "Profit", "chart.line.uptrend.xyaxis"), ("sales", "Sales", "cart"), ("bookings", "Bookings", "airplane"),
    ("customer_outstanding", "Customer outstanding", "person.crop.circle.badge.exclamationmark"),
    ("supplier_outstanding", "Supplier outstanding", "building.2"), ("expenses", "Expenses", "creditcard"),
    ("cashbook", "Cashbook (all accounts)", "book"), ("refunds", "Refunds & cancellations", "arrow.uturn.backward"),
    ("commission", "Commission income", "percent"), ("monthly", "Monthly summary", "calendar"), ("yearly", "Yearly summary", "calendar.badge.clock"),
    ("airline", "Airline-wise sales", "airplane.circle"), ("supplier_purchases", "Supplier-wise purchases", "shippingbox")]

struct ReportListView: View {
    var body: some View {
        List(REPORT_TITLES, id: \.0) { r in
            NavigationLink { ReportView(name: r.0) } label: { Label(r.1, systemImage: r.2) }
        }
        .navigationTitle("Reports")
    }
}

struct ReportView: View {
    @EnvironmentObject var model: AppModel
    let name: String
    var fixedParams: [String: String] = [:]
    @State private var from = String(Dates.today().prefix(5)) + "01-01"
    @State private var to = Dates.today()
    @State private var allTime = false
    @State private var report: Report?
    @State private var error: String?

    var usesRange: Bool { !["customer_outstanding", "supplier_outstanding", "monthly", "yearly"].contains(name) }

    var body: some View {
        List {
            if usesRange {
                Section {
                    Toggle("All dates", isOn: $allTime)
                    if !allTime {
                        ISODateField(label: "From", iso: $from)
                        ISODateField(label: "To", iso: $to)
                    }
                }
            }
            if let error { Text(error).foregroundStyle(.red) }
            if let r = report {
                if let note = r.note { Text(note).font(.footnote).foregroundStyle(.secondary) }
                if let summary = r.summary, !summary.isEmpty {
                    Section("Summary") {
                        ForEach(summary.indices, id: \.self) { i in
                            FigureRow(label: summary[i]["label"].text, minor: summary[i]["value"].int, currency: r.base, strong: summary[i]["strong"] == .bool(true))
                        }
                    }
                }
                Section("Totals") {
                    ForEach(r.columns.filter { r.totals[$0.key] != nil }, id: \.key) { c in
                        if c.isMoney { FigureRow(label: c.label, minor: r.totals[c.key]!.int, currency: r.base) }
                        else { LabeledContent(c.label, value: r.totals[c.key]!.text) }
                    }
                }
                Section("\(r.rows.count) rows") {
                    ForEach(r.rows.prefix(300).indices, id: \.self) { i in ReportRowView(row: r.rows[i], columns: r.columns, base: r.base) }
                    if r.rows.count > 300 { Text("Showing 300 of \(r.rows.count). Share the CSV for all rows.").font(.footnote) }
                }
            }
        }
        .navigationTitle(report?.title ?? "Report")
        .toolbar {
            if let r = report {
                ShareLink(item: shareFile("\(r.name)-\(Dates.today()).csv", Data(r.csv.utf8))) { Image(systemName: "square.and.arrow.up") }
            }
        }
        .task(id: "\(model.revision)|\(from)|\(to)|\(allTime)") { load() }
    }

    func load() {
        guard let a = model.agency else { return }
        var p = fixedParams
        if usesRange && !allTime { p["from"] = from; p["to"] = to }
        if name == "monthly" { p["year"] = String(to.prefix(4)) }
        do { report = try Ledger(a).report(name, ReportParams(p)); error = nil } catch { self.error = "\(error)" }
    }
}

struct ReportRowView: View {
    let row: [String: JSONValue]
    let columns: [ReportColumn]
    let base: String
    var body: some View {
        let text = columns.filter { !$0.isMoney }
        let money = columns.filter { $0.isMoney }
        VStack(alignment: .leading, spacing: 4) {
            Text(text.prefix(3).map { row[$0.key]?.text ?? "" }.filter { !$0.isEmpty }.joined(separator: " · ")).font(.subheadline.bold())
            if text.count > 3 {
                Text(text.dropFirst(3).map { row[$0.key]?.text ?? "" }.filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            ForEach(money, id: \.key) { c in
                if let v = row[c.key]?.intValue, v != 0 {
                    HStack { Text(c.label).font(.caption).foregroundStyle(.secondary); Spacer(); Text(c.type == "money_raw" ? fmt(v) : Fmt.money(v, base)).font(.caption.monospacedDigit()) }
                }
            }
        }
    }
}

struct CashbookView: View {
    let accountId: Int
    let name: String
    var body: some View { ReportView(name: "cashbook", fixedParams: ["account_id": String(accountId)]).navigationTitle(name) }
}

// MARK: - invoices

struct InvoiceListView: View {
    @EnvironmentObject var model: AppModel
    @State private var rows: [Row] = []
    @State private var query = ""
    var body: some View {
        List(rows.indices, id: \.self) { i in
            let r = rows[i]
            NavigationLink { InvoiceDetailView(invoiceId: r.int("id")) } label: {
                HStack {
                    VStack(alignment: .leading) {
                        Text(r.str("number")).font(.subheadline.bold())
                        Text("\(r.str("customer_name")) · \(r.str("booking_ref")) · \(Fmt.display(r.str("issue_date")))").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing) {
                        MoneyText(minor: r.int("total"), currency: r.str("currency")).font(.caption)
                        if r.str("status") == "cancelled" { StatusBadge(status: "cancelled") }
                    }
                }
            }
        }
        .searchable(text: $query)
        .navigationTitle("Invoices")
        .task(id: "\(model.revision)|\(query)") { rows = (try? model.agency?.listInvoices(["q": query])) ?? [] }
    }
}

struct InvoiceDetailView: View {
    @EnvironmentObject var model: AppModel
    let invoiceId: Int
    @State private var pdf: (Data, String)?
    @State private var status = ""
    @State private var cancelling = false
    @State private var reason = ""
    @State private var errors = ErrorState()

    var body: some View {
        Group {
            if let pdf { PDFPreview(data: pdf.0, fileName: pdf.1) } else { ProgressView() }
        }
        .toolbar {
            if status == "issued" { Button("Cancel invoice", role: .destructive) { cancelling = true } }
        }
        .alert("Cancel this document?", isPresented: $cancelling) {
            TextField("Reason", text: $reason)
            Button("Cancel document", role: .destructive) { cancel() }
            Button("Keep", role: .cancel) {}
        } message: { Text("Issued documents are never deleted; it will be marked cancelled with your reason.") }
        .errorAlert($errors)
        .task(id: model.revision) { load() }
    }

    func load() {
        guard let a = model.agency else { return }
        if let r = try? a.invoicePDF(invoiceId) { pdf = (r.data, r.fileName) }
        status = (try? a.getInvoice(invoiceId).row.str("status")) ?? ""
    }

    func cancel() {
        guard let a = model.agency else { return }
        attempt($errors, ["reason": .string(reason)], { f in try a.cancelInvoice(invoiceId, f) }) { _ in model.changed() }
    }
}
