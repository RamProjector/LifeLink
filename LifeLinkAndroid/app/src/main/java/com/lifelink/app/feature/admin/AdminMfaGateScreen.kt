package com.lifelink.app.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.auth.AuthSession
import com.lifelink.app.core.auth.SupabaseAuthRepository
import com.lifelink.app.core.auth.SupabaseMfaEnrollment
import com.lifelink.app.core.auth.SupabaseMfaFactor
import com.lifelink.app.data.remote.AdminMfaEventRequest
import com.lifelink.app.data.remote.LifeLinkApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

private fun verifiedTotpFactors(factors: List<SupabaseMfaFactor>): List<SupabaseMfaFactor> =
    factors.filter { it.factorType == "totp" && it.status == "verified" }

internal enum class AdminMfaStep {
    LOADING,
    ENROLL_PRIMARY,
    ENROLL_BACKUP,
    VERIFY_ENROLLMENT,
    CHALLENGE,
    AUDIT_PENDING,
    RELOAD_FACTORS,
    CONFIRM_ACCESS,
}

internal fun nextAdminMfaStep(assuranceLevel: String, verifiedFactorCount: Int): AdminMfaStep =
    when {
        assuranceLevel == "aal2" && verifiedFactorCount >= 2 -> AdminMfaStep.CONFIRM_ACCESS
        verifiedFactorCount <= 0 -> AdminMfaStep.ENROLL_PRIMARY
        assuranceLevel == "aal2" -> AdminMfaStep.ENROLL_BACKUP
        else -> AdminMfaStep.CHALLENGE
    }

