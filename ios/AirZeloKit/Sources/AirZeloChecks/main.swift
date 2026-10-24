import Foundation
import AirZeloCheckSuite

// swift run AirZeloChecks [--parity <dir from ios/tools/parity.py>] [--only <name part>] [--scale <bookings>]
// child process for the crash-safety check
if CommandLine.arguments.count == 4 && CommandLine.arguments[1] == "--crash-writer" {
    BackupChecks.crashWriter(CommandLine.arguments[2], CommandLine.arguments[3])
}
var options = CheckOptions()
var args = Array(CommandLine.arguments.dropFirst())
while !args.isEmpty {
    let a = args.removeFirst()
    switch a {
    case "--parity": options.parityDir = URL(fileURLWithPath: args.removeFirst())
    case "--only": options.only = args.removeFirst()
    case "--scale": options.scaleBookings = Int(args.removeFirst()) ?? options.scaleBookings
    default: print("unknown argument \(a)"); exit(2)
    }
}
let results = CheckSuite.run(options)
exit(results.allSatisfy { $0.passed } ? 0 : 1)
