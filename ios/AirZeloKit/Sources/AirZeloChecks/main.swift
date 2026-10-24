import Foundation
import AirZeloCheckSuite

// swift run AirZeloChecks [--parity <dir from ios/tools/parity.py>] [--only <name part>] [--scale <bookings>]
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
