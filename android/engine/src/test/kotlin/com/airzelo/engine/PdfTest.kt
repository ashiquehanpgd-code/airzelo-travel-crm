package com.airzelo.engine

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Test

/** Port of AirZeloCheckSuite/PDFChecks.swift, plus the Android payment receipt. Text is read back with PDFBox. */
class PdfTest {
    data class Setup(val booking: Long, val cust: Long, val bank: Long)

    companion object {
        fun text(data: ByteArray): Pair<String, Int> = PDDocument.load(data).use { PDFTextStripper().getText(it) to it.numberOfPages }

        fun setup(a: Agency): Setup {
            a.updateSettings(f("business_name" to "Demo Travels", "business_tagline" to "Synthetic test agency", "business_phone" to "+91 90000 00000",
                "business_gstin" to "32ABCDE1234F1Z5", "pay_account_name" to "Demo Travels", "pay_account_number" to "123456789012",
                "pay_ifsc" to "ABCD0123456", "pay_bank_name" to "Demo Bank", "pay_upi_number" to "9000000000", "brand_color" to "#0E7C66"))
            val x = ServiceTest.basics(a)
            val b = a.saveBooking(f("customer_id" to x.cust, "supplier_id" to x.sup, "status" to "ticketed", "pnr" to "K7Q2LM",
                "trip_type" to "one_way", "purchase_cost" to "40000", "selling_price" to "43000", "service_charge" to "1000", "tax_profile_id" to 2,
                "passengers" to listOf(mapOf("title" to "MRS", "name" to "Fathima Rahman", "ticket_number" to "065-2417545001"),
                    mapOf("title" to "MR", "name" to "Mr Ahmed Rahman", "pax_type" to "ADT", "ticket_number" to "065-2417545002"),
                    mapOf("title" to "MSTR", "name" to "Zayan Rahman", "pax_type" to "CHD")),
                "segments" to listOf(mapOf("airline" to "IndiGo", "flight_no" to "6E 1407", "origin" to "COK", "destination" to "DXB",
                    "dep_at" to "2026-11-02T04:10", "arr_at" to "2026-11-02T06:40", "baggage" to "30 kg"),
                    mapOf("airline" to "Emirates", "flight_no" to "EK 803", "origin" to "DXB", "destination" to "JED",
                        "dep_at" to "2026-11-02T09:15", "arr_at" to "2026-11-02T11:05", "airline_pnr" to "EKX9QZ"))))
            a.createTransaction(f("kind" to "customer_receipt", "account_id" to x.bank, "amount" to "20000", "booking_id" to b))
            return Setup(b, x.cust, x.bank)
        }
    }

    @Test fun eTicketWithConnectionAndPaymentPage() = TempAgency().use { t ->
        val s = setup(t.agency)
        val start = System.nanoTime()
        val pdf = t.agency.ticketPDF(s.booking, withPayment = true)
        val ms = (System.nanoTime() - start) / 1e6
        val (txt, pages) = text(pdf.data)
        expectEq(pdf.fileName, "Booking_BK-26-00001_K7Q2LM_Fathima-Rahman_Ticket+Payment.pdf")
        for (needle in listOf("E-TICKET", "K7Q2LM", "DEMO TRAVELS", "MRS FATHIMA RAHMAN", "MR AHMED RAHMAN", "065-2417545001", "Not issued yet",
            "Layover at DXB (Dubai)", "2h 35m", "EK 803", "Airline PNR EKX9QZ", "GOOD TO KNOW", "PAYMENT DETAILS",
            "Partly paid", "INR 24,180.00", "123456789012", "UPI PAYMENT", "9000000000")) {
            expectTrue(txt.contains(needle), "ticket PDF is missing \"$needle\"")
        }
        expectTrue(!txt.contains("MR MR AHMED"), "a title typed into the name is not repeated")
        expectTrue(pages >= 2, "ticket + payment page")
        note(String.format("%d pages, %d bytes, generated in %.0f ms", pages, pdf.data.size, ms))
    }

