package com.airzelo.engine

/**
 * Features that need a live connection to an airline, GDS, B2B portal or payment gateway.
 *
 * The engine contains no network code at all: every booking, payment, invoice, report, PDF and backup is
 * produced on the device. These live features are therefore reported as unavailable - they never return a
 * made-up flight, fare, PNR or payment confirmation. What the agent CAN do offline is record the result
 * of a booking or payment they made elsewhere (portal, airline website, bank app). Port of Offline/OnlineFeatures.swift.
 */
enum class OnlineFeature(val id: String, val title: String, val offlineAlternative: String) {
    FLIGHT_SEARCH("flight_search", "Live flight search", "Search fares on your B2B portal, then record the booking here."),
    AIRLINE_BOOKING("airline_booking", "New airline booking",
        "Book on the airline or portal, then save the booking with its PNR and ticket numbers here."),
    PAYMENT_VERIFICATION("payment_verification", "Online payment verification",
        "Check the payment in your bank or UPI app, then record the receipt with its UTR / reference."),
    LIVE_PNR_STATUS("live_pnr_status", "Live PNR confirmation",
        "The PNR, status and ticket numbers shown here are the ones you saved; confirm them with the airline.");

    /** Always refuses: no provider is connected in this version, and nothing is simulated. */
    fun perform(): Nothing = throw AZError.unavailable("$title is not available: it needs a live connection to an airline, portal or payment provider, " +
        "which this version does not have. $offlineAlternative")
}
