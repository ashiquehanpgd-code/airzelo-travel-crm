// swift-tools-version:5.9
// AirZeloKit: the offline engine of the AirZelo iPhone app (database, accounting, reports, PDFs, security, backups).
// No third-party dependencies and no network code. Builds and runs its checks on macOS too, so it can be
// verified without an iPhone: `swift run AirZeloChecks` (see ios/README.md).
import PackageDescription

let package = Package(
    name: "AirZeloKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "AirZeloKit", targets: ["AirZeloKit"]),
        .library(name: "AirZeloCheckSuite", targets: ["AirZeloCheckSuite"]),
    ],
    targets: [
        .target(name: "AirZeloKit", linkerSettings: [.linkedLibrary("sqlite3")]),
        // the checks live in a library so the same cases run from the command line and from XCTest on an iPhone simulator
        .target(name: "AirZeloCheckSuite", dependencies: ["AirZeloKit"]),
        .executableTarget(name: "AirZeloChecks", dependencies: ["AirZeloCheckSuite", "AirZeloKit"]),
    ]
)
