import SwiftUI
import AirZeloKit

// MARK: - customers

struct CustomerListView: View {
    @EnvironmentObject var model: AppModel
    @State private var query = ""
    @State private var rows: [[String: JSONValue]] = []
    @State private var creating = false

    var body: some View {
        List(rows.indices, id: \.self) { i in
            let c = rows[i]
            NavigationLink { CustomerDetailView(customerId: c["id"]!.int) } label: {
                HStack {
                    VStack(alignment: .leading) {
                        Text(c["name"]!.text).font(.subheadline.bold())
                        Text(c["phone"]!.text).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if c["outstanding"]!.int != 0 { MoneyText(minor: c["outstanding"]!.int, colored: true).font(.caption) }
                }
            }
        }
        .searchable(text: $query, prompt: "Name, phone or email")
        .navigationTitle("Customers")
        .toolbar { Button { creating = true } label: { Image(systemName: "person.badge.plus") } }
        .sheet(isPresented: $creating) { NavigationStack { CustomerFormView(customerId: nil) } }
        .task(id: "\(model.revision)|\(query)") { rows = (try? model.agency?.listCustomers(query: query)) ?? [] }
    }
}

struct CustomerDetailView: View {
    @EnvironmentObject var model: AppModel
    let customerId: Int
    @State private var detail: Agency.CustomerDetail?
    @State private var statement: Ledger.Statement?
    @State private var editing = false
    @State private var errors = ErrorState()

    var body: some View {
        List {
            if let d = detail {
                let c = d.customer
                Section {
                    Text(c["name"]!.text).font(.headline)
                    ForEach(["phone", "alt_phone", "email", "city", "gstin", "passport_no"], id: \.self) { k in
                        if !(c[k]?.text ?? "").isEmpty { LabeledContent(Fmt.label(k == "passport_no" ? "passport" : k), value: FieldCrypto.display(c[k]!.text)) }
                    }
                    if !(c["follow_up_date"]?.text ?? "").isEmpty {
                        LabeledContent("Follow up", value: "\(Fmt.display(c["follow_up_date"]!.text)) \(c["follow_up_note"]!.text)")
                    }
                }
                Section("Balance") {
                    FigureRow(label: "Billed", minor: d.balance.billed - d.balance.credits)
                    FigureRow(label: "Received", minor: d.balance.received - d.balance.refunded)
                    if d.balance.unallocated != 0 { FigureRow(label: "Unallocated advance", minor: d.balance.unallocated) }
                    FigureRow(label: d.balance.outstanding >= 0 ? "Outstanding" : "Due back to customer", minor: abs(d.balance.outstanding), strong: true)
                }
                Section("Bookings (\(d.bookings.count))") {
                    ForEach(d.bookings.indices, id: \.self) { i in
                        NavigationLink { BookingDetailView(bookingId: d.bookings[i]["id"]!.int) } label: { BookingRow(r: d.bookings[i]) }
                    }
                }
                if let st = statement {
                    Section("Statement") {
                        ForEach(st.lines.indices, id: \.self) { i in
                            let l = st.lines[i]
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(l["description"]!.text).font(.caption)
                                    Text("\(Fmt.display(l["date"]!.text)) · \(l["ref"]!.text)").font(.caption2).foregroundStyle(.secondary)
                                }
                                Spacer()
                                VStack(alignment: .trailing) {
                                    Text(l["debit"]!.int != 0 ? "+" + fmt(l["debit"]!.int) : "−" + fmt(l["credit"]!.int)).font(.caption.monospacedDigit())
                                    Text(fmt(l["balance"]!.int)).font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                                }
                            }
                        }
                        FigureRow(label: "Closing balance", minor: st.closing, strong: true)
                        if let url = statementCSV(st) { ShareLink(item: url) { Label("Share statement (CSV)", systemImage: "square.and.arrow.up") } }
                    }
                }
                Section {
                    Button(role: .destructive) { remove() } label: { Text(d.bookings.isEmpty && d.transactions.isEmpty ? "Delete customer" : "Deactivate customer") }
                }
            }
        }
        .navigationTitle(detail?.customer["name"]?.text ?? "Customer")
        .toolbar { Button("Edit") { editing = true } }
        .sheet(isPresented: $editing) { NavigationStack { CustomerFormView(customerId: customerId) } }
        .errorAlert($errors)
        .task(id: model.revision) {
            guard let a = model.agency else { return }
            detail = try? a.getCustomer(customerId)
            statement = try? Ledger(a).customerStatement(customerId)
        }
    }

    func statementCSV(_ st: Ledger.Statement) -> URL? {
        var out = "Date,Type,Ref,Description,Debit,Credit,Balance\r\n"
        func esc(_ s: String) -> String { s.contains(",") || s.contains("\"") ? "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : s }
        for l in st.lines {
            out += [l["date"]!.text, l["type"]!.text, l["ref"]!.text, esc(l["description"]!.text), fmt(l["debit"]!.int), fmt(l["credit"]!.int),
                    fmt(l["balance"]!.int)].joined(separator: ",") + "\r\n"
        }
        return shareFile("Statement-\(customerId).csv", Data(out.utf8))
    }

    func remove() {
        guard let a = model.agency else { return }
        attempt($errors, [:], { _ in try a.deleteCustomer(customerId) }) { _ in model.changed() }
    }
}

