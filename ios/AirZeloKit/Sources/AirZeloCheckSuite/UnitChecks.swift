import Foundation
import AirZeloKit

enum UnitChecks {
    static let cases: [CheckSuite.Case] = [
        ("money: rounding and formatting", moneyBasics),
        ("money: parsing matches desktop vectors", moneyVectors),
        ("zip + crc32 round trip", zipRoundTrip),
    ]

    static func moneyBasics(_ o: CheckOptions, _ log: CheckLog) throws {
        try expectEqual(try parseAmount("12,000.50"), 1_200_050)
        try expectEqual(try parseAmount(.double(0.1)), 10)
        try expectEqual(try parseAmount("₹ 1,500"), 150_000)
        try expectError("validation", message: "Amount can have at most 2 decimal places") { _ = try parseAmount("1.005", "Amount") }
        try expectError("validation", message: "Amount must be a valid amount") { _ = try parseAmount("12abc", "Amount") }
        try expectEqual(convert(12_345, "22.15"), 273_442)         // 12345 * 22.15 = 273441.75 -> half up
        try expectEqual(convert(-1_050, "0.5"), -525)
        try expectEqual(convert(5, "0.5"), 3)                       // 2.5 -> 3 (ties away from zero)
        try expectEqual(convert(-5, "0.5"), -3)
        try expectEqual(percentOf(12_345, "18"), 2_222)             // 2222.1
        try expectEqual(splitHalf(2_223).0 + splitHalf(2_223).1, 2_223)
        try expectEqual(splitHalf(2_223).0, 1_112)
        try expectEqual(fmt(-1_050), "-10.50")
        try expectEqual(money(1_23_45_678_90, "INR"), "INR 1,23,45,678.90")
        try expectEqual(money(123_456, "SAR"), "SAR 1234.56")
        try expectEqual(halfRate("18"), "9")
        try expectEqual(halfRate("5"), "2.5")
    }

    /// Every input the desktop's money.py was given, with its exact result or refusal (from ios/tools/parity.py).
    static func moneyVectors(_ o: CheckOptions, _ log: CheckLog) throws {
        let fx = try ParityChecks.load(o)
        let vectors = try JSONValue.parse(Data(contentsOf: o.parityDir!.appendingPathComponent("fixture.json")))["money"]
        _ = fx
        var problems: [String] = []
        var n = 0
        for row in vectors["parse"].arrayValue ?? [] {
            let input = row["input"]
            let fns: [(String, (JSONValue) throws -> JSONValue)] = [
                ("amount", { .int(try parseAmount($0, "Amount")) }),
                ("amount_neg", { .int(try parseAmount($0, "Amount", allowZero: false, allowNegative: true)) }),
                ("rate", { .string(try parseRate($0, "Rate")) }),
                ("percent", { .string(try parsePercent($0, "Pct")) })]
            for (name, fn) in fns {
                n += 1
                let got: JSONValue
                do { got = ["ok": try fn(input)] } catch let e as AZError { got = ["error": .string(e.message)] }
                if got != row[name] { problems.append("\(name)(\(JSONValue.serialize(input))): desktop \(JSONValue.serialize(row[name])) iPhone \(JSONValue.serialize(got))") }
            }
        }
        for c in vectors["calc"].arrayValue ?? [] {
            let m = c["minor"].intValue!, r = c["rate"].stringValue!
            n += 4
            if convert(m, r) != c["convert"].intValue { problems.append("convert(\(m), \(r))") }
            if percentOf(m, r) != c["percent_of"].intValue { problems.append("percent_of(\(m), \(r))") }
            let sh = splitHalf(m)
            if [JSONValue.int(sh.0), .int(sh.1)] != c["split_half"].arrayValue! { problems.append("split_half(\(m))") }
            if fmt(m) != c["fmt"].stringValue { problems.append("fmt(\(m))") }
        }
        log.note("\(n) money results compared with the desktop")
        try expect(problems.isEmpty, problems.prefix(20).joined(separator: "\n"))
    }

    static func zipRoundTrip(_ o: CheckOptions, _ log: CheckLog) throws {
        try expectEqual(CRC32.checksum(Data("123456789".utf8)), 0xCBF43926, "crc32")
        let w = Zip.Writer()
        let big = Data((0..<300_000).map { UInt8($0 % 251) })
        w.add("manifest.json", Data("{\"app\": \"AirZelo\"}".utf8))
        w.add("uploads/a.bin", big)
        w.add("empty.txt", Data())
        let r = try Zip.Reader(w.finish())
        try expectEqual(r.names, ["manifest.json", "uploads/a.bin", "empty.txt"])
        try expectEqual(try r.read("uploads/a.bin"), big)
        try expectEqual(try r.read("empty.txt"), Data())
        var bad = w.finish()
        bad[60] ^= 0xFF  // damage the compressed payload of the first entry
        try expect((try? Zip.Reader(bad).read("manifest.json")) == nil, "damaged entry must be rejected")
    }
}
