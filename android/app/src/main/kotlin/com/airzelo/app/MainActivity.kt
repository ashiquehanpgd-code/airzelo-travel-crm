package com.airzelo.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity

/**
 * The only activity. FLAG_SECURE keeps the books out of screenshots, screen recordings and the recent-apps preview.
 * The app opens only after Android confirms the phone's owner (fingerprint, face, or the phone's PIN, pattern or
 * password, through BiometricPrompt) and locks again after [AppLock.DEFAULT_TIMEOUT_MS] away from the screen.
 */
class MainActivity : FragmentActivity() {
    private val model: AppModel by viewModels()
    private val auth: DeviceAuth by lazy { AppConfig.deviceAuth?.invoke(this) ?: BiometricDeviceAuth(this) }

    companion object {
        /** Set while the app opens another app on purpose (file picker, share sheet, Settings), so that is not "leaving". */
        @Volatile var expectingExternal = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        model.start()
        setContent { AirZeloTheme { AppRoot(model, onUnlock = { model.lock.requestUnlock(auth) }) } }
    }

    override fun onResume() {
        super.onResume()
        expectingExternal = false
        if (!model.skipLock) model.lock.onForeground(auth)
    }

    override fun onStop() {
        super.onStop()
        if (!expectingExternal && !isChangingConfigurations) model.onBackground()
    }
}
