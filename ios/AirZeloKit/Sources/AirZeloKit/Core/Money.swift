import Foundation

// Port of core/server/money.py.
//
// All amounts are INTEGER minor units (paise for INR, halalas for SAR). Conversion and percentage
// maths round HALF-UP (ties away from zero) to the nearest minor unit - the single rounding policy
// of the whole application. Parsing follows Python's Decimal exactly (including how many decimal
// places were typed), so the iPhone accepts and refuses the same inputs as the desktop app.

public let MAX_MINOR = 10_000_000_000_000  // 100 billion in major units

/// A decimal number as Python's Decimal sees it: sign, coefficient digits and exponent (as typed).
struct PyDecimal: Equatable {
    var negative: Bool
    var coeff: String   // digits, no leading zeros ("0" for zero)
    var exp: Int

    var isZero: Bool { coeff == "0" }

    init(negative: Bool, coeff: String, exp: Int) {
        let trimmed = String(coeff.drop(while: { $0 == "0" }))
        self.negative = negative
        self.coeff = trimmed.isEmpty ? "0" : trimmed
        self.exp = exp
    }

    init(int v: Int) {
        self.init(negative: v < 0, coeff: String(v.magnitude), exp: 0)
    }

    /// Strict parse of Python Decimal literal syntax. Returns nil for invalid text; `.nonFinite` for NaN / Infinity.
    enum ParseResult { case ok(PyDecimal), invalid, nonFinite }

    static func parse(_ raw: String) -> ParseResult {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "_", with: "")
        let lower = s.lowercased()
        let body = lower.hasPrefix("+") || lower.hasPrefix("-") ? String(lower.dropFirst()) : lower
        if ["inf", "infinity", "nan", "snan"].contains(body) || body.hasPrefix("nan") || body.hasPrefix("snan") { return .nonFinite }
        var chars = Array(s)
        var neg = false
        if let f = chars.first, f == "+" || f == "-" { neg = f == "-"; chars.removeFirst() }
        var intDigits = "", fracDigits = "", expText = ""
        var i = 0
        while i < chars.count, chars[i].isASCII, chars[i].isNumber { intDigits.append(chars[i]); i += 1 }
        if i < chars.count, chars[i] == "." {
            i += 1
            while i < chars.count, chars[i].isASCII, chars[i].isNumber { fracDigits.append(chars[i]); i += 1 }
        }
        if intDigits.isEmpty && fracDigits.isEmpty { return .invalid }
        if i < chars.count, chars[i] == "e" || chars[i] == "E" {
            i += 1
            if i < chars.count, chars[i] == "+" || chars[i] == "-" { expText.append(chars[i]); i += 1 }
            var digits = ""
            while i < chars.count, chars[i].isASCII, chars[i].isNumber { digits.append(chars[i]); i += 1 }
            if digits.isEmpty { return .invalid }
            expText += digits
        }
        if i != chars.count { return .invalid }
        let e = expText.isEmpty ? 0 : (Int(expText) ?? 0)
        return .ok(PyDecimal(negative: neg, coeff: intDigits + fracDigits, exp: e - fracDigits.count))
    }

    var decimal: Decimal {
        var d = Decimal(string: coeff, locale: Locale(identifier: "en_US_POSIX")) ?? 0
        if exp != 0 { d = Decimal(sign: .plus, exponent: exp, significand: d) }
        return negative ? -d : d
    }

    /// Python format(d.normalize(), "f").
    var normalizedString: String {
        if isZero { return negative ? "-0" : "0" }
        var c = coeff, e = exp
        while c.count > 1, c.hasSuffix("0") { c.removeLast(); e += 1 }
        var out: String
        if e >= 0 {
            out = c + String(repeating: "0", count: e)
        } else {
            let places = -e
            if c.count > places {
                out = String(c.prefix(c.count - places)) + "." + String(c.suffix(places))
            } else {
                out = "0." + String(repeating: "0", count: places - c.count) + c
            }
        }
        return (negative ? "-" : "") + out
    }

    /// d * 100 as an integer when it is whole (at most 2 significant decimals), else nil.
    var hundredthsIfWhole: Int? {
        if isZero { return 0 }
        var c = coeff, e = exp + 2
        while e < 0 {
            guard c.hasSuffix("0") else { return nil }
            c.removeLast()
            e += 1
        }
        if c.count + e > 18 { return negative ? Int.min + 1 : Int.max }  // beyond any valid amount; caller reports "too large"
        var v = Int(c) ?? 0
        for _ in 0..<e { v *= 10 }
        return negative ? -v : v
    }

    /// Python d.quantize(Decimal("0.01"), ROUND_HALF_UP).
    var quantized2: PyDecimal {
        var x = decimal * 100
        var r = Decimal()
        NSDecimalRound(&r, &x, 0, .plain)
        let s = NSDecimalNumber(decimal: r.magnitude).stringValue
        return PyDecimal(negative: r < 0 || (negative && r == 0), coeff: s, exp: -2)
    }
}

