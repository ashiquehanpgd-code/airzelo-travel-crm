import SwiftUI
import AirZeloKit

// MARK: - transactions

struct TransactionListView: View {
    @EnvironmentObject var model: AppModel
    @State private var rows: [[String: JSONValue]] = []
    @State private var query = ""
    @State private var kind = ""
    @State private var creating = false

    var body: some View {
        List {
            Picker("Type", selection: $kind) {
                Text("All").tag("")
                ForEach(KIND_ORDER.filter { !$0.hasPrefix("loan") }, id: \.self) { Text(KIND_LABELS[$0]!).tag($0) }
            }
            ForEach(rows.prefix(500).indices, id: \.self) { i in
                NavigationLink { TransactionDetailView(txnId: rows[i]["id"]!.int) } label: { TransactionRow(r: rows[i]) }
            }
        }
        .searchable(text: $query, prompt: "Number, reference, party, notes")
        .navigationTitle("Money")
        .toolbar { Button { creating = true } label: { Image(systemName: "plus") } }
        .sheet(isPresented: $creating) { NavigationStack { TransactionFormView(preset: .init(kind: "expense")) } }
        .task(id: "\(model.revision)|\(query)|\(kind)") { rows = (try? model.agency?.listTransactions(["q": query, "kind": kind])) ?? [] }
    }
}

struct TransactionRow: View {
    let r: [String: JSONValue]
    var body: some View {
        let inflow = ["customer_receipt", "supplier_receipt", "other_income", "loan_received"].contains(r["kind"]!.text)
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(r["kind_label"]!.text + (r["sign"]!.int < 0 ? " (reversal)" : "")).font(.subheadline.bold())
                Text([r["txn_no"]!.text, Fmt.display(r["txn_date"]!.text),
                      [r["customer_name"]!.text, r["supplier_name"]!.text, r["payee"]!.text, r["category"]!.text].first { !$0.isEmpty } ?? ""]
                        .filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Text((inflow == (r["sign"]!.int > 0) ? "+" : "−") + Fmt.money(r["amount"]!.int, r["currency"]!.text))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(r["reversed_by"]!.isNull ? (inflow ? Color.green : Color.primary) : Color.secondary)
                .strikethrough(!r["reversed_by"]!.isNull)
        }
    }
}

struct TransactionDetailView: View {
    @EnvironmentObject var model: AppModel
    let txnId: Int
    @State private var row: [String: JSONValue]?
    @State private var reversing = false
    @State private var reason = ""
    @State private var errors = ErrorState()

    var body: some View {
        List {
            if let r = row {
                Section {
                    LabeledContent("Type", value: r["kind_label"]!.text)
                    LabeledContent("Number", value: r["txn_no"]!.text)
                    LabeledContent("Date", value: Fmt.display(r["txn_date"]!.text))
                    LabeledContent("Amount", value: Fmt.money(r["amount"]!.int, r["currency"]!.text))
                    if r["currency"]!.text != (model.agency?.baseCurrency ?? "INR") {
                        LabeledContent("In \(model.agency?.baseCurrency ?? "INR")", value: Fmt.money(r["base_amount"]!.int))
                    }
                    LabeledContent("Account", value: r["account_name"]!.text + (r["to_account_name"]!.text.isEmpty ? "" : " → " + r["to_account_name"]!.text))
                    LabeledContent("Method", value: Fmt.label(r["method"]!.text))
                    ForEach(["customer_name", "supplier_name", "booking_ref", "payee", "category", "reference", "notes"], id: \.self) { k in
                        if !r[k]!.text.isEmpty { LabeledContent(Fmt.label(k.replacingOccurrences(of: "_name", with: "")), value: r[k]!.text) }
                    }
                }
                if let allocs = r["allocations"]?.arrayValue, !allocs.isEmpty {
                    Section("Allocated to") {
                        ForEach(allocs.indices, id: \.self) { i in FigureRow(label: allocs[i]["ref"].text, minor: allocs[i]["amount"].int) }
                        if r["unallocated"]!.int != 0 { FigureRow(label: "Unallocated", minor: r["unallocated"]!.int) }
                    }
                }
                Section(footer: Text("Posted entries are never edited or deleted. Reversing posts an opposite entry, so the audit trail stays complete.")) {
                    if !r["reversed_by"]!.isNull {
                        Text("Reversed").foregroundStyle(.secondary)
                    } else if r["sign"]!.int > 0 {
                        Button("Reverse this entry", role: .destructive) { reversing = true }
                    }
                }
            }
        }
        .navigationTitle(row?["txn_no"]?.text ?? "Entry")
        .alert("Reverse entry", isPresented: $reversing) {
            TextField("Reason", text: $reason)
            Button("Reverse", role: .destructive) { reverse() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("An opposite entry will be posted today.") }
        .errorAlert($errors)
        .task(id: model.revision) {
            guard let a = model.agency, let L = try? Ledger(a), let t = L.txnById[txnId] else { return }
            row = L.txnRow(t)
        }
    }

    func reverse() {
        guard let a = model.agency else { return }
        attempt($errors, ["reason": .string(reason)], { f in try a.reverseTransaction(txnId, f) }) { _ in model.changed() }
    }
}

struct TransactionFormView: View {
    struct Preset {
        var kind: String
        var bookingId: Int?
        var customerId: Int?
        var supplierId: Int?
        var amount: Int = 0
    }

    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let preset: Preset
    @State private var kind = "expense"
    @State private var date = Dates.today()
    @State private var accountId = 0
    @State private var toAccountId = 0
    @State private var amount = ""
    @State private var toAmount = ""
    @State private var rate = ""
    @State private var method = "cash"
    @State private var reference = ""
    @State private var category = "Office expenses"
    @State private var payee = ""
    @State private var notes = ""
    @State private var customerId = 0
    @State private var supplierId = 0
    @State private var accounts: [Row] = []
    @State private var customers: [Row] = []
    @State private var suppliers: [Row] = []
    @State private var categories: [String] = []
    @State private var errors = ErrorState()
    @State private var idem = UUID().uuidString
    @State private var loaded = false

