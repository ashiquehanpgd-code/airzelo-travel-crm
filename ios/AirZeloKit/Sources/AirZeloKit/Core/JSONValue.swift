import Foundation

/// A JSON value. Service inputs ("payloads") use the same shape as the desktop app's JSON API, so the
/// Swift port can apply the desktop validation rules unchanged and replay the same inputs in parity tests.
public enum JSONValue: Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(_ v: SQLValue) {
        switch v {
        case .null: self = .null
        case .int(let i): self = .int(Int(i))
        case .real(let d): self = .double(d)
        case .text(let s): self = .string(s)
        case .blob(let d): self = .string(d.base64EncodedString())
        }
    }

    public var isNull: Bool { if case .null = self { return true }; return false }
    public var stringValue: String? { if case .string(let s) = self { return s }; return nil }
    public var intValue: Int? {
        switch self {
        case .int(let i): return i
        case .double(let d) where d == d.rounded(): return Int(d)
        case .string(let s): return Int(s.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }
    public var objectValue: [String: JSONValue]? { if case .object(let o) = self { return o }; return nil }
    public var arrayValue: [JSONValue]? { if case .array(let a) = self { return a }; return nil }

    public subscript(_ key: String) -> JSONValue {
        if case .object(let o) = self { return o[key] ?? .null }
        return .null
    }

    /// Python `str(value)` for scalars (used where the desktop code compares or stores str(...)).
    public var pyStr: String {
        switch self {
        case .null: return "None"
        case .bool(let b): return b ? "True" : "False"
        case .int(let i): return String(i)
        case .double(let d): return JSONValue.pyFloat(d)
        case .string(let s): return s
        case .array, .object: return JSONValue.serialize(self, sortKeys: true)
        }
    }

    /// Python truthiness.
    public var truthy: Bool {
        switch self {
        case .null: return false
        case .bool(let b): return b
        case .int(let i): return i != 0
        case .double(let d): return d != 0
        case .string(let s): return !s.isEmpty
        case .array(let a): return !a.isEmpty
        case .object(let o): return !o.isEmpty
        }
    }

    static func pyFloat(_ d: Double) -> String {
        if d.isNaN { return "nan" }
        if d.isInfinite { return d > 0 ? "inf" : "-inf" }
        if d == d.rounded() && abs(d) < 1e16 { return String(format: "%.1f", d) }
        return "\(d)"
    }

    // MARK: parsing / serialising

    public static func parse(_ data: Data) throws -> JSONValue {
        let obj = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        return from(any: obj)
    }

    public static func parse(_ text: String) throws -> JSONValue { try parse(Data(text.utf8)) }

    public static func from(any obj: Any) -> JSONValue {
        switch obj {
        case is NSNull: return .null
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return .bool(n.boolValue) }
            let t = String(cString: n.objCType)
            if ["c", "s", "i", "l", "q", "C", "S", "I", "L", "Q"].contains(t) { return .int(n.intValue) }
            return .double(n.doubleValue)
        case let s as String: return .string(s)
        case let a as [Any]: return .array(a.map { from(any: $0) })
        case let o as [String: Any]: return .object(o.mapValues { from(any: $0) })
        default: return .null
        }
    }

    /// Deterministic JSON text (keys sorted when asked, Python json.dumps separators).
    public static func serialize(_ v: JSONValue, sortKeys: Bool = true) -> String {
        var out = ""
        write(v, into: &out, sortKeys: sortKeys)
        return out
    }

    private static func write(_ v: JSONValue, into out: inout String, sortKeys: Bool) {
        switch v {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .int(let i): out += String(i)
        case .double(let d): out += pyFloat(d)
        case .string(let s): writeString(s, into: &out)
        case .array(let a):
            out += "["
            for (i, x) in a.enumerated() {
                if i > 0 { out += ", " }
                write(x, into: &out, sortKeys: sortKeys)
            }
            out += "]"
        case .object(let o):
            out += "{"
            let keys = sortKeys ? o.keys.sorted() : Array(o.keys)
            for (i, k) in keys.enumerated() {
                if i > 0 { out += ", " }
                writeString(k, into: &out)
                out += ": "
                write(o[k]!, into: &out, sortKeys: sortKeys)
            }
            out += "}"
        }
    }

    private static func writeString(_ s: String, into out: inout String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if u.value < 0x20 || u.value > 0x7E {
                    if u.value > 0xFFFF {
                        let v = u.value - 0x10000
                        out += String(format: "\\u%04x\\u%04x", 0xD800 + (v >> 10), 0xDC00 + (v & 0x3FF))
                    } else {
                        out += String(format: "\\u%04x", u.value)
                    }
                } else {
                    out.unicodeScalars.append(u)
                }
            }
        }
        out += "\""
    }
}

extension JSONValue: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral,
                     ExpressibleByNilLiteral, ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByFloatLiteral {
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .int(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(nilLiteral: ()) { self = .null }
    public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        var o: [String: JSONValue] = [:]
        for (k, v) in elements { o[k] = v }
        self = .object(o)
    }
    public init(floatLiteral value: Double) { self = .double(value) }
}

/// Service input: the same keys the desktop API accepts.
public typealias Fields = [String: JSONValue]

extension Dictionary where Key == String, Value == JSONValue {
    /// Python `data.get(key)`: missing keys read as null.
    public func get(_ key: String) -> JSONValue { self[key] ?? .null }
    /// Python `key in data`.
    public func contains(_ key: String) -> Bool { self[key] != nil }
}

/// Helpers to build JSON from Swift values.
public func J(_ s: String?) -> JSONValue { s.map { .string($0) } ?? .null }
public func J(_ i: Int?) -> JSONValue { i.map { .int($0) } ?? .null }
public func J(_ b: Bool) -> JSONValue { .bool(b) }
