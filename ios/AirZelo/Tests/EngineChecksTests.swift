import XCTest
import AirZeloCheckSuite

/// Runs the whole AirZeloKit check suite inside the iOS simulator (real iOS Keychain, Data Protection and
/// SQLite). Parity checks need the desktop fixture: set AZ_PARITY_DIR (xcodebuild: TEST_RUNNER_AZ_PARITY_DIR).
final class EngineChecksTests: XCTestCase {
    func testEngineChecks() {
        var options = CheckOptions(scaleBookings: 10_000)
        if let dir = ProcessInfo.processInfo.environment["AZ_PARITY_DIR"], !dir.isEmpty {
            options.parityDir = URL(fileURLWithPath: dir)
        }
        let results = CheckSuite.run(options) { print($0) }
        XCTAssertGreaterThanOrEqual(results.count, 25)
        for r in results {
            XCTContext.runActivity(named: r.name) { _ in
                if !r.passed { XCTFail("\(r.name): \(r.detail)") }
            }
        }
        if ProcessInfo.processInfo.environment["AZ_REQUIRE_PARITY"] == "1" {
            XCTAssertNotNil(options.parityDir, "AZ_PARITY_DIR was not passed to the simulator")
        }
        if options.parityDir != nil {
            let skipped = results.filter { $0.detail.hasPrefix("skipped") }.map { $0.name }
            XCTAssertFalse(skipped.contains { $0.hasPrefix("parity") }, "parity checks must run when AZ_PARITY_DIR is set")
        }
    }
}
