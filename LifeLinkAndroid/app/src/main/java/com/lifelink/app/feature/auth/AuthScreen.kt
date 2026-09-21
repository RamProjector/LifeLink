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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.util.regex.Pattern

private val emailPattern = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

@Composable
fun AuthScreen(
    state: AuthState,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onPasswordReset: (String) -> Unit,
    onResendConfirmation: (String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var createAccount by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var recoveryMode by remember { mutableStateOf(false) }
    val busy = state is AuthState.Loading
    val emailValid = emailPattern.matcher(email.trim()).matches()
    val passwordValid = password.length >= 6
    val passwordsMatch = !createAccount || password == confirmPassword
    val canSubmit = emailValid && (recoveryMode || (passwordValid && passwordsMatch)) && !busy

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("LifeLink", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text(
                if (recoveryMode) "Recover access to your LifeLink account."
                else if (createAccount) "Join the community helping people find blood donors."
                else "Welcome back. Sign in to continue.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!recoveryMode) Row(
                modifier = Modifier.fillMaxWidth().background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp)
                ).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AuthModeButton("Sign in", !createAccount, Modifier.weight(1f)) { createAccount = false }
                AuthModeButton("Create account", createAccount, Modifier.weight(1f)) { createAccount = true }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        if (recoveryMode) "Reset your password" else if (createAccount) "Create your account" else "Sign in",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (recoveryMode) "We’ll email a secure password-reset link."
                        else if (createAccount) "Use an email address you can open. Supabase may send a confirmation link."
                        else "Use the email and password associated with your LifeLink account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email address") },
                        placeholder = { Text("name@gmail.com") },
                        singleLine = true,
                        isError = email.isNotEmpty() && !emailValid,
                        supportingText = if (email.isNotEmpty() && !emailValid) {
                            { Text("Enter a complete email, such as name@gmail.com") }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        trailingIcon = if (email.isNotEmpty() && !emailValid) {
                            { androidx.compose.material3.Icon(Icons.Default.Error, contentDescription = "Invalid email address") }
                        } else null
                    )

                    if (!recoveryMode) PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        visible = showPassword,
                        onToggleVisibility = { showPassword = !showPassword },
                        isError = password.isNotEmpty() && !passwordValid,
                        supportingText = if (password.isNotEmpty() && !passwordValid) "Use at least 6 characters." else null
                    )

                    if (!recoveryMode && createAccount) {
                        PasswordField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = "Confirm password",
                            visible = showConfirmPassword,
                            onToggleVisibility = { showConfirmPassword = !showConfirmPassword },
                            isError = confirmPassword.isNotEmpty() && !passwordsMatch,
                            supportingText = if (confirmPassword.isNotEmpty() && !passwordsMatch) "Passwords do not match." else null
                        )
                    }

                    when (state) {
                        is AuthState.Error -> MessageCard(state.message, isError = true)
                        is AuthState.Message -> MessageCard(state.text, isError = state.isError)
                        AuthState.SessionExpired -> MessageCard("Your session expired. Please sign in again to protect your requests and contact details.", isError = true)
                        is AuthState.PasswordResetConfirmed -> MessageCard(
                            if (state.email.isNullOrBlank()) "Email confirmed. Your password-reset link is valid. Return to sign in to continue."
                            else "Email confirmed for ${state.email}. Your password-reset link is valid. Return to sign in to continue.",
                            isError = false
                        )
                        AuthState.EmailConfirmationRequired -> MessageCard(
                            "Account created. Check your inbox and click the confirmation link, then choose Sign in.",
                            isError = false
                        )
                        else -> Unit
                    }

                    Button(
                        onClick = { if (recoveryMode) onPasswordReset(email.trim()) else if (createAccount) onSignUp(email.trim(), password) else onSignIn(email.trim(), password) },
                        enabled = canSubmit,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(22.dp).height(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Working…")
                        } else {
                            Text(if (recoveryMode) "Send reset email" else if (createAccount) "Create account" else "Sign in")
                        }
                    }

                    if (!createAccount && !recoveryMode) {
                        TextButton(onClick = { recoveryMode = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Forgot password?")
                        }
                    }
                    if (recoveryMode) TextButton(onClick = { recoveryMode = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back to sign in") }
                    if (state is AuthState.EmailConfirmationRequired) OutlinedButton(onClick = { onResendConfirmation(email.trim()) }, enabled = emailValid && !busy, modifier = Modifier.fillMaxWidth()) { Text("Resend confirmation email") }
                }
            }

            HorizontalDivider()
            Text(
                "Your account protects emergency requests, donor profiles, and contact actions. Never share your password.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AuthModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(9.dp)) { Text(label) }
    } else {
        TextButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    isError: Boolean,
    supportingText: String?
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isError) {
                    androidx.compose.material3.Icon(Icons.Default.Error, contentDescription = "Invalid $label")
                }
                TextButton(onClick = onToggleVisibility) { Text(if (visible) "Hide" else "Show") }
            }
        }
    )
}

@Composable
private fun MessageCard(message: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            message,
            modifier = Modifier.padding(14.dp),
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
