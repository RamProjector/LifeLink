package com.lifelink.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.util.regex.Pattern

private val emailPattern = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

// Password-strength scoring thresholds (see passwordStrength).
private const val MIN_PASSWORD_LENGTH = 6
private const val STRONG_PASSWORD_LENGTH = 10
private const val MAX_PASSWORD_SCORE = 4

/**
 * Scores a candidate password from 0 (empty) to 4 (strong) so the UI can give
 * live, non-blocking feedback while the user types a new password.
 */
private fun passwordStrength(password: String): Int {
    if (password.isEmpty()) return 0
    var score = 0
    if (password.length >= MIN_PASSWORD_LENGTH) score++
    if (password.length >= STRONG_PASSWORD_LENGTH) score++
    if (password.any { it.isDigit() }) score++
    if (password.any { it.isUpperCase() } || password.any { !it.isLetterOrDigit() }) score++
    return score.coerceIn(0, MAX_PASSWORD_SCORE)
}

/**
 * Renders sign-in, account creation, and password recovery forms for [state].
 * Validates form input before invoking the supplied callbacks and requires agreement to the privacy
 * policy and terms before signup, with dialogs for reading both documents.
 *
 * Accessibility: fields expose autofill content types so password managers can fill them, the
 * primary action is reachable from the keyboard via IME actions, and status messages are announced
 * through a polite live region.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun AuthScreen(
    state: AuthState,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onPasswordReset: (String) -> Unit,
    onResendConfirmation: (String) -> Unit,
    onUpdatePassword: (String, String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var createAccount by rememberSaveable { mutableStateOf(false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showConfirmPassword by rememberSaveable { mutableStateOf(false) }
    var recoveryMode by rememberSaveable { mutableStateOf(false) }
    var acceptedLegal by rememberSaveable { mutableStateOf(false) }
    var legalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    val focusManager = LocalFocusManager.current
    val busy = state is AuthState.Loading
    val resetReady = state is AuthState.PasswordResetReady
    val emailValid = emailPattern.matcher(email.trim()).matches()
    val passwordValid = password.length >= 6
    val passwordsMatch = !(createAccount || resetReady) || password == confirmPassword
    // Remind the user to accept the legal documents only once the rest of the form is otherwise valid.
    val showLegalReminder = createAccount && !acceptedLegal && !busy && emailValid && passwordValid && passwordsMatch
    val canSubmit =
        if (resetReady) {
            passwordValid && passwordsMatch && !busy
        } else {
            emailValid &&
                (recoveryMode || (passwordValid && passwordsMatch && (!createAccount || acceptedLegal))) &&
                !busy
        }

    val submit = {
        if (resetReady) {
            onUpdatePassword((state as AuthState.PasswordResetReady).accessToken, password)
        } else if (recoveryMode) {
            onPasswordReset(email.trim())
        } else if (createAccount) {
            onSignUp(email.trim(), password)
        } else {
            onSignIn(email.trim(), password)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxSize()
                        .statusBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier =
                            Modifier
                                .size(48.dp)
                                .background(
                                    brush =
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary,
                                            ),
                                        ),
                                    shape = CircleShape,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "LifeLink",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "Connecting donors and requests, safely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        if (resetReady) {
                            "Create a new password"
                        } else if (recoveryMode) {
                            "Reset your password"
                        } else if (createAccount) {
                            "Create your account"
                        } else {
                            "Sign in"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        if (resetReady) {
                            "Your recovery link is confirmed. Enter and confirm your new password below."
                        } else if (recoveryMode) {
                            "We\u2019ll email a secure password-reset link."
                        } else if (createAccount) {
                            "Use an email you can access. Check your inbox for a confirmation link."
                        } else {
                            "Use the email and password associated with your LifeLink account."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (!resetReady) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            enabled = !busy,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .semantics { contentType = ContentType.Username },
                            label = { Text("Email address") },
                            placeholder = { Text("name@gmail.com") },
                            singleLine = true,
                            isError = email.isNotEmpty() && !emailValid,
                            supportingText =
                                if (email.isNotEmpty() && !emailValid) {
                                    { Text("Enter a complete email, such as name@gmail.com") }
                                } else {
                                    null
                                },
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next,
                                ),
                            keyboardActions =
                                KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                ),
                            trailingIcon =
                                if (email.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { email = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear email address")
                                        }
                                    }
                                } else {
                                    null
                                },
                        )
                    }

                    if (!recoveryMode || resetReady) {
                        PasswordField(
                            value = password,
                            onValueChange = { password = it },
                            config =
                                PasswordFieldConfig(
                                    label = if (resetReady) "New password" else "Password",
                                    visible = showPassword,
                                    onToggleVisibility = { showPassword = !showPassword },
                                    isError = password.isNotEmpty() && !passwordValid,
                                    supportingText = if (password.isNotEmpty() && !passwordValid) "Use at least 6 characters." else null,
                                    enabled = !busy,
                                    contentType = if (createAccount || resetReady) ContentType.NewPassword else ContentType.Password,
                                    imeAction = if ((!recoveryMode && createAccount) || resetReady) ImeAction.Next else ImeAction.Done,
                                    onImeAction = {
                                        if ((!recoveryMode && createAccount) || resetReady) {
                                            focusManager.moveFocus(FocusDirection.Down)
                                        } else if (canSubmit) {
                                            focusManager.clearFocus()
                                            submit()
                                        }
                                    },
                                ),
                        )
                    }

                    if ((!recoveryMode && createAccount) || resetReady) {
                        PasswordStrengthMeter(password)
                        PasswordField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            config =
                                PasswordFieldConfig(
                                    label = if (resetReady) "Confirm new password" else "Confirm password",
                                    visible = showConfirmPassword,
                                    onToggleVisibility = { showConfirmPassword = !showConfirmPassword },
                                    isError = confirmPassword.isNotEmpty() && !passwordsMatch,
                                    supportingText =
                                        if (confirmPassword.isNotEmpty() &&
                                            !passwordsMatch
                                        ) {
                                            "Passwords do not match."
                                        } else {
                                            null
                                        },
                                    enabled = !busy,
                                    contentType = ContentType.NewPassword,
                                    imeAction = ImeAction.Done,
                                    onImeAction = {
                                        if (canSubmit) {
                                            focusManager.clearFocus()
                                            submit()
                                        }
                                    },
                                ),
                        )
                    }

                    if (createAccount && !recoveryMode && !resetReady) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.toggleable(
                                    value = acceptedLegal,
                                    enabled = !busy,
                                    role = Role.Checkbox,
                                    onValueChange = { acceptedLegal = it },
                                ),
                        ) {
                            Checkbox(checked = acceptedLegal, onCheckedChange = null, enabled = !busy)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "I agree to the LifeLink Privacy Policy and Terms and Conditions.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = {
                                            legalDocument = PrivacyPolicyPhilippines
                                        },
                                        contentPadding =
                                            androidx.compose.foundation.layout
                                                .PaddingValues(0.dp),
                                    ) {
                                        Text("Privacy Policy")
                                    }
                                    Text(
                                        "and",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(
                                        onClick = {
                                            legalDocument = TermsAndConditionsPhilippines
                                        },
                                        contentPadding =
                                            androidx.compose.foundation.layout
                                                .PaddingValues(0.dp),
                                    ) {
                                        Text("Terms")
                                    }
                                }
                            }
                        }
                        if (showLegalReminder) {
                            Text(
                                "Accept the Privacy Policy and Terms to continue.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    when (state) {
                        is AuthState.Error -> MessageCard(state.message, isError = true)
                        is AuthState.Message -> MessageCard(state.text, isError = state.isError)
                        AuthState.SessionExpired ->
                            MessageCard(
                                "Your session expired. Please sign in again to protect your requests and contact details.",
                                isError = true,
                            )
                        is AuthState.PasswordResetReady ->
                            MessageCard(
                                "Email confirmed. Your password-reset link is valid. Set a new password below.",
                                isError = false,
                            )
                        AuthState.PasswordResetComplete ->
                            MessageCard(
                                "Password updated successfully. Return to sign in with your new password.",
                                isError = false,
                            )
                        AuthState.EmailConfirmationRequired ->
                            MessageCard(
                                "Account created. Check your inbox and click the confirmation link, then choose Sign in.",
                                isError = false,
                            )
                        else -> Unit
                    }

                    Button(
                        onClick = submit,
                        enabled = canSubmit,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .semantics { if (busy) stateDescription = "Submitting" },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(22.dp).height(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Working\u2026")
                        } else {
                            Text(
                                if (resetReady) {
                                    "Update password"
                                } else if (recoveryMode) {
                                    "Send reset email"
                                } else if (createAccount) {
                                    "Create account"
                                } else {
                                    "Sign in"
                                },
                            )
                        }
                    }

                    if (!createAccount && !recoveryMode && !resetReady) {
                        TextButton(onClick = { recoveryMode = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Forgot password?")
                        }
                    }
                    if (recoveryMode &&
                        !resetReady
                    ) {
                        TextButton(onClick = {
                            recoveryMode = false
                        }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back to sign in") }
                    }
                    if (state is AuthState.EmailConfirmationRequired) {
                        OutlinedButton(
                            onClick = { onResendConfirmation(email.trim()) },
                            enabled =
                                emailValid && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Resend confirmation email") }
                    }
                }
                if (!recoveryMode && !resetReady) {
                    TextButton(
                        onClick = {
                            createAccount = !createAccount
                            acceptedLegal = false
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(if (createAccount) "Already have an account? Sign in" else "Create an account")
                    }
                }
            }
        }
    }
    legalDocument?.let { document -> LegalDocumentDialog(document = document, onDismiss = { legalDocument = null }) }
}

/**
 * Groups the non-value inputs of [PasswordField] so the composable stays within the
 * detekt parameter-count budget while keeping each call site readable.
 */
