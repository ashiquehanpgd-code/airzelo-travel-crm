import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// One SQLite value, typed as SQLite stores it.
public enum SQLValue: Equatable, Sendable {
    case null
    case int(Int64)
    case real(Double)
    case text(String)
    case blob(Data)

    public var isNull: Bool { if case .null = self { return true }; return false }
}

public protocol SQLBindable { var sqlValue: SQLValue { get } }
extension Int: SQLBindable { public var sqlValue: SQLValue { .int(Int64(self)) } }
extension Int64: SQLBindable { public var sqlValue: SQLValue { .int(self) } }
extension String: SQLBindable { public var sqlValue: SQLValue { .text(self) } }
extension Double: SQLBindable { public var sqlValue: SQLValue { .real(self) } }
extension Data: SQLBindable { public var sqlValue: SQLValue { .blob(self) } }
extension Bool: SQLBindable { public var sqlValue: SQLValue { .int(self ? 1 : 0) } }
extension SQLValue: SQLBindable { public var sqlValue: SQLValue { self } }
extension Optional: SQLBindable where Wrapped: SQLBindable {
    public var sqlValue: SQLValue { self?.sqlValue ?? .null }
}

/// A result row. Column order is kept so a row can be dumped exactly as SQLite returned it.
public struct Row: Sendable {
    public let columns: [String]
    public let values: [SQLValue]
    private let index: [String: Int]

    init(columns: [String], values: [SQLValue], index: [String: Int]) {
        self.columns = columns
        self.values = values
        self.index = index
    }

    public subscript(_ key: String) -> SQLValue { index[key].map { values[$0] } ?? .null }
    public func has(_ key: String) -> Bool { index[key] != nil }

    public func int(_ key: String) -> Int {
        switch self[key] {
        case .int(let v): return Int(v)
        case .real(let v): return Int(v)
        case .text(let s): return Int(s) ?? 0
        default: return 0
        }
    }
    public func optInt(_ key: String) -> Int? { self[key].isNull ? nil : int(key) }
    public func str(_ key: String) -> String { optStr(key) ?? "" }
    public func optStr(_ key: String) -> String? {
        switch self[key] {
        case .text(let s): return s
        case .int(let v): return String(v)
        case .real(let v): return String(v)
        case .null: return nil
        case .blob(let d): return String(decoding: d, as: UTF8.self)
        }
    }
    public func bool(_ key: String) -> Bool { int(key) != 0 }

    /// The row as a JSON object (SQLite integers stay integers, text stays text).
    public var json: [String: JSONValue] {
        var out: [String: JSONValue] = [:]
        for (i, c) in columns.enumerated() { out[c] = JSONValue(values[i]) }
        return out
    }
}

public struct SQLiteError: Error, CustomStringConvertible {
    public let code: Int32
    public let message: String
    public var description: String { "SQLite error \(code): \(message)" }
}

/// Thin, synchronous SQLite connection. All access to one connection happens on one thread (the caller's).
public final class SQLiteDB {
    public private(set) var handle: OpaquePointer?
    public let path: String
    private var statements: [String: OpaquePointer] = [:]

    public init(path: String, readOnly: Bool = false) throws {
        self.path = path
        let flags = readOnly ? SQLITE_OPEN_READONLY : (SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE)
        var h: OpaquePointer?
        let rc = sqlite3_open_v2(path, &h, flags | SQLITE_OPEN_FULLMUTEX, nil)
        guard rc == SQLITE_OK, let h else {
            let msg = h.map { String(cString: sqlite3_errmsg($0)) } ?? "cannot open database"
            sqlite3_close(h)
            throw SQLiteError(code: rc, message: msg)
        }
        handle = h
        sqlite3_busy_timeout(h, 20000)
    }

    deinit { close() }

    public func close() {
        for (_, s) in statements { sqlite3_finalize(s) }
        statements.removeAll()
        if let h = handle { sqlite3_close_v2(h) }
        handle = nil
    }

    var errorMessage: String { handle.map { String(cString: sqlite3_errmsg($0)) } ?? "closed" }