    var account: Row? { accounts.first { $0.int("id") == accountId } }
    var toAccount: Row? { accounts.first { $0.int("id") == toAccountId } }
    var base: String { model.agency?.baseCurrency ?? "INR" }

    var body: some View {
        Form {
            if preset.bookingId == nil {
                Picker("Type", selection: $kind) {
                    ForEach(["customer_receipt", "supplier_payment", "customer_refund", "supplier_receipt", "expense", "other_income", "transfer"], id: \.self) {
                        Text(KIND_LABELS[$0]!).tag($0)
                    }
                }
            } else {
                LabeledContent("Type", value: KIND_LABELS[kind] ?? kind)
            }
            Section {
                ISODateField(label: "Date", iso: $date)
                AccountPicker(accounts: accounts, selection: $accountId, label: kind == "transfer" ? "From" : "Account")
                AmountField(label: "Amount" + (account.map { " (\($0.str("currency")))" } ?? ""), text: $amount, id: "txn-amount")
                if let acc = account, acc.str("currency") != base { AmountField(label: "Rate (1 \(acc.str("currency")) in \(base))", text: $rate) }
                if kind == "transfer" {
                    AccountPicker(accounts: accounts.filter { $0.int("id") != accountId }, selection: $toAccountId, label: "To")
                    if let a = account, let b = toAccount, a.str("currency") != b.str("currency") {
                        AmountField(label: "Received (\(b.str("currency")))", text: $toAmount)
                    }
                } else {
                    Picker("Method", selection: $method) { ForEach(METHODS, id: \.self) { Text(Fmt.label($0)).tag($0) } }
                }
            }
            if preset.bookingId == nil {
                if ["customer_receipt", "customer_refund"].contains(kind) {
                    Picker("Customer", selection: $customerId) {
                        Text("Select").tag(0)
                        ForEach(customers.indices, id: \.self) { i in Text(customers[i].str("name")).tag(customers[i].int("id")) }
                    }
                }
                if ["supplier_payment", "supplier_receipt"].contains(kind) || kind == "expense" {
                    Picker(kind == "expense" ? "Supplier (optional)" : "Supplier", selection: $supplierId) {
                        Text(kind == "expense" ? "None" : "Select").tag(0)
                        ForEach(suppliers.indices, id: \.self) { i in Text(suppliers[i].str("name")).tag(suppliers[i].int("id")) }
                    }
                }
            }
            if ["expense", "other_income"].contains(kind) {
                Section {
                    Picker("Category", selection: $category) {
                        ForEach(categories, id: \.self) { Text($0).tag($0) }
                    }
                    TextField("Payee / from", text: $payee)
                }
            }
            Section {
                TextField("Reference (UTR, cheque no.)", text: $reference)
                TextField("Notes", text: $notes, axis: .vertical)
            }
            if ["customer_refund", "supplier_receipt"].contains(kind) && preset.bookingId == nil {
                Text("To refund a booking, open it and use Refund / cancel: that records the credit and adjusts the profit first.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(KIND_LABELS[kind] ?? "Entry")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) { Button("Save") { save() }.accessibilityIdentifier("save-transaction") }
        }
        .errorAlert($errors)
        .task {
            guard !loaded, let a = model.agency else { return }
            loaded = true
            kind = preset.kind
            accounts = (try? a.db.query("SELECT id, name, currency FROM accounts WHERE active = 1 ORDER BY kind, name")) ?? []
            accountId = accounts.first { $0.str("currency") == base }?.int("id") ?? accounts.first?.int("id") ?? 0
            toAccountId = accounts.first { $0.int("id") != accountId }?.int("id") ?? 0
            customers = (try? a.db.query("SELECT id, name FROM customers WHERE active = 1 ORDER BY name COLLATE NOCASE")) ?? []
            suppliers = (try? a.db.query("SELECT id, name FROM suppliers WHERE active = 1 ORDER BY name COLLATE NOCASE")) ?? []
            categories = ((try? a.listExpenseCategories()) ?? []).filter { $0.bool("active") }.map { $0.str("name") }
            if !categories.contains("Office expenses") { categories.insert("Office expenses", at: 0) }
            if !categories.contains("Other income") { categories.append("Other income") }
            customerId = preset.customerId ?? 0
            supplierId = preset.supplierId ?? 0
            if preset.amount > 0 { amount = fmt(preset.amount) }
            if kind == "other_income" { category = "Other income" }
        }
        .onChange(of: kind) { _, k in if k == "other_income" { category = "Other income" } else if k == "expense" && category == "Other income" { category = categories.first ?? "Office expenses" } }
    }

    func save() {
        guard let a = model.agency else { return }
        var f: Fields = ["kind": .string(kind), "txn_date": .string(date), "account_id": .int(accountId), "amount": .string(amount),
                         "exchange_rate": .string(rate), "method": .string(method), "reference": .string(reference), "notes": .string(notes),
                         "idem_key": .string(idem)]
        if let b = preset.bookingId { f["booking_id"] = .int(b) }
        if customerId != 0 { f["customer_id"] = .int(customerId) }
        if supplierId != 0 { f["supplier_id"] = .int(supplierId) }
        if ["expense", "other_income"].contains(kind) { f["category"] = .string(category); f["payee"] = .string(payee) }
        if kind == "transfer" { f["to_account_id"] = .int(toAccountId); f["to_amount"] = .string(toAmount) }
        attempt($errors, f, { f in try a.createTransaction(f) }) { _ in
            model.changed()
            dismiss()
        }
    }
}

// MARK: - accounts

struct AccountListView: View {
    @EnvironmentObject var model: AppModel
    @State private var rows: [[String: JSONValue]] = []
    @State private var creating = false

    var body: some View {
        List(rows.indices, id: \.self) { i in
            let a = rows[i]
            NavigationLink { CashbookView(accountId: a["id"]!.int, name: a["name"]!.text) } label: {
                HStack {
                    VStack(alignment: .leading) {
                        Text(a["name"]!.text).font(.subheadline.bold())
                        Text("\(Fmt.label(a["account_type"]!.text)) · \(a["txn_count"]!.int) entries\(a["active"]!.int == 0 ? " · inactive" : "")")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    MoneyText(minor: a["balance"]!.int, currency: a["currency"]!.text)
                }
            }
        }
        .navigationTitle("Accounts")
        .toolbar { Button { creating = true } label: { Image(systemName: "plus") } }
        .sheet(isPresented: $creating) { NavigationStack { AccountFormView() } }
        .task(id: model.revision) { rows = (try? model.agency?.listAccounts()) ?? [] }
    }
}

struct AccountFormView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var type = "bank"
    @State private var currency = "INR"
    @State private var opening = ""
    @State private var bank = ""
    @State private var last4 = ""
    @State private var errors = ErrorState()

    var body: some View {
        Form {
            TextField("Name (e.g. HDFC Current)", text: $name)
            Picker("Type", selection: $type) {
                ForEach(["cash", "bank", "current", "savings", "upi", "credit_card", "wallet", "gateway"], id: \.self) { Text(Fmt.label($0)).tag($0) }
            }
            TextField("Currency", text: $currency).capitals()
            AmountField(label: "Opening balance", text: $opening)
            TextField("Bank name", text: $bank)
            TextField("Last 4 digits only", text: $last4).decimalKeyboard()
        }
        .navigationTitle("New account")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) { Button("Save") { save() } }
        }
        .errorAlert($errors)
    }

    func save() {
        guard let a = model.agency else { return }
        let f: Fields = ["name": .string(name), "account_type": .string(type), "currency": .string(currency), "opening_balance": .string(opening),
                         "bank_name": .string(bank), "account_last4": .string(last4)]
        attempt($errors, f, { f in try a.saveAccount(f) }) { _ in
            model.changed()
            dismiss()
        }
    }
}
