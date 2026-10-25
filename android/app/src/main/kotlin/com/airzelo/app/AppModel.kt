package com.airzelo.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.airzelo.engine.AZError
import com.airzelo.engine.Agency
import com.airzelo.engine.AgencyStore
import com.airzelo.engine.BackupCrypto
import com.airzelo.engine.Fields
import com.airzelo.engine.JValue
import com.airzelo.engine.PdfFile
import com.airzelo.engine.SqlException
import com.airzelo.engine.Zip
import java.io.File

/** Where the books live. Tests replace it before the activity starts (temporary folder, random key, no lock). */
object AppConfig {
    class Overrides(val root: File, val key: ByteArray, val skipLock: Boolean = true)
    @Volatile var overrides: Overrides? = null
    /** Tests: a fake of Android's authentication and a controllable clock. */
    @Volatile var deviceAuth: ((androidx.fragment.app.FragmentActivity) -> DeviceAuth)? = null
    @Volatile var clock: (() -> Long)? = null
}

/**
 * App state: the agency's books, the lock, and a revision counter that refreshes screens after a change.
 * All engine work happens on the main thread (one Agency, one thread), as in the iPhone app.
 */
class AppModel(app: Application) : AndroidViewModel(app) {
    val lock = AppLock(AppConfig.clock ?: { android.os.SystemClock.elapsedRealtime() }, unlockedAtStart = AppConfig.overrides?.skipLock == true)
    var agency by mutableStateOf<Agency?>(null)
        private set
    var store: AgencyStore? = null
        private set
    var startupProblem by mutableStateOf<String?>(null)
    var corrupted by mutableStateOf(false)
    var revision by mutableIntStateOf(0)
        private set
    /** A refusal or warning to show after an action, with "Save anyway" for the warnings the desktop also lets users override. */
    class Problem(val title: String, val message: String, val retry: (() -> Unit)?)
    var problem by mutableStateOf<Problem?>(null)
    /** The document shown on the PDF screen. */
    var pdf by mutableStateOf<PdfFile?>(null)

    val skipLock: Boolean get() = AppConfig.overrides?.skipLock == true

    fun start() {
        if (store != null) return
        try {
            val o = AppConfig.overrides
            val ctx = getApplication<Application>()
            val s = if (o != null) AgencyStore(o.root, o.key, AndroidSqlDb.factory)
                    else AgencyStore(File(ctx.filesDir, "AirZelo"), DeviceKey.loadOrCreate(File(ctx.noBackupFilesDir, "keys")), AndroidSqlDb.factory)
            store = s
            agency = s.open()
        } catch (e: AgencyStore.StoreError.Corrupted) {
            corrupted = true
            startupProblem = e.message
        } catch (e: Exception) {
            startupProblem = describe(e)
        }
    }

    fun recover() {
        val s = store ?: return
        try {
            s.recoverFromLatestBackup()
            agency = s.open()
            corrupted = false
            startupProblem = null
            changed()
        } catch (e: Exception) {
            startupProblem = describe(e)
        }
    }

    fun reopen() {
        val s = store ?: return
        try { agency = s.open(); changed() } catch (e: Exception) { startupProblem = describe(e) }
    }

    fun changed() { revision++ }

    /**
     * Runs a service call with [fields]; on success calls [onSuccess]. A refusal is shown to the user instead of crashing,
     * and an overridable warning (duplicate PNR, possible duplicate payment ...) offers to save again with its flag set.
     */
    fun <T> attempt(fields: Fields = emptyMap(), body: (Agency, Fields) -> T, onSuccess: (T) -> Unit = {}) {
        val a = agency ?: return
        val result = try {
            body(a, fields)
        } catch (e: AZError) {
            val flag = OVERRIDABLE[e.code]
            problem = Problem(if (e.kind == AZError.Kind.CONFLICT) "Please check" else "Not saved", e.message,
                flag?.let { { attempt(fields + (it to JValue.Bool(true)), body, onSuccess) } })
            return
        } catch (e: Exception) {
            problem = Problem("Not saved", describe(e), null)
            return
        }
        problem = null
        onSuccess(result)
    }

    /** Shows a message from a non-service action (backup, export, file access). */
    fun fail(e: Throwable) { problem = Problem("Something needs attention", describe(e), null) }

    // ---- backups (the Backup screen and the workflow test use these)

    /** A device backup, encrypted with this phone's key and verified after writing. */
    fun backupNow(): AgencyStore.BackupInfo? = try { store!!.createBackup().also { changed() } } catch (e: Exception) { fail(e); null }

    /** A portable export, encrypted with [passphrase] (opens on another phone; decrypted, it is a desktop backup). */
    fun exportBytes(passphrase: String): ByteArray? = try { store!!.exportBackup(passphrase) } catch (e: Exception) { fail(e); null }

    /** Restores a phone backup or a desktop .zip after checking it completely; the current books are saved first. */
    fun restoreBytes(data: ByteArray, passphrase: String?): Boolean = try {
        store!!.restore(data, passphrase?.takeIf { it.isNotEmpty() })
        agency = store!!.open()
        changed()
        true
    } catch (e: Exception) {
        fail(e)
        runCatching { if (agency == null || store?.agency == null) agency = store!!.open() }
        false
    }

    /** Leaving the app: start the lock timeout and make the automatic backup when it is due. */
    fun onBackground() {
        if (!skipLock) lock.onBackground()
        runCatching { store?.autoBackupIfDue() }
    }

    override fun onCleared() {
        store?.close()
    }

    companion object {
        val OVERRIDABLE = mapOf("duplicate_pnr" to "force_duplicate", "possible_duplicate" to "force_duplicate",
            "duplicate_customer" to "force_duplicate", "confirm_opening_change" to "confirm_opening_change")

        fun describe(e: Throwable): String = when (e) {
            is AZError -> e.message
            is AgencyStore.StoreError -> e.message ?: "The backup could not be used."
            is BackupCrypto.CryptoError -> e.message ?: "The backup could not be opened."
            is Zip.ZipError -> e.message ?: "The file is not a valid backup."
            // SQLite's own text and the start of the statement; bound values (customer data) are never part of it
            is SqlException -> "Database error: " + (e.message ?: "unknown")
            else -> "Something went wrong: ${e.javaClass.simpleName}"  // no details: they could include customer data
        }
    }
}