struct CustomerFormView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let customerId: Int?
    @State private var f: [String: String] = [:]
    @State private var errors = ErrorState()
    let keys = ["name", "phone", "alt_phone", "email", "address", "city", "state", "gstin", "passport_no", "notes", "follow_up_note"]

    var body: some View {
        Form {
            Section("Customer") {
                TextField("Name", text: bind("name")).accessibilityIdentifier("customer-name")
                TextField("Phone", text: bind("phone")).phoneKeyboard()
                TextField("Alternate phone", text: bind("alt_phone")).phoneKeyboard()
                TextField("Email", text: bind("email"))
            }
            Section("Address") {
                TextField("Address", text: bind("address"), axis: .vertical)
                TextField("City", text: bind("city"))
                TextField("State", text: bind("state"))
            }
            Section(footer: Text("Passport numbers are encrypted on this iPhone.")) {
                TextField("GSTIN", text: bind("gstin")).capitals()
                if FieldCrypto.isSealed(f["passport_no"]) {
                    Text("Passport: encrypted on another device (kept as is)").foregroundStyle(.secondary)
                } else {
                    TextField("Passport number", text: bind("passport_no")).capitals()
                }
            }
            Section("Follow-up") {
                OptionalDateField(label: "Follow-up date", iso: bind("follow_up_date"))
                TextField("Note", text: bind("follow_up_note"))
            }
            Section("Notes") { TextField("Notes", text: bind("notes"), axis: .vertical) }
        }
        .navigationTitle(customerId == nil ? "New customer" : "Edit customer")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) { Button("Save") { save() }.accessibilityIdentifier("save-customer") }
        }
        .errorAlert($errors)
        .task {
            guard let id = customerId, let c = try? model.agency?.getCustomer(id).customer else { return }
            for k in keys + ["follow_up_date"] { f[k] = c[k]?.text ?? "" }
        }
    }

    func bind(_ k: String) -> Binding<String> { Binding(get: { f[k] ?? "" }, set: { f[k] = $0 }) }

    func save() {
        guard let a = model.agency else { return }
        var data: Fields = [:]
        for (k, v) in f { data[k] = .string(v) }
        attempt($errors, data, { d in customerId == nil ? try a.createCustomer(d) : try a.updateCustomer(customerId!, d) }) { _ in
            model.changed()
            dismiss()
        }
    }
}

// MARK: - suppliers

struct SupplierListView: View {
    @EnvironmentObject var model: AppModel
    @State private var rows: [[String: JSONValue]] = []
    @State private var editing: Int?
    @State private var creating = false

    var body: some View {
        List(rows.indices, id: \.self) { i in
            let s = rows[i]
            Button { editing = s["id"]!.int } label: {
                HStack {
                    VStack(alignment: .leading) {
                        Text(s["name"]!.text).font(.subheadline.bold()).foregroundStyle(.primary)
                        Text("\(Fmt.label(s["kind"]!.text)) · \(s["bookings"]!.int) bookings").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if s["outstanding"]!.int != 0 { MoneyText(minor: s["outstanding"]!.int, colored: true).font(.caption) }
                }
            }
        }
        .navigationTitle("Suppliers")
        .toolbar { Button { creating = true } label: { Image(systemName: "plus") } }
        .sheet(isPresented: $creating) { NavigationStack { SupplierFormView(supplierId: nil) } }
        .sheet(item: Binding(get: { editing.map { IdBox(id: $0) } }, set: { editing = $0?.id })) { b in NavigationStack { SupplierFormView(supplierId: b.id) } }
        .task(id: model.revision) { rows = (try? model.agency?.listSuppliers()) ?? [] }
    }
}

struct IdBox: Identifiable { let id: Int }

struct SupplierFormView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let supplierId: Int?
    @State private var f: [String: String] = ["kind": "b2b_portal"]
    @State private var errors = ErrorState()

    var body: some View {
        Form {
            TextField("Name", text: bind("name"))
            Picker("Type", selection: bind("kind")) { ForEach(SUPPLIER_KINDS, id: \.self) { Text(Fmt.label($0)).tag($0) } }
            TextField("Contact person", text: bind("contact_person"))
            TextField("Phone", text: bind("phone")).phoneKeyboard()
            TextField("Email", text: bind("email"))
            TextField("GSTIN", text: bind("gstin")).capitals()
            TextField("Notes", text: bind("notes"), axis: .vertical)
        }
        .navigationTitle(supplierId == nil ? "New supplier" : "Edit supplier")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) { Button("Save") { save() } }
        }
        .errorAlert($errors)
        .task {
            guard let id = supplierId, let s = try? model.agency?.db.one("SELECT * FROM suppliers WHERE id = ?", [id]) else { return }
            for k in ["name", "kind", "contact_person", "phone", "email", "gstin", "notes"] { f[k] = s.str(k) }
        }
    }

    func bind(_ k: String) -> Binding<String> { Binding(get: { f[k] ?? "" }, set: { f[k] = $0 }) }

    func save() {
        guard let a = model.agency else { return }
        var data: Fields = [:]
        for (k, v) in f { data[k] = .string(v) }
        attempt($errors, data, { d in supplierId == nil ? try a.createSupplier(d) : try a.updateSupplier(supplierId!, d) }) { _ in
            model.changed()
            dismiss()
        }
    }
}
