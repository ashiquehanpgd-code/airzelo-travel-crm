import Foundation
import PDFKit
import AirZeloKit

enum PDFChecks {
    static let cases: [CheckSuite.Case] = [
        ("pdf: e-ticket with connection and payment page", ticket),
        ("pdf: itinerary for an unconfirmed booking", itinerary),
        ("pdf: tax invoice, credit note, cancelled stamp", invoices),
    ]

    static func text(_ data: Data) throws -> (String, Int) {
        guard let doc = PDFDocument(data: data) else { throw CheckFailure(message: "not a readable PDF") }
        return (doc.string ?? "", doc.pageCount)
    }

    static func setup(_ a: Agency) throws -> (booking: Int, cust: Int, bank: Int) {
        try a.updateSettings(["business_name": "Demo Travels", "business_tagline": "Synthetic test agency", "business_phone": "+91 90000 00000",
                              "business_gstin": "32ABCDE1234F1Z5", "pay_account_name": "Demo Travels", "pay_account_number": "123456789012",
                              "pay_ifsc": "ABCD0123456", "pay_bank_name": "Demo Bank", "pay_upi_number": "9000000000", "brand_color": "#0E7C66"])
        let x = try ServiceChecks.basics(a)
        let b = try a.saveBooking(["customer_id": .int(x.cust), "supplier_id": .int(x.sup), "status": "ticketed", "pnr": "K7Q2LM",
                                   "trip_type": "one_way", "purchase_cost": "40000", "selling_price": "43000", "service_charge": "1000", "tax_profile_id": 2,
                                   "passengers": [["title": "MRS", "name": "Fathima Rahman", "ticket_number": "065-2417545001"],
                                                  ["title": "MR", "name": "Mr Ahmed Rahman", "pax_type": "ADT", "ticket_number": "065-2417545002"],
                                                  ["title": "MSTR", "name": "Zayan Rahman", "pax_type": "CHD"]],
                                   "segments": [["airline": "IndiGo", "flight_no": "6E 1407", "origin": "COK", "destination": "DXB",
                                                 "dep_at": "2026-11-02T04:10", "arr_at": "2026-11-02T06:40", "baggage": "30 kg"],
                                                ["airline": "Emirates", "flight_no": "EK 803", "origin": "DXB", "destination": "JED",
                                                 "dep_at": "2026-11-02T09:15", "arr_at": "2026-11-02T11:05", "airline_pnr": "EKX9QZ"]]])
        try a.createTransaction(["kind": "customer_receipt", "account_id": .int(x.bank), "amount": "20000", "booking_id": .int(b)])
        return (b, x.cust, x.bank)
    }

    static func ticket(_ o: CheckOptions, _ log: CheckLog) throws {
        let t = try TempAgency()
        let s = try setup(t.agency)
        let start = Date()
        let (pdf, name) = try t.agency.ticketPDF(s.booking, withPayment: true)
        let ms = Date().timeIntervalSince(start) * 1000
        let (txt, pages) = try text(pdf)
        try expectEqual(name, "Booking_BK-26-00001_K7Q2LM_Fathima-Rahman_Ticket+Payment.pdf")
        for needle in ["E-TICKET", "K7Q2LM", "DEMO TRAVELS", "MRS FATHIMA RAHMAN", "MR AHMED RAHMAN", "065-2417545001", "Not issued yet",
                       "Layover at DXB (Dubai)", "2h 35m", "EK 803", "Airline PNR EKX9QZ", "GOOD TO KNOW", "PAYMENT DETAILS",
                       "Partly paid", "INR 24,180.00", "123456789012", "UPI PAYMENT", "9000000000"] {
            try expect(txt.contains(needle), "ticket PDF is missing \"\(needle)\"")
        }
        try expect(!txt.contains("MR MR AHMED"), "a title typed into the name is not repeated")
        try expect(pages >= 2, "ticket + payment page")
        log.note(String(format: "%d pages, %d bytes, generated in %.0f ms", pages, pdf.count, ms))
    }

    static func itinerary(_ o: CheckOptions, _ log: CheckLog) throws {
        let t = try TempAgency()
        let a = t.agency
        let cid = try a.createCustomer(["name": "Enquiry Customer"])
        let b = try a.saveBooking(["customer_id": .int(cid), "status": "enquiry", "passengers": [["name": "Walk In"]]])
        let (txt, _) = try text(try a.ticketPDF(b).data)
        for needle in ["ITINERARY (NOT CONFIRMED)", "not valid for travel", "No flight segments are recorded", "This is not an e-ticket yet"] {
            try expect(txt.contains(needle), "itinerary PDF is missing \"\(needle)\"")
        }
    }

    static func invoices(_ o: CheckOptions, _ log: CheckLog) throws {
        let t = try TempAgency()
        let a = t.agency
        let s = try setup(a)
        let inv = try a.createInvoice(s.booking, ["issue_date": "2026-10-06"])
        let (txt, _) = try text(try a.invoicePDF(inv).data)
        for needle in ["TAX INVOICE", "INV/2026-27/0001", "Fathima Rahman", "Air ticket | PNR K7Q2LM", "CGST @ 9%", "SGST @ 9%", "INR 44,180.00",
                       "Balance due", "INR 24,180.00", "GSTIN 32ABCDE1234F1Z5"] {
            try expect(txt.contains(needle), "invoice PDF is missing \"\(needle)\"")
        }
        let adj = try a.refundBooking(s.booking, ["customer_refund": "10000", "supplier_refund": "8000", "date": "2026-10-10"]).adjustmentId
        let cn = try a.createCreditNote(adj, ["issue_date": "2026-10-10"])
        let (cnTxt, _) = try text(try a.invoicePDF(cn).data)
        for needle in ["CREDIT NOTE", "CN/2026-27/0001", "Against invoice INV/2026-27/0001", "Refund credit - booking BK-26-00001", "INR 10,000.00"] {
            try expect(cnTxt.contains(needle), "credit note PDF is missing \"\(needle)\"")
        }
        try a.cancelInvoice(cn, ["reason": "Issued in error"])
        let (cancelled, _) = try text(try a.invoicePDF(cn).data)
        try expect(cancelled.contains("CANCELLED") && cancelled.contains("Issued in error"), "cancelled stamp and reason")
    }
}
