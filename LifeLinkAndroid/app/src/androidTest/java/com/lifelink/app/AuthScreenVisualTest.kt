package com.lifelink.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.feature.auth.AuthScreen
import com.lifelink.app.feature.auth.AuthState
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthScreenVisualTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun authScreenRendersAndCapturesVisualEvidence() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme {
                    AuthScreen(
                        state = AuthState.SignedOut,
                        onSignIn = { _, _ -> },
                        onSignUp = { _, _ -> },
                        onPasswordReset = { },
                        onResendConfirmation = { },
                        onUpdatePassword = { _, _ -> }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Welcome back. Sign in to continue.").assertIsDisplayed()
        composeRule.onNodeWithText("Email address").assertIsDisplayed()
        composeRule.onNodeWithText("Forgot password?").assertIsDisplayed()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.takeScreenshot(File("/sdcard/lifelink-auth-screen.png"))) {
            "Could not capture the auth-screen visual evidence screenshot"
        }
    }
}
