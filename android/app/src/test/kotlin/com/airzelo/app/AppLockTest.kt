package com.airzelo.app

import android.provider.Settings
import android.view.WindowManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * The app lock through the real MainActivity, with Android's authentication replaced by a fake (Robolectric has no
 * BiometricPrompt UI). The real prompt with a real device PIN is exercised on emulators by android/tools/device_smoke.py.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppLockTest {
    /** What Android would report, and the prompts the app asked for (resolved by the test). */
    class FakeAuth : DeviceAuth {
        var secure = true
        var available: Int? = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        val requests = ArrayList<Int>()
        private var pending: Pair<() -> Unit, (Int, CharSequence) -> Unit>? = null
        override fun isDeviceSecure() = secure
        override fun availableAuthenticators() = available
        override fun authenticate(authenticators: Int, onSuccess: () -> Unit, onError: (Int, CharSequence) -> Unit) {
            requests.add(authenticators)
            pending = onSuccess to onError
        }
        fun succeed() { val p = pending!!; pending = null; p.first() }
        fun fail(code: Int, text: String) { val p = pending!!; pending = null; p.second(code, text) }
    }

    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var root: File
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val auth = FakeAuth()
    private var now = 1_000_000L

    @Before fun setUp() {
        root = Files.createTempDirectory("az-lock-").toFile()
        AppConfig.overrides = AppConfig.Overrides(root, ByteArray(32).also { SecureRandom().nextBytes(it) }, skipLock = false)
        AppConfig.deviceAuth = { auth }
        AppConfig.clock = { now }
    }

    @After fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        AppConfig.overrides = null; AppConfig.deviceAuth = null; AppConfig.clock = null
        MainActivity.expectingExternal = false
        root.deleteRecursively()
    }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java); compose.waitForIdle() }
    private fun state(): AppLock.State { var s: AppLock.State? = null; scenario.onActivity { s = ViewModelProvider(it)[AppModel::class.java].lock.state }; return s!! }
    private fun onMain(body: () -> Unit) { scenario.onActivity { body() }; compose.waitForIdle() }
    private fun booksHidden() = assertEquals("no screen of the books is composed", 0, compose.onAllNodesWithTag("dashboard").fetchSemanticsNodes().size)

    @Test fun opensOnlyAfterSuccessfulUnlock() {
        launch()
        assertEquals("prompt asked for fingerprint/face or the screen lock", listOf(BIOMETRIC_STRONG or DEVICE_CREDENTIAL), auth.requests)
        compose.onNodeWithTag("lock-screen").assertIsDisplayed()
        booksHidden()
        onMain { auth.succeed() }
        assertEquals(AppLock.State.UNLOCKED, state())
        compose.onNodeWithTag("dashboard").assertIsDisplayed()
    }

    @Test fun failedOrCancelledAuthenticationKeepsTheBooksClosed() {
        launch()
        onMain { auth.fail(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts. Try again later.") }
        assertEquals(AppLock.State.LOCKED, state())
        compose.onNodeWithTag("lock-message").assertTextContains("Too many attempts", substring = true)
        booksHidden()
        // coming back to the screen after a failure does not reopen the prompt by itself: the user taps Unlock
        scenario.moveToState(Lifecycle.State.STARTED); scenario.moveToState(Lifecycle.State.RESUMED); compose.waitForIdle()
        assertEquals(1, auth.requests.size)
        compose.onNodeWithTag("unlock").performClick()
        assertEquals(2, auth.requests.size)
        onMain { auth.fail(BiometricPrompt.ERROR_USER_CANCELED, "Cancelled") }
        compose.onNodeWithTag("lock-message").assertTextContains("Unlock to open your books.")
        booksHidden()
        compose.onNodeWithTag("unlock").performClick()
        onMain { auth.succeed() }
        compose.onNodeWithTag("dashboard").assertIsDisplayed()
    }

    @Test fun locksAgainAfterThePrivacyTimeoutInTheBackground() {
        launch()
        onMain { auth.succeed() }
        // briefly away (under the timeout): still open, no new prompt
        scenario.moveToState(Lifecycle.State.CREATED)
        now += AppLock.DEFAULT_TIMEOUT_MS - 1_000
        scenario.moveToState(Lifecycle.State.RESUMED); compose.waitForIdle()
        assertEquals(AppLock.State.UNLOCKED, state())
        assertEquals(1, auth.requests.size)
        // away for the timeout: locked, and the prompt opens again
        scenario.moveToState(Lifecycle.State.CREATED)
        now += AppLock.DEFAULT_TIMEOUT_MS
        scenario.moveToState(Lifecycle.State.RESUMED); compose.waitForIdle()
        assertEquals(AppLock.State.AUTHENTICATING, state())
        assertEquals(2, auth.requests.size)
        compose.onNodeWithTag("lock-screen").assertIsDisplayed()
        booksHidden()
        onMain { auth.succeed() }
        compose.onNodeWithTag("dashboard").assertIsDisplayed()
    }

    @Test fun deviceCredentialFallbackWithoutStrongBiometrics() {
        auth.available = DEVICE_CREDENTIAL  // no fingerprint/face enrolled, or only weak ones
        launch()
        assertEquals(listOf(DEVICE_CREDENTIAL), auth.requests)
        onMain { auth.succeed() }
        compose.onNodeWithTag("dashboard").assertIsDisplayed()
    }

    @Test fun noScreenLockShowsSetupInstructionsAndNeverOpensTheBooks() {
        auth.secure = false
        launch()
        assertEquals(AppLock.State.NO_SCREEN_LOCK, state())
        assertTrue("no prompt without a screen lock", auth.requests.isEmpty())
        compose.onNodeWithTag("no-screen-lock").assertIsDisplayed()
        booksHidden()
        compose.onNodeWithTag("open-security-settings").performClick()
        scenario.onActivity { assertEquals(Settings.ACTION_SECURITY_SETTINGS, shadowOf(it).nextStartedActivity.action) }
        compose.onNodeWithTag("check-again").performClick()
        assertEquals(AppLock.State.NO_SCREEN_LOCK, state())  // still none: still closed
        auth.secure = true                                  // the owner set a PIN
        compose.onNodeWithTag("check-again").performClick()
        assertEquals(listOf(BIOMETRIC_STRONG or DEVICE_CREDENTIAL), auth.requests)
        onMain { auth.succeed() }
        compose.onNodeWithTag("dashboard").assertIsDisplayed()
    }

    @Test fun windowIsSecure() {
        launch()
        scenario.onActivity { assertTrue("FLAG_SECURE", it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
    }
}
