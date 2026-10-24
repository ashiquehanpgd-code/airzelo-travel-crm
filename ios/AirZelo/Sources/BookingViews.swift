import SwiftUI
import AirZeloKit

// MARK: - list

struct BookingListView: View {
    @EnvironmentObject var model: AppModel
    @State private var query = ""
    @State private var status = ""
    @State private var rows: [[String: JSONValue]] = []
    @State private var creating = false

    var body: some View {
        List {
            Picker("Status", selection: $status) {
                Text("All").tag("")
                ForEach(BOOKING_STATUSES, id: \.self) { Text(Fmt.label($0)).tag($0) }
            }
            ForEach(rows.indices, id: \.self) { i in
                let r = rows[i]
                NavigationLink { BookingDetailView(bookingId: r["id"]!.int) } label: { BookingRow(r: r) }
            }
            if rows.isEmpty { Text(query.isEmpty ? "No bookings yet." : "No saved booking matches.").foregroundStyle(.secondary) }
        }
        .searchable(text: $query, prompt: "PNR, name, ticket no., phone")
        .navigationTitle("Bookings")
        .toolbar { Button("New booking", systemImage: "plus") { creating = true }.accessibilityIdentifier("new-booking") }
        .sheet(isPresented: $creating) { NavigationStack { BookingFormView(bookingId: nil) } }
        .task(id: "\(model.revision)|\(query)|\(status)") { load() }
    }

    func load() {
        guard let a = model.agency else { return }
        rows = (try? a.searchBookings(["q": query, "status": status])) ?? []
        if query.isEmpty { rows.sort { ($0["booking_date"]!.text, $0["id"]!.int) > ($1["booking_date"]!.text, $1["id"]!.int) } }
    }
}

struct BookingRow: View {
    let r: [String: JSONValue]
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(r["customer_name"]!.text).font(.subheadline.bold())
                Spacer()
                StatusBadge(status: r["status"]!.text)
            }
            HStack {
                Text([r["ref"]!.text, r["pnr"]!.text, r["route"]!.text].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                if r["customer_outstanding"]!.int > 0 { MoneyText(minor: r["customer_outstanding"]!.int, colored: true).font(.caption) }
            }
        }
    }
}

// MARK: - detail

struct BookingDetailView: View {
    @EnvironmentObject var model: AppModel
    let bookingId: Int
    @State private var detail: Agency.BookingDetail?
    @State private var errors = ErrorState()
    @State private var editing = false
    @State private var paying = false
    @State private var payingSupplier = false
    @State private var refunding = false
    @State private var pdf: (Data, String)?
    @State private var showPDF = false

