import SwiftUI
import PDFKit
import AirZeloKit

// MARK: - platform shims (the app is iOS; these let the views also type-check on macOS)

extension View {
    @ViewBuilder func decimalKeyboard() -> some View {
        #if os(iOS)
        self.keyboardType(.decimalPad)
        #else
        self
        #endif
    }
    @ViewBuilder func phoneKeyboard() -> some View {
        #if os(iOS)
        self.keyboardType(.phonePad)
        #else
        self
        #endif
    }
    @ViewBuilder func capitals() -> some View {
        #if os(iOS)
        self.textInputAutocapitalization(.characters).autocorrectionDisabled()
        #else
        self
        #endif
    }
    @ViewBuilder func inlineTitle() -> some View {
        #if os(iOS)
        self.navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }
}

// MARK: - formatting

enum Fmt {
    static func money(_ minor: Int, _ cur: String = "INR") -> String { AirZeloKit.money(minor, cur) }
    static func amountText(_ minor: Int) -> String { minor == 0 ? "" : fmt(minor) }
    static func label(_ key: String) -> String { key.replacingOccurrences(of: "_", with: " ").capitalized }
    static let iso: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd"
        return df
    }()
    static let isoTime: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return df
    }()
    static func date(_ s: String?) -> Date { s.flatMap { iso.date(from: String($0.prefix(10))) } ?? Date() }
    static func display(_ s: String?) -> String {
        guard let s, let d = iso.date(from: String(s.prefix(10))) else { return "" }
        return d.formatted(date: .abbreviated, time: .omitted)
    }
}

extension JSONValue {
    var text: String { isNull ? "" : pyStr }
    var int: Int { intValue ?? 0 }
}

// MARK: - small views

struct MoneyText: View {
    let minor: Int
    var currency = "INR"
    var colored = false
    var body: some View {
        Text(Fmt.money(minor, currency)).monospacedDigit()
            .foregroundStyle(colored ? (minor > 0 ? Color.red : minor < 0 ? Color.purple : Color.green) : Color.primary)
    }
}

struct StatusBadge: View {
    let status: String
    var color: Color {
        switch status {
        case "ticketed", "paid", "settled", "issued": return .green
        case "confirmed", "advance": return .blue
        case "enquiry", "on_hold", "partial": return .orange
        case "cancelled", "refunded", "unpaid", "refund_due": return .red
        case "overpaid": return .purple
        default: return .gray
        }
    }
    var body: some View {
        Text(Fmt.label(status)).font(.caption2.bold()).padding(.horizontal, 7).padding(.vertical, 3)
            .background(color.opacity(0.15), in: Capsule()).foregroundStyle(color)
    }
}

struct FigureRow: View {
    let label: String
    let minor: Int
    var currency = "INR"
    var strong = false
    var body: some View {
        HStack {
            Text(label).foregroundStyle(strong ? .primary : .secondary)
            Spacer()
            MoneyText(minor: minor, currency: currency).fontWeight(strong ? .semibold : .regular)
        }
    }
}

struct AmountField: View {
    let label: String
    @Binding var text: String
    var id: String?
    var body: some View {
        HStack {
            Text(label)
            Spacer()
            TextField("0.00", text: $text).multilineTextAlignment(.trailing).decimalKeyboard().frame(maxWidth: 160)
                .accessibilityIdentifier(id ?? label)
        }
    }
}

/// Optional date stored as ISO text.
struct OptionalDateField: View {
    let label: String
    @Binding var iso: String
    var body: some View {
        Toggle(label, isOn: Binding(get: { !iso.isEmpty }, set: { iso = $0 ? Fmt.iso.string(from: Date()) : "" }))
        if !iso.isEmpty {
            DatePicker(label, selection: Binding(get: { Fmt.date(iso) }, set: { iso = Fmt.iso.string(from: $0) }), displayedComponents: .date)
        }
    }
}

struct ISODateField: View {
    let label: String
    @Binding var iso: String
    var body: some View {
        DatePicker(label, selection: Binding(get: { Fmt.date(iso) }, set: { iso = Fmt.iso.string(from: $0) }), displayedComponents: .date)
    }
}

// MARK: - errors and confirmations

/// Shows service refusals with the desktop's wording, and offers "Save anyway" for the warnings the desktop
/// also lets the user override (duplicate PNR, possible duplicate payment, duplicate customer phone ...).
struct ErrorState {
    var error: AZError?
    var retry: (() -> Void)?
    static let overridable: [String: String] = ["duplicate_pnr": "force_duplicate", "possible_duplicate": "force_duplicate",
                                                "duplicate_customer": "force_duplicate", "confirm_opening_change": "confirm_opening_change"]
}

extension View {
    func errorAlert(_ state: Binding<ErrorState>) -> some View {
        alert(state.wrappedValue.error?.kind == .conflict ? "Please check" : "Not saved",
              isPresented: Binding(get: { state.wrappedValue.error != nil }, set: { if !$0 { state.wrappedValue = ErrorState() } }),
              presenting: state.wrappedValue.error) { e in
            if let retry = state.wrappedValue.retry, ErrorState.overridable[e.code] != nil {
                Button("Save anyway") { retry() }
                Button("Cancel", role: .cancel) {}
            } else {
                Button("OK", role: .cancel) {}
            }
        } message: { e in Text(e.message) }
    }
}

/// Runs a service call; on a refusal fills `errors` (with a retry that sets the override flag) and returns nil.
@MainActor
func attempt<T>(_ errors: Binding<ErrorState>, _ fields: Fields = [:], _ body: @escaping (Fields) throws -> T, onSuccess: @escaping (T) -> Void) {
    do {
        onSuccess(try body(fields))
    } catch let e as AZError {
        var retry: (() -> Void)?
        if let flag = ErrorState.overridable[e.code] {
            retry = {
                var f = fields
                f[flag] = true
                attempt(errors, f, body, onSuccess: onSuccess)
            }
        }
        errors.wrappedValue = ErrorState(error: e, retry: retry)
    } catch {
        errors.wrappedValue = ErrorState(error: .validation("\(error)"))
    }
}

// MARK: - PDF preview and sharing

struct PDFPreview: View {
    let data: Data
    let fileName: String
    @State private var url: URL?

    var body: some View {
        PDFKitView(data: data)
            .navigationTitle(fileName)
            .inlineTitle()
            .toolbar {
                if let url { ShareLink(item: url) { Label("Share", systemImage: "square.and.arrow.up") } }
            }
            .task {
                let u = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
                try? data.write(to: u, options: [.atomic, .completeFileProtection])
                url = u
            }
    }
}

#if os(iOS)
struct PDFKitView: UIViewRepresentable {
    let data: Data
    func makeUIView(context: Context) -> PDFView {
        let v = PDFView()
        v.autoScales = true
        v.document = PDFDocument(data: data)
        return v
    }
    func updateUIView(_ v: PDFView, context: Context) {}
}
#else
struct PDFKitView: NSViewRepresentable {
    let data: Data
    func makeNSView(context: Context) -> PDFView {
        let v = PDFView()
        v.autoScales = true
        v.document = PDFDocument(data: data)
        return v
    }
    func updateNSView(_ v: PDFView, context: Context) {}
}
#endif

/// A temporary file for ShareLink (CSV exports).
func shareFile(_ name: String, _ data: Data) -> URL {
    let u = FileManager.default.temporaryDirectory.appendingPathComponent(name)
    try? data.write(to: u, options: [.atomic, .completeFileProtection])
    return u
}