@Suppress("LongMethod", "CyclomaticComplexMethod", "SwallowedException")
@Composable
fun AdminMfaGateScreen(
    repository: SupabaseAuthRepository,
    api: LifeLinkApi,
    session: AuthSession,
    onVerified: () -> Unit,
    onSignOut: () -> Unit,
) {
    var activeSession by remember(session.userId) { mutableStateOf(session) }
    var factors by remember(session.userId) { mutableStateOf<List<SupabaseMfaFactor>>(emptyList()) }
    var step by remember(session.userId) { mutableStateOf(AdminMfaStep.LOADING) }
    var enrollment by remember(session.userId) { mutableStateOf<SupabaseMfaEnrollment?>(null) }
    var factorName by remember(session.userId) { mutableStateOf("Primary authenticator") }
    var code by remember { mutableStateOf("") }
    var selectedFactorId by remember { mutableStateOf("") }
    var pendingAuditType by remember { mutableStateOf<String?>(null) }
    var pendingAuditCount by remember { mutableStateOf(0) }
    var enrollmentAuditPending by remember { mutableStateOf(false) }
    var backupStoredSeparately by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val verifiedFactors = verifiedTotpFactors(factors)

    suspend fun confirmAdminAccess() {
        step = AdminMfaStep.CONFIRM_ACCESS
        try {
            val response = api.adminMe()
            if (response.isSuccessful && response.body()?.isAdmin == true) {
                error = null
                onVerified()
            } else {
                error = "Authenticator verification succeeded, but administrator access could not be confirmed. " +
                    "Retry or contact the system owner if this continues."
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: IOException) {
            error = "Authenticator verification succeeded, but LifeLink could not confirm admin access. Retry."
        }
    }

    suspend fun recordPendingAudit() {
        val eventType = pendingAuditType ?: return
        val response = try {
            api.adminMfaEvent(
                AdminMfaEventRequest(
                    eventType = eventType,
                    verifiedFactorCount = pendingAuditCount,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: IOException) {
            step = AdminMfaStep.AUDIT_PENDING
            error = "Your authenticator is verified, but LifeLink could not save its security audit receipt. Retry."
            return
        }
        if (!response.isSuccessful) {
            step = AdminMfaStep.AUDIT_PENDING
            error = "Your authenticator is verified, but LifeLink could not save its security audit receipt. Retry before continuing."
            return
        }
        pendingAuditType = null
        error = null
        if (pendingAuditCount < 2) {
            step = AdminMfaStep.ENROLL_BACKUP
            factorName = "Backup authenticator"
            backupStoredSeparately = false
        } else {
            confirmAdminAccess()
        }
    }

    suspend fun loadFactorInventory() {
        repository.listMfaFactors(activeSession)
            .onSuccess { listed ->
                val refreshedFactors = listed.filter {
                    it.factorType == "totp" && (it.status == "verified" || it.status == "unverified")
                }
                factors = refreshedFactors
                val verified = verifiedTotpFactors(refreshedFactors)
                if (enrollmentAuditPending) {
                    if (verified.isEmpty()) {
                        step = AdminMfaStep.RELOAD_FACTORS
                        error = "Supabase verified the authenticator, but it is not yet visible in the factor inventory. Retry the check."
                        return@onSuccess
                    }
                    pendingAuditType = if (verified.size >= 2) "backup_factor_verified" else "factor_enrollment_verified"
                    pendingAuditCount = verified.size
                    enrollmentAuditPending = false
                    step = AdminMfaStep.AUDIT_PENDING
                    recordPendingAudit()
                    return@onSuccess
                }
                when (nextAdminMfaStep(repository.assuranceLevel(activeSession.accessToken), verified.size)) {
                    AdminMfaStep.CONFIRM_ACCESS -> confirmAdminAccess()
                    AdminMfaStep.ENROLL_PRIMARY -> {
                        step = AdminMfaStep.ENROLL_PRIMARY
                        factorName = "Primary authenticator"
                    }
                    AdminMfaStep.ENROLL_BACKUP -> {
                        step = AdminMfaStep.ENROLL_BACKUP
                        factorName = "Backup authenticator"
                    }
                    AdminMfaStep.CHALLENGE -> {
                        step = AdminMfaStep.CHALLENGE
                        selectedFactorId = verified.first().id
                    }
                    else -> {
                        step = AdminMfaStep.RELOAD_FACTORS
                        error = "LifeLink could not determine the next authenticator step. Retry the check."
                    }
                }
            }
            .onFailure {
                error = it.message ?: "LifeLink could not load your authenticator devices."
                step = AdminMfaStep.RELOAD_FACTORS
            }
    }

    LaunchedEffect(session.userId) {
        busy = true
        loadFactorInventory()
        busy = false
    }

    fun startEnrollment() {
        scope.launch {
            if (step == AdminMfaStep.ENROLL_BACKUP && !backupStoredSeparately) {
                error = "Confirm that the backup authenticator is stored on a separate device or in a separately protected vault."
                return@launch
            }
            busy = true
            error = null
            repository.enrollMfaTotp(activeSession, factorName)
                .onSuccess {
                    enrollment = it
                    code = ""
                    step = AdminMfaStep.VERIFY_ENROLLMENT
                }
                .onFailure { error = it.message ?: "Authenticator setup could not be started." }
            busy = false
        }
    }

    fun verifyCode() {
        scope.launch {
            if (!code.matches(Regex("^[0-9]{6}$"))) {
                error = "Enter the current six-digit authenticator code."
                return@launch
            }
            val enrolling = step == AdminMfaStep.VERIFY_ENROLLMENT
            val factorId = if (enrolling) enrollment?.id.orEmpty() else selectedFactorId
            if (factorId.isBlank()) {
                error = "No verified authenticator is available. Contact the system owner; password reset cannot bypass MFA."
                return@launch
            }
            busy = true
            error = null
            val challenge = repository.challengeMfa(activeSession, factorId)
            challenge.onSuccess { challengeId ->
                repository.verifyMfaTotp(activeSession, factorId, challengeId, code)
                    .onSuccess { refreshed ->
                        activeSession = refreshed
                        code = ""
                        enrollment = null
                        enrollmentAuditPending = enrolling
                        loadFactorInventory()
                    }
                    .onFailure { error = it.message ?: "The authenticator code was not accepted." }
            }.onFailure { error = it.message ?: "An authenticator challenge could not be created." }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Administrator security", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Verify your authenticator before opening the LifeLink admin console. Regular requester and donor sign-in is unchanged.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy || step == AdminMfaStep.LOADING) {
            CircularProgressIndicator()
        } else {
            when (step) {
                AdminMfaStep.ENROLL_PRIMARY, AdminMfaStep.ENROLL_BACKUP -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                if (step == AdminMfaStep.ENROLL_PRIMARY) {
                                    "Set up your primary authenticator"
                                } else {
                                    "Add your backup authenticator"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Use a TOTP authenticator such as Google Authenticator, 1Password, or another " +
                                    "compatible app. Keep the backup separately from your primary device.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = factorName,
                                onValueChange = { factorName = it.take(64) },
                                label = { Text("Authenticator name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (step == AdminMfaStep.ENROLL_BACKUP) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(
                                        checked = backupStoredSeparately,
                                        onCheckedChange = { backupStoredSeparately = it },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("I have stored this backup separately.")
                                }
                            }
                            Button(onClick = ::startEnrollment, enabled = !busy && factorName.isNotBlank()) {
                                Text("Generate setup key")
                            }
                        }
                    }
                }
                AdminMfaStep.VERIFY_ENROLLMENT -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Add this authenticator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Enter this setup key manually in your authenticator app. It is shown only during " +
                                    "setup; do not send it to anyone.",
                            )
                            Text(
                                enrollment?.totp?.secret.orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                                label = { Text("Six-digit code") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(onClick = ::verifyCode, enabled = !busy && code.length == 6) { Text("Verify authenticator") }
                        }
                    }
                }
                AdminMfaStep.CHALLENGE -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Verify your authenticator",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("Choose one of your registered authenticators and enter its current code.")
                            verifiedFactors.forEach { factor ->
                                OutlinedButton(
                                    onClick = { selectedFactorId = factor.id },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        (if (selectedFactorId == factor.id) "Selected · " else "") +
                                            (factor.friendlyName?.takeIf { it.isNotBlank() } ?: "Authenticator"),
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                                label = { Text("Six-digit code") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(onClick = ::verifyCode, enabled = !busy && selectedFactorId.isNotBlank() && code.length == 6) {
                                Text("Verify and continue")
                            }
                        }
                    }
                }
                AdminMfaStep.CONFIRM_ACCESS -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Confirming administrator access", style = MaterialTheme.typography.titleMedium)
                            Text("Your authenticator is verified. LifeLink is checking the server-side administrator session.")
                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        confirmAdminAccess()
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Text("Retry access check")
                            }
                        }
                    }
                }
                AdminMfaStep.RELOAD_FACTORS -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Authenticator devices could not be loaded", style = MaterialTheme.typography.titleMedium)
                            Text("No administrator access is granted until LifeLink can confirm your registered authenticator devices.")
                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        error = null
                                        loadFactorInventory()
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                            ) { Text("Retry authenticator check") }
                        }
                    }
                }
                AdminMfaStep.AUDIT_PENDING -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Save security audit receipt",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("The authenticator was verified. Retry the audit receipt before continuing.")
                            Button(onClick = {
                                scope.launch {
                                    busy = true
                                    runCatching { recordPendingAudit() }
                                        .onFailure {
                                            error = "The audit receipt could not be saved. " +
                                                "Retry when the connection is available."
                                        }
                                    busy = false
                                }
                            }, enabled = !busy) { Text("Retry audit receipt") }
                        }
                    }
                }
                AdminMfaStep.LOADING -> Unit
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onSignOut, enabled = !busy) { Text("Sign out") }
    }
}