    var body: some View {
        List {
            if let d = detail {
                let b = d.booking, c = b.calc, base = d.base
                Section {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(b.customerName).font(.headline)
                            Text("\(b.ref)  ·  PNR \(b.pnr ?? "-")").font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing) {
                            StatusBadge(status: b.status)
                            StatusBadge(status: c.paymentStatus).accessibilityIdentifier("payment-status")
                        }
                    }
                    if !d.duplicates.isEmpty {
                        Label("Same PNR in \(d.duplicates.map { $0.str("ref") }.joined(separator: ", "))", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange).font(.footnote)
                    }
                }
                Section("Customer") {
                    FigureRow(label: "Billed", minor: c.customerPayable - c.customerCredits, currency: base)
                    FigureRow(label: "Received", minor: c.received - c.refunded, currency: base)
                    FigureRow(label: c.customerOutstanding >= 0 ? "Outstanding" : "Due back to customer", minor: abs(c.customerOutstanding), currency: base, strong: true)
                }
                Section("Supplier\(b.supplierName.isEmpty ? "" : " · " + b.supplierName)") {
                    FigureRow(label: "Payable (after commission)", minor: c.supplierPayable - c.supplierCredits, currency: base)
                    FigureRow(label: "Paid", minor: c.paidSupplier - c.supplierRefunds, currency: base)
                    FigureRow(label: c.supplierOutstanding >= 0 ? "Outstanding" : "Due from supplier", minor: abs(c.supplierOutstanding), currency: base, strong: true)
                }
                Section("Profit") {
                    if b.currency != base { Text("\(b.currency) at \(b.row.str("exchange_rate")) = 1 \(b.currency)").font(.caption).foregroundStyle(.secondary) }
                    FigureRow(label: "Revenue (ex tax)", minor: c.revenue, currency: base)
                    FigureRow(label: "Cost", minor: c.cost, currency: base)
                    FigureRow(label: "Commission", minor: c.commissionNet, currency: base)
                    if c.directExpenses != 0 { FigureRow(label: "Direct expenses", minor: c.directExpenses, currency: base) }
                    FigureRow(label: "Gross profit", minor: c.grossProfit, currency: base, strong: true)
                    if c.taxNet != 0 { FigureRow(label: "Tax collected (liability)", minor: c.taxNet, currency: base) }
                }
                Section("Passengers") {
                    ForEach(d.passengers.indices, id: \.self) { i in
                        let p = d.passengers[i]
                        VStack(alignment: .leading) {
                            Text([p["title"]!.text, p["name"]!.text].filter { !$0.isEmpty }.joined(separator: " ")).bold()
                            Text([PAX_LABEL[p["pax_type"]!.text] ?? "", p["ticket_number"]!.text.isEmpty ? "No ticket no." : "Ticket " + p["ticket_number"]!.text,
                                  p["passport_no"]!.text.isEmpty ? "" : "Passport " + FieldCrypto.display(p["passport_no"]!.text)].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if !d.segments.isEmpty {
                    Section("Flights") {
                        ForEach(d.segments.indices, id: \.self) { i in
                            let s = d.segments[i]
                            VStack(alignment: .leading) {
                                Text("\(s.str("origin")) → \(s.str("destination"))  \(s.str("airline")) \(s.str("flight_no"))").bold()
                                Text([s.str("dep_at").replacingOccurrences(of: "T", with: " "), s.str("airline_pnr").isEmpty ? "" : "Airline PNR " + s.str("airline_pnr")]
                                        .filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section("Actions") {
                    Button { paying = true } label: { Label("Record customer payment", systemImage: "arrow.down.circle") }
                        .accessibilityIdentifier("record-payment")
                    if b.supplierId != nil {
                        Button { payingSupplier = true } label: { Label("Record supplier payment", systemImage: "arrow.up.circle") }
                    }
                    if b.committed { Button { refunding = true } label: { Label("Refund / cancel", systemImage: "arrow.uturn.backward.circle") } }
                    Button { makePDF(payment: false) } label: { Label("Ticket PDF", systemImage: "doc.richtext") }
                    Button { makePDF(payment: true) } label: { Label("Ticket + payment details PDF", systemImage: "doc.richtext.fill") }
                    if b.committed && !d.invoices.contains(where: { $0.str("doc_type") == "invoice" && $0.str("status") == "issued" }) {
                        Button { issueInvoice() } label: { Label("Issue invoice", systemImage: "doc.badge.plus") }
                    }
                    if !b.committed {
                        Menu {
                            ForEach(["confirmed", "ticketed", "on_hold", "enquiry", "cancelled"], id: \.self) { s in
                                Button(Fmt.label(s)) { setStatus(s) }
                            }
                        } label: { Label("Change status", systemImage: "flag") }
                    } else if b.status == "confirmed" {
                        Button { setStatus("ticketed") } label: { Label("Mark ticketed", systemImage: "checkmark.seal") }
                    }
                }
                if !d.invoices.isEmpty {
                    Section("Invoices & credit notes") {
                        ForEach(d.invoices.indices, id: \.self) { i in
                            let inv = d.invoices[i]
                            NavigationLink { InvoiceDetailView(invoiceId: inv.int("id")) } label: {
                                HStack { Text(inv.str("number")); Spacer(); StatusBadge(status: inv.str("status")) }
                            }
                        }
                    }
                }
                if !d.transactions.isEmpty {
                    Section("Money") {
                        ForEach(d.transactions.indices, id: \.self) { i in TransactionRow(r: d.transactions[i]) }
                    }
                }
                if !d.adjustments.isEmpty {
                    Section("Adjustments") {
                        ForEach(d.adjustments, id: \.id) { a in
                            VStack(alignment: .leading) {
                                Text("\(a.adjNo) · \(Fmt.label(a.kind))\(a.sign < 0 ? " (reversal)" : "")").bold()
                                Text("Customer credit \(fmt(a["customer_credit"])) · Supplier credit \(fmt(a["supplier_credit"]))").font(.caption)
                            }
                        }
                    }
                }
                Section("History") {
                    ForEach(d.history.prefix(30).indices, id: \.self) { i in
                        let h = d.history[i]
                        VStack(alignment: .leading) {
                            Text(h.str("detail")).font(.footnote)
                            Text(h.str("at").replacingOccurrences(of: "T", with: " ")).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(detail?.booking.ref ?? "Booking")
        .toolbar { Button("Edit") { editing = true } }
        .sheet(isPresented: $editing) { NavigationStack { BookingFormView(bookingId: bookingId) } }
        .sheet(isPresented: $paying) { NavigationStack { TransactionFormView(preset: presetPayment(supplier: false)) } }
        .sheet(isPresented: $payingSupplier) { NavigationStack { TransactionFormView(preset: presetPayment(supplier: true)) } }
        .sheet(isPresented: $refunding) { NavigationStack { RefundFormView(bookingId: bookingId) } }
        .navigationDestination(isPresented: $showPDF) { if let pdf { PDFPreview(data: pdf.0, fileName: pdf.1) } }
        .errorAlert($errors)
        .task(id: model.revision) { load() }
    }

    func presetPayment(supplier: Bool) -> TransactionFormView.Preset {
        let c = detail?.booking.calc
        return .init(kind: supplier ? "supplier_payment" : "customer_receipt", bookingId: bookingId,
                     customerId: detail?.booking.customerId, supplierId: detail?.booking.supplierId,
                     amount: max(supplier ? (c?.supplierOutstanding ?? 0) : (c?.customerOutstanding ?? 0), 0))
    }

    func load() { detail = try? model.agency?.getBooking(bookingId) }

    func setStatus(_ s: String) {
        guard let a = model.agency else { return }
        attempt($errors, [:], { _ in try a.setBookingStatus(bookingId, s) }) { _ in model.changed() }
    }

    func issueInvoice() {
        guard let a = model.agency else { return }
        attempt($errors, [:], { _ in try a.createInvoice(bookingId) }) { _ in model.changed() }
    }

    func makePDF(payment: Bool) {
        guard let a = model.agency else { return }
        attempt($errors, [:], { _ in try a.ticketPDF(bookingId, withPayment: payment) }) { r in
            pdf = (r.data, r.fileName)
            showPDF = true
        }
    }
}

let PAX_LABEL = ["ADT": "Adult", "CHD": "Child", "INF": "Infant"]

// MARK: - form

struct PaxDraft: Identifiable { let id = UUID(); var title = "", name = "", type = "ADT", ticket = "", passport = "", notes = "" }
struct SegDraft: Identifiable {
    let id = UUID()
    var direction = "outbound", airline = "", flightNo = "", origin = "", destination = "", airlinePnr = "", baggage = ""
    var dep = Date(), arr = Date(), hasDep = false, hasArr = false
    var cabin = ""
    /// A stored date without a time (e.g. from the desktop) is kept as it is unless the user sets a time.
    var depRaw: String?, arrRaw: String?
}

struct BookingFormView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let bookingId: Int?

    @State private var customers: [Row] = []
    @State private var suppliers: [Row] = []
    @State private var currencies: [Row] = []
    @State private var taxProfiles: [Row] = []
    @State private var customerId = 0
    @State private var newCustomerName = ""
    @State private var newCustomerPhone = ""
    @State private var supplierId = 0
    @State private var status = "confirmed"
    @State private var serviceType = "flight"
    @State private var tripType = "one_way"
    @State private var bookingDate = Dates.today()
    @State private var travelDate = ""
    @State private var pnr = ""
    @State private var currency = "INR"
    @State private var rate = ""
    @State private var purchase = ""
    @State private var selling = ""
    @State private var commission = ""
    @State private var service = ""
    @State private var other = ""
    @State private var taxProfileId = 0
    @State private var notes = ""
    @State private var pax: [PaxDraft] = [PaxDraft()]
    @State private var segs: [SegDraft] = []
    @State private var errors = ErrorState()
    @State private var loaded = false
    /// The booking as stored (every field); edits are applied on top, so nothing the form does not show is lost.
    @State private var original: Fields = [:]

    var base: String { model.agency?.baseCurrency ?? "INR" }

    var body: some View {
        // A plain (non-lazy) scroll view: every field always exists, so nothing is released while scrolling or hidden
        // from VoiceOver / UI tests behind the keyboard, as happened with the lazy Form.
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
            FormCard("Customer") {
                LabeledPicker("Customer", selection: $customerId) {
                    Text("New customer").tag(0)
                    ForEach(customers.indices, id: \.self) { i in Text(customers[i].str("name")).tag(customers[i].int("id")) }
                }
                if customerId == 0 {
                    TextField("Name", text: $newCustomerName).accessibilityIdentifier("new-customer-name")
                    TextField("Phone", text: $newCustomerPhone).phoneKeyboard()
                }
            }
            FormCard("Booking") {
                LabeledPicker("Status", selection: $status) { ForEach(BOOKING_STATUSES, id: \.self) { Text(Fmt.label($0)).tag($0) } }
                LabeledPicker("Service", selection: $serviceType) { ForEach(SERVICE_TYPES, id: \.self) { Text(Fmt.label($0)).tag($0) } }
                LabeledPicker("Trip", selection: $tripType) {
                    Text("One way").tag("one_way"); Text("Return").tag("round_trip"); Text("Multi-city").tag("multi_city")
                }
                TextField("PNR", text: $pnr).capitals().accessibilityIdentifier("pnr")
                LabeledPicker("Supplier", selection: $supplierId) {
                    Text("None").tag(0)
                    ForEach(suppliers.indices, id: \.self) { i in Text(suppliers[i].str("name")).tag(suppliers[i].int("id")) }
                }
                ISODateField(label: "Booking date", iso: $bookingDate)
                OptionalDateField(label: "Travel date", iso: $travelDate)
            }
            FormCard("Prices") {
                LabeledPicker("Currency", selection: $currency) {
                    ForEach(currencies.indices, id: \.self) { i in Text(currencies[i].str("code")).tag(currencies[i].str("code")) }
                }
                if currency != base { AmountField(label: "Rate (1 \(currency) in \(base))", text: $rate) }
                AmountField(label: "Purchase cost", text: $purchase)
                AmountField(label: "Selling price", text: $selling, id: "selling-price")
                AmountField(label: "Commission", text: $commission)
                AmountField(label: "Service charge", text: $service)
                AmountField(label: "Other income", text: $other)
                LabeledPicker("Tax", selection: $taxProfileId) {
                    Text("No tax").tag(0)
                    ForEach(taxProfiles.indices, id: \.self) { i in Text(taxProfiles[i].str("name")).tag(taxProfiles[i].int("id")) }
                }
            }
            FormCard("Passengers") {
                ForEach($pax) { $p in
                    VStack(spacing: 10) {
                        HStack {
                            TextField("Title", text: $p.title).capitals().frame(width: 60)
                            TextField("Passenger name", text: $p.name).accessibilityIdentifier("pax-name")
                        }
                        LabeledPicker("Type", selection: $p.type) { Text("Adult").tag("ADT"); Text("Child").tag("CHD"); Text("Infant").tag("INF") }
                        TextField("E-ticket number", text: $p.ticket).capitals()
                        if pax.count > 1 {
                            Button("Remove passenger", role: .destructive) { pax.removeAll { $0.id == p.id } }.font(.footnote)
                        }
                        if FieldCrypto.isSealed(p.passport) {
                            Text("Passport: encrypted on another device (kept as is)").font(.caption).foregroundStyle(.secondary)
                        } else {
                            TextField("Passport number (encrypted)", text: $p.passport).capitals()
                        }
                    }
                }
                Button("Add passenger") { pax.append(PaxDraft()) }
            }
            FormCard("Flights") {
                ForEach($segs) { $s in
                    VStack(spacing: 10) {
                        LabeledPicker("Direction", selection: $s.direction) { Text("Onward").tag("outbound"); Text("Return").tag("return"); Text("Other").tag("other") }
                        HStack { TextField("Airline", text: $s.airline); TextField("Flight no.", text: $s.flightNo).capitals() }
                        HStack { TextField("From (COK)", text: $s.origin).capitals(); TextField("To (DXB)", text: $s.destination).capitals() }
                        Toggle("Departure time", isOn: $s.hasDep)
                        if s.hasDep { DatePicker("Departs", selection: $s.dep) }
                        Toggle("Arrival time", isOn: $s.hasArr)
                        if s.hasArr { DatePicker("Arrives", selection: $s.arr) }
                        HStack { TextField("Baggage", text: $s.baggage); TextField("Airline PNR", text: $s.airlinePnr).capitals() }
                        Button("Remove flight", role: .destructive) { segs.removeAll { $0.id == s.id } }.font(.footnote)
                    }
                }
                Button("Add flight") { segs.append(SegDraft()) }
            }
            FormCard("Notes") { TextField("Notes", text: $notes, axis: .vertical) }
            }
            .padding()
            .textFieldStyle(.roundedBorder)
        }
        .scrollDismissesKeyboard(.interactively)
        .keyboardDoneButton()
        .background(Color.secondary.opacity(0.08))
        .navigationTitle(bookingId == nil ? "New booking" : "Edit booking")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) { Button("Save booking") { save() }.accessibilityIdentifier("save-booking") }
        }
        .errorAlert($errors)
        .task { if !loaded { load(); loaded = true } }
    }

    func load() {
        guard let a = model.agency else { return }
        customers = ((try? a.db.query("SELECT id, name FROM customers WHERE active = 1 ORDER BY name COLLATE NOCASE")) ?? [])
        suppliers = ((try? a.db.query("SELECT id, name FROM suppliers WHERE active = 1 ORDER BY name COLLATE NOCASE")) ?? [])
        currencies = ((try? a.db.query("SELECT code FROM currencies WHERE active = 1 ORDER BY CASE code WHEN 'INR' THEN 0 WHEN 'SAR' THEN 1 ELSE 2 END, code")) ?? [])
        taxProfiles = ((try? a.db.query("SELECT id, name FROM tax_profiles WHERE active = 1 AND basis <> 'none' ORDER BY id")) ?? [])
        currency = base
        guard let bid = bookingId, let d = try? a.getBooking(bid), let o = try? a.bookingEditFields(bid) else { return }
        original = o
        let r = d.booking.row
        customerId = r.int("customer_id"); supplierId = r.optInt("supplier_id") ?? 0
        status = r.str("status"); serviceType = r.str("service_type"); tripType = r.str("trip_type")
        bookingDate = r.str("booking_date"); travelDate = r.str("travel_date"); pnr = r.str("pnr")
        currency = r.str("currency"); rate = r.str("currency") == base ? "" : r.str("exchange_rate")
        purchase = Fmt.amountText(r.int("purchase_cost")); selling = Fmt.amountText(r.int("selling_price"))
        commission = Fmt.amountText(r.int("commission")); service = Fmt.amountText(r.int("service_charge")); other = Fmt.amountText(r.int("other_income"))
        taxProfileId = r.optInt("tax_profile_id") ?? 0
        notes = r.str("notes")
        pax = d.passengers.map { p in PaxDraft(title: p["title"]!.text, name: p["name"]!.text, type: p["pax_type"]!.text,
                                               ticket: p["ticket_number"]!.text, passport: p["passport_no"]!.text, notes: p["notes"]?.text ?? "") }
        segs = d.segments.map { s in
            var x = SegDraft(direction: s.str("direction"), airline: s.str("airline"), flightNo: s.str("flight_no"), origin: s.str("origin"),
                             destination: s.str("destination"), airlinePnr: s.str("airline_pnr"), baggage: s.str("baggage"))
            x.cabin = s.str("cabin")
            if let d = Fmt.isoTime.date(from: s.str("dep_at")) { x.dep = d; x.hasDep = true } else if !s.str("dep_at").isEmpty { x.depRaw = s.str("dep_at") }
            if let d = Fmt.isoTime.date(from: s.str("arr_at")) { x.arr = d; x.hasArr = true } else if !s.str("arr_at").isEmpty { x.arrRaw = s.str("arr_at") }
            return x
        }
    }

    var fields: Fields {
        var f = original
        for (k, v) in ["status": .string(status), "service_type": .string(serviceType), "trip_type": .string(tripType),
                         "booking_date": .string(bookingDate), "travel_date": .string(travelDate), "pnr": .string(pnr),
                         "currency": .string(currency), "exchange_rate": .string(rate), "purchase_cost": .string(purchase),
                         "selling_price": .string(selling), "commission": .string(commission), "service_charge": .string(service),
                         "other_income": .string(other), "notes": .string(notes),
                         "supplier_id": supplierId == 0 ? .null : .int(supplierId),
                         "tax_profile_id": taxProfileId == 0 ? .null : .int(taxProfileId)] as Fields { f[k] = v }
        if customerId == 0 {
            f.removeValue(forKey: "customer_id")
            f["new_customer"] = ["name": .string(newCustomerName), "phone": .string(newCustomerPhone)]
        } else {
            f.removeValue(forKey: "new_customer")
            f["customer_id"] = .int(customerId)
        }
        f["passengers"] = .array(pax.map { p in ["title": .string(p.title), "name": .string(p.name), "pax_type": .string(p.type),
                                                 "ticket_number": .string(p.ticket), "passport_no": .string(p.passport), "notes": .string(p.notes)] })
        f["segments"] = .array(segs.map { s in
            ["direction": .string(s.direction), "airline": .string(s.airline), "flight_no": .string(s.flightNo), "origin": .string(s.origin),
             "destination": .string(s.destination), "airline_pnr": .string(s.airlinePnr), "baggage": .string(s.baggage),
             "cabin": .string(s.cabin),
             "dep_at": s.hasDep ? .string(Fmt.isoTime.string(from: s.dep)) : J(s.depRaw),
             "arr_at": s.hasArr ? .string(Fmt.isoTime.string(from: s.arr)) : J(s.arrRaw)]
        })
        return f
    }

    func save() {
        guard let a = model.agency else { return }
        attempt($errors, fields, { f in try a.saveBooking(f, id: bookingId) }) { _ in
            model.changed()
            dismiss()
        }
    }
}

// MARK: - refund / cancel

struct RefundFormView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let bookingId: Int
    @State private var customerRefund = ""
    @State private var refundTax = ""
    @State private var supplierRefund = ""
    @State private var commissionBack = ""
    @State private var date = Dates.today()
    @State private var newStatus = "cancelled"
    @State private var payNow = false
    @State private var payAccount = 0
    @State private var payAmount = ""
    @State private var gotNow = false
    @State private var gotAccount = 0
    @State private var gotAmount = ""
    @State private var accounts: [Row] = []
    @State private var errors = ErrorState()
    @State private var idem = UUID().uuidString
    @State private var calc: BookingCalc?

    var body: some View {
        Form {
            if let c = calc {
                Section("Now") {
                    FigureRow(label: "Billed to customer", minor: c.customerPayable - c.customerCredits)
                    FigureRow(label: "Received from customer", minor: c.received - c.refunded)
                    FigureRow(label: "Gross profit", minor: c.grossProfit)
                }
            }
            Section(footer: Text("New profit = profit − refund to customer (ex tax) + refund from supplier − commission given back.")) {
                AmountField(label: "Refund to customer", text: $customerRefund)
                AmountField(label: "of which tax", text: $refundTax)
                AmountField(label: "Refund from supplier / airline", text: $supplierRefund)
                AmountField(label: "Commission given back", text: $commissionBack)
                ISODateField(label: "Date", iso: $date)
                Picker("Booking status", selection: $newStatus) {
                    Text("Cancelled").tag("cancelled"); Text("Refunded").tag("refunded"); Text("Keep status (partial refund)").tag("")
                }
            }
            Section("Money moved now (optional)") {
                Toggle("Paid the customer now", isOn: $payNow)
                if payNow {
                    AccountPicker(accounts: accounts, selection: $payAccount)
                    AmountField(label: "Amount paid", text: $payAmount)
                }
                Toggle("Supplier refund already received", isOn: $gotNow)
                if gotNow {
                    AccountPicker(accounts: accounts, selection: $gotAccount)
                    AmountField(label: "Amount received", text: $gotAmount)
                }
            }
        }
        .keyboardDoneButton()
        .navigationTitle("Refund / cancel")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) { Button("Record") { save() } }
        }
        .errorAlert($errors)
        .task {
            guard let a = model.agency else { return }
            accounts = (try? a.db.query("SELECT id, name, currency FROM accounts WHERE active = 1 AND currency = ? ORDER BY name", [a.baseCurrency])) ?? []
            payAccount = accounts.first?.int("id") ?? 0
            gotAccount = payAccount
            calc = try? Ledger(a).bookings[bookingId]?.calc
        }
    }

    func save() {
        guard let a = model.agency else { return }
        var f: Fields = ["customer_refund": .string(customerRefund), "customer_refund_tax": .string(refundTax), "supplier_refund": .string(supplierRefund),
                         "commission_reversal": .string(commissionBack), "date": .string(date), "new_status": .string(newStatus), "idem_key": .string(idem)]
        if payNow { f["pay_customer"] = ["account_id": .int(payAccount), "amount": .string(payAmount)] }
        if gotNow { f["supplier_received"] = ["account_id": .int(gotAccount), "amount": .string(gotAmount)] }
        attempt($errors, f, { f in try a.refundBooking(bookingId, f) }) { _ in
            model.changed()
            dismiss()
        }
    }
}

struct AccountPicker: View {
    let accounts: [Row]
    @Binding var selection: Int
    var label = "Account"
    var body: some View {
        Picker(label, selection: $selection) {
            ForEach(accounts.indices, id: \.self) { i in
                Text("\(accounts[i].str("name")) (\(accounts[i].str("currency")))").tag(accounts[i].int("id"))
            }
        }
    }
}
