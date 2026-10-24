import Foundation

/// The desktop app's error classes (server/errors.py): the same codes and messages, so the iPhone shows
/// the same guidance and parity tests can compare refusals as well as results.
public struct AZError: Error, LocalizedError, Equatable, CustomStringConvertible {
    public enum Kind: String, Sendable { case validation, notFound = "not_found", conflict, unavailable }
    public let kind: Kind
    public let message: String
    /// "validation", "not_found" or the conflict code ("duplicate_pnr", "refund_not_due", ...).
    public let code: String
    public let field: String?
    public let details: JSONValue

    public var errorDescription: String? { message }
    public var description: String { "\(code): \(message)" }

    public static func validation(_ message: String, _ field: String? = nil) -> AZError {
        AZError(kind: .validation, message: message, code: "validation", field: field, details: .null)
    }
    public static func notFound(_ message: String = "Not found") -> AZError {
        AZError(kind: .notFound, message: message, code: "not_found", field: nil, details: .null)
    }
    public static func conflict(_ message: String, _ code: String = "conflict", _ details: JSONValue = .null) -> AZError {
        AZError(kind: .conflict, message: message, code: code, field: nil, details: details)
    }
    /// A feature that needs the internet (or the desktop app) and is deliberately not simulated offline.
    public static func unavailable(_ message: String) -> AZError {
        AZError(kind: .unavailable, message: message, code: "unavailable", field: nil, details: .null)
    }
}
