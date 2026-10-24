import Foundation

// Port of core/server/validate.py: the same rules and messages as the desktop app.
enum V {
    /// Python str.capitalize(): first character upper case, the rest lower case.
    static func capitalize(_ s: String) -> String {
        guard let f = s.first else { return s }
        return String(f).uppercased() + s.dropFirst().lowercased()
    }

    static func label(_ key: String, _ label: String?) -> String {
        label ?? capitalize(key.replacingOccurrences(of: "_", with: " "))
    }

    static func s(_ data: Fields, _ key: String, _ maxLen: Int = 500, required: Bool = false, label lbl: String? = nil) throws -> String? {
        let lab = label(key, lbl)
        let raw = data.get(key)
        let text: String
        switch raw {
        case .null: text = ""
        case .string(let x): text = x
        case .int, .double: text = raw.pyStr
        default: throw AZError.validation("\(lab) is invalid", key)
        }
        let v = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if required && v.isEmpty { throw AZError.validation("\(lab) is required", key) }
        if v.unicodeScalars.count > maxLen { throw AZError.validation("\(lab) is too long (max \(maxLen) characters)", key) }
        return v.isEmpty ? nil : v
    }

    static func d(_ data: Fields, _ key: String, required: Bool = false, label lbl: String? = nil) throws -> String? {
        guard let v = try s(data, key, 10, required: required, label: lbl) else { return nil }
        let lab = label(key, lbl)
        guard v.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil else {
            throw AZError.validation("\(lab) must be a date (YYYY-MM-DD)", key)
        }
        guard Dates.isValidDate(v) else { throw AZError.validation("\(lab) is not a valid date", key) }
        return v
    }

    static func dt(_ data: Fields, _ key: String, label lbl: String? = nil) throws -> String? {
        guard var v = try s(data, key, 19, label: lbl) else { return nil }
        let lab = label(key, lbl)
        v = v.replacingOccurrences(of: " ", with: "T")
        // strptime formats "%Y-%m-%dT%H:%M", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d" (one or two digit fields, like strptime)
        let pattern = #"^(\d{4})-(\d{1,2})-(\d{1,2})(T(\d{1,2}):(\d{1,2})(:(\d{1,2}))?)?$"#
        if let re = try? NSRegularExpression(pattern: pattern),
           let m = re.firstMatch(in: v, range: NSRange(v.startIndex..., in: v)) {
            func g(_ i: Int) -> Int? {
                guard let r = Range(m.range(at: i), in: v) else { return nil }
                return Int(v[r])
            }
            let y = g(1)!, mo = g(2)!, da = g(3)!
            var ok = Dates.isValid(y, mo, da)
            if let h = g(5), let mi = g(6) { ok = ok && h < 24 && mi < 60 }
            if let sec = g(8) { ok = ok && sec < 62 }
            if ok { return v.contains("T") ? String(v.prefix(16)) : v }
        }
        throw AZError.validation("\(lab) must be a date/time (YYYY-MM-DDTHH:MM)", key)
    }

    static func choice(_ data: Fields, _ key: String, _ options: [String], _ dflt: String? = nil, _ lbl: String? = nil) throws -> String {
        let v = data.get(key)
        if v.isNull || v == .string("") {
            if let dflt { return dflt }
            throw AZError.validation("\(lbl ?? key) is required", key)
        }
        guard case .string(let s) = v, options.contains(s) else {
            throw AZError.validation("\(lbl ?? key) must be one of: \(options.joined(separator: ", "))", key)
        }
        return s
    }

    static func i(_ data: Fields, _ key: String, required: Bool = false, label lbl: String? = nil) throws -> Int? {
        let v = data.get(key)
        let lab = label(key, lbl)
        if v.isNull || v == .string("") {
            if required { throw AZError.validation("\(lab) is required", key) }
            return nil
        }
        switch v {
        case .int(let n): return n
        case .double(let d) where d.isFinite: return Int(d)  // int(12.7) == 12
        case .string(let s):
            if let n = Int(s.trimmingCharacters(in: .whitespacesAndNewlines)) { return n }
            throw AZError.validation("\(lab) is invalid", key)
        default: throw AZError.validation("\(lab) is invalid", key)
        }
    }

