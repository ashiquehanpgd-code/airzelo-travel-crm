package com.airzelo.app

import android.app.KeyguardManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * How the phone confirms its owner. The app never sees a PIN, pattern, password or biometric: Android's own
 * screen (BiometricPrompt) asks for them and only reports success or failure. Tests replace this with a fake.
 */
interface DeviceAuth {
    /** A PIN, pattern or password is set on the phone (KeyguardManager.isDeviceSecure). */
    fun isDeviceSecure(): Boolean
    /** The authenticators to offer now (BiometricManager.Authenticators flags), or null when none can be used. */
    fun availableAuthenticators(): Int?
    fun authenticate(authenticators: Int, onSuccess: () -> Unit, onError: (code: Int, message: CharSequence) -> Unit)
}

/**
 * Android's implementation: fingerprint / face (class 3, "strong") or the phone's PIN, pattern or password.
 * BIOMETRIC_STRONG | DEVICE_CREDENTIAL needs Android 11 (API 30), which is the app's minSdk; on a phone without
 * strong biometrics, or with none enrolled, the prompt is the screen lock itself.
 */
class BiometricDeviceAuth(private val activity: FragmentActivity) : DeviceAuth {
    override fun isDeviceSecure() = activity.getSystemService(KeyguardManager::class.java).isDeviceSecure

    override fun availableAuthenticators(): Int? {
        val bm = BiometricManager.from(activity)
        return when {
            bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS -> BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            bm.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS -> DEVICE_CREDENTIAL
            else -> null
        }
    }

    override fun authenticate(authenticators: Int, onSuccess: () -> Unit, onError: (Int, CharSequence) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errorCode, errString)
            // onAuthenticationFailed (a finger or face not recognised) needs nothing: the prompt stays open for another try
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AirZelo")
            .setSubtitle("Use your fingerprint, face or screen lock")
            .setAllowedAuthenticators(authenticators)  // with DEVICE_CREDENTIAL there is no negative button
            .build())
    }
}

/**
 * The app lock. The books are hidden until the phone's owner is confirmed, and hidden again after the app has been
 * away from the screen for [timeoutMs] (or when Android ends the process). Without a screen lock the app does not
 * open: it explains how to set one.
 */
class AppLock(private val clock: () -> Long, val timeoutMs: Long = DEFAULT_TIMEOUT_MS, unlockedAtStart: Boolean = false) {
    enum class State { LOCKED, AUTHENTICATING, NO_SCREEN_LOCK, UNLOCKED }

    companion object {
        /** Away from the screen for this long (or longer) means unlocking again. */
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private val QUIET_ERRORS = setOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON)
    }

    var state by mutableStateOf(if (unlockedAtStart) State.UNLOCKED else State.LOCKED)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var leftAt: Long? = null
    /** After a failed or cancelled prompt the user taps Unlock to try again (returning from the prompt must not reopen it). */
    private var waitForTap = false

    val unlocked get() = state == State.UNLOCKED

    /** Shows Android's prompt, or the screen-lock instructions when the phone has no screen lock. */
    fun requestUnlock(auth: DeviceAuth) {
        if (state == State.UNLOCKED || state == State.AUTHENTICATING) return
        waitForTap = false
        if (!auth.isDeviceSecure()) { state = State.NO_SCREEN_LOCK; return }
        val allowed = auth.availableAuthenticators()
        if (allowed == null) {
            state = State.LOCKED
            message = "This phone cannot confirm your identity right now. Check its screen lock in Settings, then try again."
            return
        }
        state = State.AUTHENTICATING
        auth.authenticate(allowed, onSuccess = {
            state = State.UNLOCKED
            message = null
        }, onError = { code, text ->
            state = State.LOCKED
            waitForTap = true
            message = if (code in QUIET_ERRORS) "Unlock to open your books." else text.toString()
        })
    }

    /** The app left the screen (not for its own file picker or share sheet). */
    fun onBackground() {
        if (state == State.UNLOCKED) leftAt = clock()
        else if (state == State.LOCKED) waitForTap = false  // really left: ask again when it comes back
    }

    /** The app is back on screen: lock if it was away for the timeout or longer, then ask to unlock if locked. */
    fun onForeground(auth: DeviceAuth) {
        val t = leftAt
        leftAt = null
        if (state == State.UNLOCKED && t != null && clock() - t >= timeoutMs) state = State.LOCKED
        if (state == State.NO_SCREEN_LOCK || (state == State.LOCKED && !waitForTap)) requestUnlock(auth)
    }
}
