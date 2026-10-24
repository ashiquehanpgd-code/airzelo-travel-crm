import Foundation

/// Features that need a live connection to an airline, GDS, B2B portal or payment gateway.
///
/// AirZeloKit contains no network code at all: every booking, payment, invoice, report, PDF and backup is
/// produced on the device. These live features are therefore reported as unavailable - they never return a
/// made-up flight, fare, PNR or payment confirmation. What the agent CAN do offline is record the result
/// of a booking or payment they made elsewhere (portal, airline website, bank app), which the services support.
public enum OnlineFeature: String, CaseIterable, Identifiable, Sendable {
    case flightSearch = "flight_search"
    case airlineBooking = "airline_booking"
    case paymentVerification = "payment_verification"
    case livePNRStatus = "live_pnr_status"

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .flightSearch: return "Live flight search"
        case .airlineBooking: return "New airline booking"
        case .paymentVerification: return "Online payment verification"
        case .livePNRStatus: return "Live PNR confirmation"
        }
    }

    public var offlineAlternative: String {
        switch self {
        case .flightSearch: return "Search fares on your B2B portal, then record the booking here."
        case .airlineBooking: return "Book on the airline or portal, then save the booking with its PNR and ticket numbers here."
        case .paymentVerification: return "Check the payment in your bank or UPI app, then record the receipt with its UTR / reference."
        case .livePNRStatus: return "The PNR, status and ticket numbers shown here are the ones you saved; confirm them with the airline."
        }
    }

    /// Always refuses: no provider is connected in this version, and nothing is simulated.
    public func perform() throws -> Never {
        throw AZError.unavailable("\(title) is not available: it needs a live connection to an airline, portal or payment provider, "
                                  + "which this version does not have. \(offlineAlternative)")
    }
}
