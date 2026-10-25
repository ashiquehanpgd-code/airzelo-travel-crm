package com.airzelo.app

import com.airzelo.engine.Agency
import com.airzelo.engine.Dates
import com.airzelo.engine.createCustomer
import com.airzelo.engine.createInvoice
import com.airzelo.engine.createSupplier
import com.airzelo.engine.createTransaction
import com.airzelo.engine.fieldsOf
import com.airzelo.engine.refundBooking
import com.airzelo.engine.saveAccount
import com.airzelo.engine.saveBooking
import java.time.LocalDate
import kotlin.random.Random

/**
 * Synthetic demo books (invented names and numbers, through the normal services) for design review screenshots and
 * UI tests. Loaded only by tests and by debug builds started with [MainActivity.EXTRA_DEMO]; never by the release app.
 */
object DemoData {
    private val first = listOf("Aisha", "Rahul", "Fathima", "Arjun", "Meera", "Imran", "Sneha", "Vikram", "Nadia", "Joseph", "Lakshmi", "Faisal",
        "Divya", "Anwar", "Priya", "Sameer", "Riya", "Kiran", "Zainab", "Thomas")
    private val last = listOf("Rahman", "Nair", "Menon", "Varghese", "Pillai", "Khan", "Iyer", "Kurian", "Thomas", "Shetty")
    private val routes = listOf(Triple("COK", "DXB", "Emirates"), Triple("COK", "JED", "Saudia"), Triple("TRV", "DOH", "Qatar Airways"),
        Triple("CCJ", "RUH", "IndiGo"), Triple("COK", "SIN", "Singapore Airlines"), Triple("BLR", "LHR", "British Airways"), Triple("COK", "MCT", "Oman Air"))

    fun seed(a: Agency, bookings: Int = 36, seed: Int = 7) {
        val rnd = Random(seed)
        a.updateSettings(fieldsOf("business_name" to "Quick Trip Travels", "business_tagline" to "Flights · Umrah · Visas", "business_phone" to "+91 90000 00000",
            "pay_account_name" to "Quick Trip Travels", "pay_upi_number" to "9000000000"))
        val cash = a.db.scalarLong("SELECT id FROM accounts WHERE name = 'Cash in hand'")
        val bank = a.saveAccount(fieldsOf("name" to "HDFC Current", "account_type" to "current", "currency" to "INR", "opening_balance" to "250000", "account_last4" to "4321"))
        val sups = listOf("Akbar Online", "Riya Travels B2B", "Alhind Portal").map { a.createSupplier(fieldsOf("name" to it, "kind" to "b2b_portal")) }
        val custs = (0 until 18).map { i ->
            a.createCustomer(fieldsOf("name" to "${first[i % first.size]} ${last[(i * 3) % last.size]}", "phone" to "+91 9${(800000000 + i * 7919) % 1000000000}",
                "city" to listOf("Kochi", "Kozhikode", "Thrissur", "Malappuram", "Thiruvananthapuram")[i % 5],
                "follow_up_date" to if (i % 6 == 0) LocalDate.parse(Dates.today()).plusDays((i % 4).toLong() - 1).toString() else null,
                "follow_up_note" to if (i % 6 == 0) "Call about Umrah package" else null))
        }
        val today = LocalDate.parse(Dates.today())
        for (i in 0 until bookings) {
            val day = today.minusDays(3 + ((bookings - i) * 5L) % 170)
            val (o, d, airline) = routes[i % routes.size]
            val cost = 18000 + rnd.nextInt(0, 30) * 1000
            val sell = cost + 1500 + rnd.nextInt(0, 12) * 250
            val status = listOf("ticketed", "ticketed", "confirmed", "ticketed", "enquiry", "on_hold")[i % 6]
            val travel = day.plusDays(10L + rnd.nextInt(0, 40))
            val cid = custs[i % custs.size]
            val pax = (0..(i % 3)).map { k -> mapOf("name" to "${first[(i + k * 5) % first.size]} ${last[(i + 2) % last.size]}",
                "ticket_number" to if (status == "ticketed") "065-24${(1000000 + i * 31 + k)}" else null) }
            val bid = a.saveBooking(fieldsOf("customer_id" to cid, "supplier_id" to sups[i % sups.size], "status" to status, "booking_date" to day.toString(),
                "travel_date" to travel.toString(), "pnr" to "QT${(36 * 36 * 36 + i * 977).toString(36).uppercase().takeLast(4)}${i % 10}",
                "purchase_cost" to cost.toString(), "selling_price" to sell.toString(), "commission" to if (i % 3 == 0) "250" else "0",
                "service_charge" to if (i % 4 == 0) "500" else "0", "tax_profile_id" to if (i % 4 == 0) 2 else null,
                "passengers" to pax, "force_duplicate" to true,
                "segments" to listOf(mapOf("airline" to airline, "flight_no" to "${airline.take(2).uppercase()} ${200 + i}", "origin" to o, "destination" to d,
                    "dep_at" to "${travel}T0${(i % 9) + 1}:15", "arr_at" to "${travel}T1${i % 9}:40"))))
            if (status == "ticketed" || status == "confirmed") {
                val payable = sell + (if (i % 4 == 0) 590 else 0)  // prices are for the whole booking; 500 service charge + 18% GST
                val paid = if (i % 5 == 0) payable / 2 else if (i % 7 == 0) 0 else payable
                if (paid > 0) a.createTransaction(fieldsOf("kind" to "customer_receipt", "txn_date" to day.plusDays(1).toString(), "account_id" to if (i % 2 == 0) bank else cash,
                    "amount" to paid.toString(), "booking_id" to bid, "method" to if (i % 2 == 0) "upi" else "cash", "force_duplicate" to true))
                if (i % 2 == 0) a.createTransaction(fieldsOf("kind" to "supplier_payment", "txn_date" to day.plusDays(2).toString(), "account_id" to bank,
                    "amount" to (cost - (if (i % 3 == 0) 250 else 0)).toString(), "booking_id" to bid, "method" to "portal_wallet", "force_duplicate" to true))
                if (i % 9 == 0) a.createInvoice(bid, fieldsOf("issue_date" to day.plusDays(1).toString()))
                if (i == 12) a.refundBooking(bid, fieldsOf("customer_refund" to "5000", "supplier_refund" to "4000", "date" to day.plusDays(3).toString(), "new_status" to ""))
            }
        }
        for (m in 0 until 5) {
            val d = today.minusDays(m * 30L + 3)
            a.createTransaction(fieldsOf("kind" to "expense", "txn_date" to d.toString(), "account_id" to bank, "amount" to "18000", "category" to "Rent", "force_duplicate" to true))
            a.createTransaction(fieldsOf("kind" to "expense", "txn_date" to d.plusDays(2).toString(), "account_id" to cash, "amount" to (2500 + m * 300).toString(),
                "category" to "Office expenses", "force_duplicate" to true))
        }
    }
}