/// Decimal value of a JSON input, following money._to_decimal. Returns nil for "" (blank).
func toDecimal(_ value: JSONValue, _ field: String) throws -> PyDecimal? {
    switch value {
    case .bool:
        throw AZError.validation("\(field) must be a number", field)
    case .int(let i):
        return PyDecimal(int: i)
    case .double(let f):
        // Swift's description of a Double is the shortest round-trip text, like Python's repr(float)
        guard case .ok(let d) = PyDecimal.parse("\(f)") else {
            throw AZError.validation("\(field) must be a valid amount", field)
        }
        let q = d.quantized2
        // tolerate binary float noise (0.1 + 0.2) but not genuine extra precision
        return abs(d.decimal - q.decimal) < Decimal(string: "0.0000001")! ? q : d
    default:
        let text = value.pyStr.replacingOccurrences(of: ",", with: "").replacingOccurrences(of: "₹", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return nil }
        switch PyDecimal.parse(text) {
        case .ok(let d): return d
        case .invalid, .nonFinite: throw AZError.validation("\(field) must be a valid amount", field)
        }
    }
}

private func isBlank(_ v: JSONValue) -> Bool {
    if v.isNull { return true }
    if case .string(let s) = v { return s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    return false
}

/// Parse a major-unit amount ("12000.50") into integer minor units.
public func parseAmount(_ value: JSONValue, _ field: String = "amount", required: Bool = false, allowZero: Bool = true,
                        allowNegative: Bool = false) throws -> Int {
    if isBlank(value) {
        if required { throw AZError.validation("\(field) is required", field) }
        return 0
    }
    guard let d = try toDecimal(value, field) else {
        if required { throw AZError.validation("\(field) is required", field) }
        return 0
    }
    guard let minor = d.hundredthsIfWhole else {
        throw AZError.validation("\(field) can have at most 2 decimal places", field)
    }
    if minor < 0 && !allowNegative { throw AZError.validation("\(field) cannot be negative", field) }
    if minor == 0 && !allowZero { throw AZError.validation("\(field) must be greater than zero", field) }
    if minor.magnitude > UInt(MAX_MINOR) { throw AZError.validation("\(field) is too large", field) }
    return minor
}

/// Exchange rate: positive decimal, up to 8 decimal places. Returned as canonical string.
public func parseRate(_ value: JSONValue, _ field: String = "exchange_rate") throws -> String {
    if isBlank(value) { throw AZError.validation("\(field) is required", field) }
    guard let d = try toDecimal(value, field), !d.negative, !d.isZero else {
        throw AZError.validation("\(field) must be greater than zero", field)
    }
    if d.exp < -8 { throw AZError.validation("\(field) can have at most 8 decimal places", field) }
    if d.decimal > 1_000_000 { throw AZError.validation("\(field) is unrealistically large", field) }
    return d.normalizedString
}

public func parsePercent(_ value: JSONValue, _ field: String = "rate") throws -> String {
    if isBlank(value) { return "0" }
    guard let d = try toDecimal(value, field) else { return "0" }
    if (d.negative && !d.isZero) || d.decimal > 100 { throw AZError.validation("\(field) must be between 0 and 100", field) }
    if d.exp < -4 { throw AZError.validation("\(field) can have at most 4 decimal places", field) }
    return d.normalizedString
}

private func roundHalfUp(_ x: Decimal) -> Int {
    var v = x, r = Decimal()
    NSDecimalRound(&r, &v, 0, .plain)
    return NSDecimalNumber(decimal: r).intValue
}

private func decimalFromText(_ s: String) -> Decimal {
    if case .ok(let d) = PyDecimal.parse(s) { return d.decimal }
    return 0
}

/// Convert minor units at the given rate into reporting-currency minor units.
public func convert(_ minor: Int, _ rate: String) -> Int {
    if minor == 0 { return 0 }
    return roundHalfUp(Decimal(minor) * decimalFromText(rate))
}

/// pct% of minor units, rounded half-up to the nearest minor unit.
public func percentOf(_ minor: Int, _ pct: String) -> Int {
    if minor == 0 { return 0 }
    return roundHalfUp(Decimal(minor) * decimalFromText(pct) / 100)
}

/// Split a tax amount into two halves (CGST/SGST) that always add back exactly.
public func splitHalf(_ minor: Int) -> (Int, Int) {
    let first = roundHalfUp(Decimal(minor) / 2)
    return (first, minor - first)
}

/// Minor units -> "12345.67" with exactly two decimals.
public func fmt(_ minor: Int) -> String {
    let sign = minor < 0 ? "-" : ""
    let m = minor.magnitude
    return sign + String(m / 100) + "." + String(format: "%02d", Int(m % 100))
}

/// Normalised decimal text for display ("9", "4.5"): Python format(Decimal(x).normalize(), "f").
public func normalizedDecimal(_ s: String) -> String {
    if case .ok(let d) = PyDecimal.parse(s) { return d.normalizedString }
    return s
}

/// Half a percentage rate as text ("18" -> "9"), used for CGST / SGST labels.
public func halfRate(_ rate: String) -> String {
    guard case .ok(let d) = PyDecimal.parse(rate) else { return rate }
    let half = d.decimal / 2
    let text = NSDecimalNumber(decimal: half).stringValue
    return normalizedDecimal(text)
}