    @Test fun itineraryForUnconfirmedBooking() = TempAgency().use { t ->
        val a = t.agency
        val cid = a.createCustomer(f("name" to "Enquiry Customer"))
        val b = a.saveBooking(f("customer_id" to cid, "status" to "enquiry", "passengers" to listOf(mapOf("name" to "Walk In"))))
        val (txt, _) = text(a.ticketPDF(b).data)
        for (needle in listOf("ITINERARY (NOT CONFIRMED)", "not valid for travel", "No flight segments are recorded", "This is not an e-ticket yet")) {
            expectTrue(txt.contains(needle), "itinerary PDF is missing \"$needle\"")
        }
    }

    @Test fun taxInvoiceCreditNoteCancelledStamp() = TempAgency().use { t ->
        val a = t.agency
        val s = setup(a)
        val inv = a.createInvoice(s.booking, f("issue_date" to "2026-10-06"))
        val (txt, _) = text(a.invoicePDF(inv).data)
        for (needle in listOf("TAX INVOICE", "INV/2026-27/0001", "Fathima Rahman", "Air ticket | PNR K7Q2LM", "CGST @ 9%", "SGST @ 9%", "INR 44,180.00",
            "Balance due", "INR 24,180.00", "GSTIN 32ABCDE1234F1Z5")) {
            expectTrue(txt.contains(needle), "invoice PDF is missing \"$needle\"")
        }
        val adj = a.refundBooking(s.booking, f("customer_refund" to "10000", "supplier_refund" to "8000", "date" to "2026-10-10")).adjustmentId
        val cn = a.createCreditNote(adj, f("issue_date" to "2026-10-10"))
        val (cnTxt, _) = text(a.invoicePDF(cn).data)
        for (needle in listOf("CREDIT NOTE", "CN/2026-27/0001", "Against invoice INV/2026-27/0001", "Refund credit - booking BK-26-00001", "INR 10,000.00")) {
            expectTrue(cnTxt.contains(needle), "credit note PDF is missing \"$needle\"")
        }
        a.cancelInvoice(cn, f("reason" to "Issued in error"))
        val (cancelled, _) = text(a.invoicePDF(cn).data)
        expectTrue(cancelled.contains("CANCELLED") && cancelled.contains("Issued in error"), "cancelled stamp and reason")
    }

    @Test fun paymentReceipt() = TempAgency().use { t ->
        val a = t.agency
        val s = setup(a)
        a.updateSettings(f("receipt_footer" to "Thank you for your payment."))
        val tid = a.db.one("SELECT id FROM transactions WHERE kind = 'customer_receipt'")!!.long("id")
        val pdf = a.receiptPDF(tid)
        val (txt, pages) = text(pdf.data)
        for (needle in listOf("PAYMENT RECEIPT", "RECEIVED FROM", "Test Customer", "INR 20,000.00", "BK-26-00001", "PNR K7Q2LM",
            "Balance after all payments: INR 24,180.00", "Thank you for your payment.")) {
            expectTrue(txt.contains(needle), "receipt PDF is missing \"$needle\"")
        }
        expectEq(pages, 1)
        expectTrue(pdf.fileName.startsWith("Receipt_") && pdf.fileName.endsWith(".pdf"), pdf.fileName)
        expectError("validation") { a.receiptPDF(a.createTransaction(f("kind" to "expense", "account_id" to s.bank, "amount" to "10", "category" to "Rent")).first) }
    }

    @Test fun textOutsideWinAnsiIsReplacedNotBroken() {
        val p = PdfCanvas("t", "a")
        p.newPage()
        p.text(40.0, 40.0, "Fare ₹ 1,500 → DXB (Paid) \\ ok – done 😀", 10.0)
        val (txt, _) = text(p.finish())
        expectTrue(txt.contains("Fare INR  1,500 -> DXB (Paid) \\ ok – done ?"), "got: $txt")
    }
}
