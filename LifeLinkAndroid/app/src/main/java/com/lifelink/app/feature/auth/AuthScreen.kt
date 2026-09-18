package com.lifelink.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.regex.Pattern

private val emailPattern = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

@Composable
fun AuthScreen(state: AuthState, onSignIn: (String, String) -> Unit, onSignUp: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var createAccount by remember { mutableStateOf(false) }
    val busy = state is AuthState.Loading

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("LifeLink", style = MaterialTheme.typography.headlineLarge)
        Text(if (createAccount) "Create a coordinator or donor account" else "Sign in to continue", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        if (state is AuthState.Error) Text(state.message, color = MaterialTheme.colorScheme.error)
        if (state is AuthState.EmailConfirmationRequired) {
            Text(
                "Account created. Check your email and click the Supabase confirmation link, then select Sign in.",
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (busy) CircularProgressIndicator()
        else Button(
            onClick = { if (createAccount) onSignUp(email.trim(), password) else onSignIn(email.trim(), password) },
            enabled = emailPattern.matcher(email.trim()).matches() && password.length >= 6,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (createAccount) "Create account" else "Sign in") }
        OutlinedButton(onClick = { createAccount = !createAccount }, modifier = Modifier.fillMaxWidth()) {
            Text(if (createAccount) "I already have an account" else "Create a new account")
        }
        Text("Use a complete email such as name@gmail.com. Your account is used to protect requests, donor profiles, and contact actions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