    /// Run one or more statements with no parameters (schema scripts, PRAGMAs).
    public func exec(_ sql: String) throws {
        var err: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(handle, sql, nil, nil, &err)
        if rc != SQLITE_OK {
            let msg = err.map { String(cString: $0) } ?? errorMessage
            sqlite3_free(err)
            throw SQLiteError(code: rc, message: msg)
        }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer {
        if let s = statements[sql] {
            sqlite3_reset(s)
            sqlite3_clear_bindings(s)
            return s
        }
        var s: OpaquePointer?
        let rc = sqlite3_prepare_v2(handle, sql, -1, &s, nil)
        guard rc == SQLITE_OK, let s else { throw SQLiteError(code: rc, message: errorMessage + " in: " + sql) }
        statements[sql] = s
        return s
    }

    private func bind(_ s: OpaquePointer, _ args: [SQLBindable]) throws {
        for (i, a) in args.enumerated() {
            let idx = Int32(i + 1)
            let rc: Int32
            switch a.sqlValue {
            case .null: rc = sqlite3_bind_null(s, idx)
            case .int(let v): rc = sqlite3_bind_int64(s, idx, v)
            case .real(let v): rc = sqlite3_bind_double(s, idx, v)
            case .text(let v): rc = sqlite3_bind_text(s, idx, v, -1, SQLITE_TRANSIENT)
            case .blob(let d): rc = d.withUnsafeBytes { sqlite3_bind_blob(s, idx, $0.baseAddress, Int32(d.count), SQLITE_TRANSIENT) }
            }
            if rc != SQLITE_OK { throw SQLiteError(code: rc, message: errorMessage) }
        }
    }

    /// Execute a statement and return all rows.
    @discardableResult
    public func query(_ sql: String, _ args: [SQLBindable] = []) throws -> [Row] {
        let s = try prepare(sql)
        defer { sqlite3_reset(s) }
        try bind(s, args)
        let n = sqlite3_column_count(s)
        var cols: [String] = []
        var index: [String: Int] = [:]
        for i in 0..<n {
            let name = String(cString: sqlite3_column_name(s, i))
            cols.append(name)
            if index[name] == nil { index[name] = Int(i) }
        }
        var out: [Row] = []
        while true {
            let rc = sqlite3_step(s)
            if rc == SQLITE_DONE { break }
            guard rc == SQLITE_ROW else { throw SQLiteError(code: rc, message: errorMessage) }
            var vals: [SQLValue] = []
            vals.reserveCapacity(Int(n))
            for i in 0..<n {
                switch sqlite3_column_type(s, i) {
                case SQLITE_INTEGER: vals.append(.int(sqlite3_column_int64(s, i)))
                case SQLITE_FLOAT: vals.append(.real(sqlite3_column_double(s, i)))
                case SQLITE_TEXT: vals.append(.text(String(cString: sqlite3_column_text(s, i))))
                case SQLITE_BLOB:
                    let len = Int(sqlite3_column_bytes(s, i))
                    vals.append(.blob(len > 0 ? Data(bytes: sqlite3_column_blob(s, i), count: len) : Data()))
                default: vals.append(.null)
                }
            }
            out.append(Row(columns: cols, values: vals, index: index))
        }
        return out
    }

    public func one(_ sql: String, _ args: [SQLBindable] = []) throws -> Row? { try query(sql, args).first }

    public func scalarInt(_ sql: String, _ args: [SQLBindable] = []) throws -> Int {
        guard let r = try one(sql, args), !r.values.isEmpty else { return 0 }
        switch r.values[0] {
        case .int(let v): return Int(v)
        case .real(let v): return Int(v)
        case .text(let t): return Int(t) ?? 0
        default: return 0
        }
    }

    /// Execute a write; returns the new row id.
    @discardableResult
    public func run(_ sql: String, _ args: [SQLBindable] = []) throws -> Int {
        try query(sql, args)
        return Int(sqlite3_last_insert_rowid(handle))
    }

    public var changes: Int { Int(sqlite3_changes(handle)) }
    public var inTransaction: Bool { sqlite3_get_autocommit(handle) == 0 }

    /// Consistent snapshot of this database into `dest` with SQLite's online backup API.
    public func backup(to dest: String) throws {
        let target = try SQLiteDB(path: dest)
        defer { target.close() }
        guard let b = sqlite3_backup_init(target.handle, "main", handle, "main") else {
            throw SQLiteError(code: sqlite3_errcode(target.handle), message: target.errorMessage)
        }
        let rc = sqlite3_backup_step(b, -1)
        sqlite3_backup_finish(b)
        guard rc == SQLITE_DONE else { throw SQLiteError(code: rc, message: target.errorMessage) }
        try target.exec("PRAGMA journal_mode = DELETE")
    }

    public static var libraryVersion: String { String(cString: sqlite3_libversion()) }

    /// True when `sql` is one complete statement (used to split scripts the way Python's sqlite3.complete_statement does).
    public static func isComplete(_ sql: String) -> Bool { sqlite3_complete(sql) != 0 }
}
