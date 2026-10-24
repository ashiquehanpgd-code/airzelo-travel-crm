import SwiftUI
import AirZeloKit

struct DashboardView: View {
    @EnvironmentObject var model: AppModel
    @State private var dash: Ledger.Dashboard?
    @State private var base = "INR"
    @State private var period = "month"

    var range: (String?, String?) {
        let today = Dates.today()
        switch period {
        case "today": return (today, today)
        case "year": return (String(today.prefix(4)) + "-01-01", today)
        case "fy":
            let y = Int(today.prefix(4))!, m = Int(today.dropFirst(5).prefix(2))!
            let start = m >= 4 ? y : y - 1
            return ("\(start)-04-01", today)
        default: return (String(today.prefix(8)) + "01", today)
        }
    }

    var body: some View {
        List {
            Picker("Period", selection: $period) {
                Text("Today").tag("today"); Text("Month").tag("month"); Text("FY").tag("fy"); Text("Year").tag("year")
            }.pickerStyle(.segmented)
            if let d = dash {
                Section("Profit") {
                    HStack { Text("Bookings").foregroundStyle(.secondary); Spacer(); Text("\(d.summary.bookings)").monospacedDigit() }
                    FigureRow(label: "Net sales (ex tax)", minor: d.summary.sales, currency: base)
                    FigureRow(label: "Gross profit", minor: d.summary.grossProfit, currency: base, strong: true)
                    FigureRow(label: "Operating expenses", minor: d.summary.operatingExpenses, currency: base)
                    FigureRow(label: "Net profit", minor: d.summary.netProfit, currency: base, strong: true)
                    FigureRow(label: "Tax collected (liability)", minor: d.summary.tax, currency: base)
                }
                Section("Cash") {
                    FigureRow(label: "Money in", minor: d.summary.cash.cashIn, currency: base)
                    FigureRow(label: "Money out", minor: d.summary.cash.cashOut, currency: base)
                    FigureRow(label: "Net cash flow", minor: d.summary.cash.netCashFlow, currency: base, strong: true)
                }
                Section("Who owes whom") {
                    FigureRow(label: "Customers owe you", minor: d.customerOutstanding, currency: base, strong: true)
                    FigureRow(label: "You owe suppliers", minor: d.supplierOutstanding, currency: base, strong: true)
                    if d.customerCreditBalances > 0 { FigureRow(label: "Customer advances / refunds due", minor: d.customerCreditBalances, currency: base) }
                    if d.supplierCreditBalances > 0 { FigureRow(label: "Supplier credits due to you", minor: d.supplierCreditBalances, currency: base) }
                }
                Section("Accounts") {
                    ForEach(d.accounts.filter { $0.active }, id: \.accountId) { a in
                        FigureRow(label: a.name, minor: a.balance, currency: a.currency)
                    }
                }
                if !d.pending.isEmpty {
                    Section("Payment pending (\(d.pendingCount))") {
                        ForEach(d.pending.indices, id: \.self) { i in
                            let r = d.pending[i]
                            NavigationLink { BookingDetailView(bookingId: r["id"]!.int) } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(r["customer_name"]!.text).font(.subheadline.bold())
                                        Text("\(r["ref"]!.text)  \(r["pnr"]!.text)").font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    MoneyText(minor: r["customer_outstanding"]!.int, currency: base, colored: true)
                                }
                            }
                        }
                    }
                }
                if !d.upcoming.isEmpty {
                    Section("Departures in the next 14 days") {
                        ForEach(d.upcoming.indices, id: \.self) { i in
                            let u = d.upcoming[i]
                            NavigationLink { BookingDetailView(bookingId: u["booking_id"]!.int) } label: {
                                VStack(alignment: .leading) {
                                    Text("\(u["origin"]!.text) → \(u["destination"]!.text)  \(u["flight_no"]!.text)").font(.subheadline.bold())
                                    Text("\(u["dep_at"]!.text.replacingOccurrences(of: "T", with: " "))  ·  \(u["lead_pax"]!.text)")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
                if !d.followUps.isEmpty {
                    Section("Follow-ups") {
                        ForEach(d.followUps.indices, id: \.self) { i in
                            let f = d.followUps[i]
                            NavigationLink { CustomerDetailView(customerId: f["customer_id"]!.int) } label: {
                                VStack(alignment: .leading) {
                                    Text(f["name"]!.text).font(.subheadline.bold())
                                    Text("\(Fmt.display(f["follow_up_date"]!.text)) · \(f["follow_up_note"]!.text)").font(.caption)
                                        .foregroundStyle(f["overdue"] == .bool(true) ? .red : .secondary)
                                }
                            }
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle((try? model.agency?.setting("business_name")).flatMap { $0?.isEmpty == false ? $0 : nil } ?? "AirZelo")
        .task(id: "\(model.revision)-\(period)") { load() }
        .refreshable { load() }
    }

    func load() {
        guard let a = model.agency, let L = try? Ledger(a) else { return }
        base = L.base
        dash = L.dashboard(from: range.0, to: range.1)
    }
}