private data class PasswordFieldConfig(
    val label: String,
    val visible: Boolean,
    val onToggleVisibility: () -> Unit,
    val isError: Boolean,
    val supportingText: String?,
    val contentType: ContentType,
    val imeAction: ImeAction,
    val onImeAction: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * Displays a password field with caller-controlled visibility, validation feedback, and a visibility toggle.
 * The visibility toggle is the single trailing element, matching Material 3 guidance for text fields.
 */
@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, config: PasswordFieldConfig) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = config.enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { this.contentType = config.contentType },
        label = { Text(config.label) },
        singleLine = true,
        isError = config.isError,
        supportingText = config.supportingText?.let { { Text(it) } },
        visualTransformation = if (config.visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = config.imeAction),
        keyboardActions =
            KeyboardActions(
                onNext = { config.onImeAction() },
                onDone = { config.onImeAction() },
            ),
        trailingIcon = {
            IconButton(onClick = config.onToggleVisibility) {
                Icon(
                    imageVector = if (config.visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (config.visible) "Hide ${config.label}" else "Show ${config.label}",
                )
            }
        },
    )
}

/**
 * Shows a four-segment strength meter with a short label while a new password is being typed.
 * The meter is decorative; the label carries the meaning for accessibility.
 */
@Composable
private fun PasswordStrengthMeter(password: String) {
    if (password.isEmpty()) return
    val score = passwordStrength(password)
    val label =
        when (score) {
            0, 1 -> "Weak password"
            2 -> "Fair password"
            3 -> "Good password"
            else -> "Strong password"
        }
    val activeColor =
        when (score) {
            0, 1 -> MaterialTheme.colorScheme.error
            2 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.secondary
        }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { index ->
                Surface(
                    modifier = Modifier.weight(1f).height(6.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (index < score) activeColor else MaterialTheme.colorScheme.surfaceVariant,
                ) {}
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = activeColor,
        )
    }
}

/**
 * Shows an authentication message using error or informational colors according to [isError].
 * The card is an assertive live region for errors and a polite one for status messages, so screen
 * readers announce errors immediately but do not interrupt for informational updates.
 */
@Composable
private fun MessageCard(message: String, isError: Boolean) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Error else Icons.Default.Info,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                message,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
