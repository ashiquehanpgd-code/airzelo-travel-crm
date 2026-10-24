package com.airzelo.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The only activity. FLAG_SECURE keeps the books out of screenshots, screen recordings and the recent-apps preview;
 * the app opens only after BiometricPrompt (fingerprint / face, or the phone's PIN, pattern or password) and locks
 * again whenever it leaves the screen.
 */
class MainActivity : FragmentActivity() {
    private val model: AppModel by viewModels()
    private var authenticating = false

    companion object {
        /** Set while the app opens another app on purpose (file picker, share sheet), so leaving the screen does not lock it. */
        @Volatile var expectingExternal = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        model.start()
        setContent { AirZeloTheme { AppRoot(model, onUnlock = ::unlock) } }
    }

    override fun onResume() {
        super.onResume()
        expectingExternal = false
        if (model.locked && !authenticating) unlock()
    }

    override fun onStop() {
        super.onStop()
        if (!expectingExternal && !authenticating && !isChangingConfigurations) model.onBackground()
    }

    fun unlock() {
        if (model.skipLock) { model.locked = false; return }
        val bm = BiometricManager.from(this)
        val allowed = when (bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                // no screen lock at all: there is nothing to authenticate against; warn plainly instead of pretending
                model.passcodeMissing = true
                model.locked = false
                return
            }
            else -> if (bm.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS) DEVICE_CREDENTIAL else {
                model.lockMessage = "This phone cannot confirm your identity right now. Check its screen lock in Settings."
                return
            }
        }
        authenticating = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticating = false
                model.lockMessage = null
                model.locked = false
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authenticating = false
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) model.lockMessage = errString.toString()
            }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AirZelo")
            .setSubtitle("Your agency's books are stored only on this phone")
            .setAllowedAuthenticators(allowed)
            .build())
    }
}
