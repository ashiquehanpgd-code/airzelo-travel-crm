import Foundation
import AirZeloKit

/// A small check runner. XCTest needs Xcode, so the checks are plain functions that run both from the
/// command line (`swift run AirZeloChecks`, works with the Command Line Tools alone) and from XCTest on
/// an iPhone simulator (ios/AirZelo/Tests), which call `CheckSuite.run` and fail on any failed check.
public struct CheckFailure: Error, CustomStringConvertible {
    public let message: String
    public var description: String { message }
}

public struct CheckResult {
    public let name: String
    public let passed: Bool
    public let detail: String
    public let seconds: Double
}

public struct CheckOptions {
    /// Folder with fixture.json + desktop.db from ios/tools/parity.py (parity checks are skipped without it).
    public var parityDir: URL?
    /// Bookings in the large-dataset check.
    public var scaleBookings = 20_000
    public var only: String?
    public init(parityDir: URL? = nil, scaleBookings: Int = 20_000, only: String? = nil) {
        self.parityDir = parityDir
        self.scaleBookings = scaleBookings
        self.only = only
    }
}

/// Collected notes from a check (timings, counts) shown in the report.
public final class CheckLog {
    public private(set) var lines: [String] = []
    public func note(_ s: String) { lines.append(s) }
}

public func expect(_ cond: @autoclosure () throws -> Bool, _ msg: @autoclosure () -> String, file: String = #fileID, line: Int = #line) throws {
    if try !cond() { throw CheckFailure(message: "\(msg())  [\(file):\(line)]") }
}

public func expectEqual<T: Equatable>(_ a: T, _ b: T, _ msg: String = "", file: String = #fileID, line: Int = #line) throws {
    if a != b { throw CheckFailure(message: "\(msg) expected \(b), got \(a)  [\(file):\(line)]") }
}

/// Runs `body` and requires it to throw an AZError with this code (and message, if given).
public func expectError(_ code: String, message: String? = nil, file: String = #fileID, line: Int = #line, _ body: () throws -> Void) throws {
    do {
        try body()
    } catch let e as AZError {
        if e.code != code || (message != nil && e.message != message!) {
            throw CheckFailure(message: "expected error \(code) \(message ?? ""), got \(e.code): \(e.message)  [\(file):\(line)]")
        }
        return
    }
    throw CheckFailure(message: "expected error \(code), but it succeeded  [\(file):\(line)]")
}

public enum CheckSuite {
    public typealias Case = (name: String, run: (CheckOptions, CheckLog) throws -> Void)

    public static var cases: [Case] {
        UnitChecks.cases + ServiceChecks.cases + SecurityChecks.cases + BackupChecks.cases + PDFChecks.cases
            + OfflineChecks.cases + ParityChecks.cases + ScaleChecks.cases
    }

    /// Runs every check (or those whose name contains `options.only`) and prints a report.
    @discardableResult
    public static func run(_ options: CheckOptions, print out: (String) -> Void = { print($0) }) -> [CheckResult] {
        var results: [CheckResult] = []
        for c in cases where options.only == nil || c.name.contains(options.only!) {
            let log = CheckLog()
            let start = Date()
            var passed = true, detail = ""
            do {
                try c.run(options, log)
            } catch let e as CheckSkipped {
                detail = "skipped: " + e.reason
            } catch {
                passed = false
                detail = "\(error)"
            }
            let secs = Date().timeIntervalSince(start)
            results.append(CheckResult(name: c.name, passed: passed, detail: detail, seconds: secs))
            out(String(format: "%@ %-44@ %6.2fs %@", passed ? (detail.hasPrefix("skipped") ? "SKIP" : "PASS") : "FAIL", c.name, secs, detail))
            for l in log.lines { out("       " + l) }
        }
        let failed = results.filter { !$0.passed }
        out("\(results.count - failed.count) of \(results.count) checks passed" + (failed.isEmpty ? "" : "; FAILED: " + failed.map { $0.name }.joined(separator: ", ")))
        return results
    }
}

public struct CheckSkipped: Error { public let reason: String }

/// A fresh agency in a temporary folder.
final class TempAgency {
    let dir: URL
    let agency: Agency
    init(crypto: FieldCrypto = .ephemeral()) throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("az-check-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        agency = try Agency(url: dir.appendingPathComponent("agency.db"), crypto: crypto)
    }
    deinit {
        agency.close()
        try? FileManager.default.removeItem(at: dir)
    }
}

/// Pins the services' clock (dates default to "today").
func pinClock(_ day: String) {
    let df = DateFormatter()
    df.locale = Locale(identifier: "en_US_POSIX")
    df.timeZone = .current
    df.dateFormat = "yyyy-MM-dd HH:mm:ss"
    let fixed = df.date(from: day + " 12:00:00")!
    Dates.now = { fixed }
}

func unpinClock() { Dates.now = { Date() } }