    static func b(_ data: Fields, _ key: String) -> Bool {
        switch data.get(key) {
        case .bool(let x): return x
        case .int(let n): return n == 1
        case .double(let d): return d == 1
        case .string(let s): return ["1", "true", "on", "yes"].contains(s)
        default: return false
        }
    }

    /// `data.get(key) in (False, 0, "0")` - the desktop's "explicitly switched off" test.
    static func isOff(_ v: JSONValue) -> Bool {
        switch v {
        case .bool(let x): return !x
        case .int(let n): return n == 0
        case .double(let d): return d == 0
        case .string(let s): return s == "0"
        default: return false
        }
    }

    static func email(_ data: Fields, _ key: String) throws -> String? {
        let v = try s(data, key, 200)
        if let v, v.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) == nil {
            throw AZError.validation("Email address looks invalid", key)
        }
        return v
    }

    static func phone(_ data: Fields, _ key: String) throws -> String? {
        let v = try s(data, key, 40)
        if let v, v.range(of: #"^[0-9+()\-\s.]{5,40}$"#, options: .regularExpression) == nil {
            throw AZError.validation("Phone number looks invalid", key)
        }
        return v
    }

    static func normCode(_ v: JSONValue, _ maxLen: Int = 12) -> String? {
        if v.isNull { return nil }
        let t = v.pyStr.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let cut = String(String.UnicodeScalarView(t.unicodeScalars.prefix(maxLen)))
        return cut.isEmpty ? nil : cut
    }

    static func normCode(_ v: String?, _ maxLen: Int = 12) -> String? { normCode(J(v), maxLen) }
}

/// Calendar helpers. Dates are ISO text (YYYY-MM-DD), exactly as the desktop stores them.
public enum Dates {
    static func isValid(_ y: Int, _ m: Int, _ d: Int) -> Bool {
        guard y >= 1, m >= 1, m <= 12, d >= 1 else { return false }
        return d <= daysInMonth(y, m)
    }

    static func isValidDate(_ s: String) -> Bool {
        let p = s.split(separator: "-").compactMap { Int($0) }
        return p.count == 3 && isValid(p[0], p[1], p[2])
    }

    public static func daysInMonth(_ y: Int, _ m: Int) -> Int {
        switch m {
        case 2: return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// Clock used by the services. Tests pin it so results do not depend on the day they run.
    nonisolated(unsafe) public static var now: () -> Date = { Date() }

    private static func formatter(_ f: String) -> DateFormatter {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.calendar = Calendar(identifier: .gregorian)
        df.timeZone = .current
        df.dateFormat = f
        return df
    }

    /// date.today().isoformat()
    public static func today() -> String { formatter("yyyy-MM-dd").string(from: now()) }
    /// datetime.now().replace(microsecond=0).isoformat()
    public static func nowISO() -> String { formatter("yyyy-MM-dd'T'HH:mm:ss").string(from: now()) }

    public static func parse(_ s: String) -> Date? { formatter("yyyy-MM-dd").date(from: String(s.prefix(10))) }
    public static func iso(_ d: Date) -> String { formatter("yyyy-MM-dd").string(from: d) }
    public static func addDays(_ s: String, _ n: Int) -> String {
        guard let d = parse(s) else { return s }
        return iso(Calendar(identifier: .gregorian).date(byAdding: .day, value: n, to: d)!)
    }
    public static func daysBetween(_ a: String, _ b: String) -> Int? {
        guard let x = parse(a), let y = parse(b) else { return nil }
        return Calendar(identifier: .gregorian).dateComponents([.day], from: x, to: y).day
    }
    static let monthAbbr = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
}
