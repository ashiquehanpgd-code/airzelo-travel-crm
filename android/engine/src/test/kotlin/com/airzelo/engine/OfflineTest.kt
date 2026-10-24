package com.airzelo.engine

import org.junit.Test
import java.security.Permission

/** Port of AirZeloCheckSuite/OfflineChecks.swift: the whole workflow runs with no network access. */
class OfflineTest {
    /** Records any socket connection or host lookup attempted while it is installed. */
    private class NetworkTripwire : SecurityManager() {
        val attempts = ArrayList<String>()
        override fun checkConnect(host: String?, port: Int) { attempts.add("$host:$port") }
        override fun checkConnect(host: String?, port: Int, context: Any?) { attempts.add("$host:$port") }
        override fun checkPermission(perm: Permission?) {}
        override fun checkPermission(perm: Permission?, context: Any?) {}
    }

    @Suppress("DEPRECATION")
    @Test fun fullWorkflowMakesNoNetworkCalls() {
        val root = tempDir("az-offline-")
        val tripwire = NetworkTripwire()
        val installed = runCatching { System.setSecurityManager(tripwire) }.isSuccess
        try {
            val store = AgencyStore(root, BackupTest.randomKey(), JdbcDb.factory)
            val a = store.open()
            val s = PdfTest.setup(a)
            a.updateCustomer(s.cust, f("notes" to "edited offline"))
            val (tid, _) = a.createTransaction(f("kind" to "expense", "account_id" to s.bank, "amount" to "450", "category" to "Office expenses"))
            a.reverseTransaction(tid, f("reason" to "entered twice"))
            val inv = a.createInvoice(s.booking)
            a.ticketPDF(s.booking, withPayment = true)
            a.invoicePDF(inv)
            val L = Ledger(a)
            for (name in REPORT_NAMES) L.report(name, ReportParams())
            L.dashboard(null, null)
            a.globalSearch("rahman")
            val b = store.createBackup()
            store.restore(store.backupData(b))
            store.exportBackup("offline-passphrase")
            store.close()
        } finally {
            if (installed) System.setSecurityManager(null)
            root.deleteRecursively()
        }
        expectTrue(tripwire.attempts.isEmpty(), "network was used: ${tripwire.attempts}")
        note(if (installed) "customers, bookings, payments, reversal, invoice, 2 PDFs, 13 reports, dashboard, search, backup, restore, export: 0 network requests"
             else "security manager unavailable on this JVM; covered by the static source scan only")
    }
}
