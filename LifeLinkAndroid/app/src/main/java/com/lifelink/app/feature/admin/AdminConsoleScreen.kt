package com.lifelink.app.feature.admin

import java.io.IOException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParseException
import com.lifelink.app.data.remote.AdminAuditEvent
import com.lifelink.app.data.remote.AdminDashboardResponse
import com.lifelink.app.data.remote.AdminUser
import com.lifelink.app.data.remote.AdminUserUpdateRequest
import com.lifelink.app.data.remote.LifeLinkApi
import kotlinx.coroutines.launch

private enum class AdminPage(val title: String) {
    OVERVIEW("Overview"),
    USERS("Users"),
    AUDIT("Audit"),
    ERRORS("Errors"),
}

@Composable
fun AdminConsoleScreen(
    api: LifeLinkApi,
    onSignOut: () -> Unit,
) {
    var page by remember { mutableStateOf(AdminPage.OVERVIEW) }
    var dashboard by remember { mutableStateOf<AdminDashboardResponse?>(null) }
    var users by remember { mutableStateOf<List<AdminUser>>(emptyList()) }
    var events by remember { mutableStateOf<List<AdminAuditEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh(target: AdminPage) {
        scope.launch {
            loading = true
            message = loadAdminPage(
                api = api,
                page = target,
                onDashboard = { dashboard = it },
                onUsers = { users = it },
                onEvents = { events = it },
            )
            loading = false
        }
    }

    LaunchedEffect(page) { refresh(page) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AdminHeader(onSignOut)
        AdminNavigation(page = page, onSelect = { page = it })
        if (loading) CircularProgressIndicator()
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when (page) {
            AdminPage.OVERVIEW -> AdminOverview(dashboard)
            AdminPage.USERS -> AdminUsers(
                users = users,
                loading = loading,
                onSetRole = { user, role ->
                    scope.launch {
                        message = updateUser(api, user, role)
                        refresh(AdminPage.USERS)
                    }
                },
            )
            AdminPage.AUDIT -> AdminEvents(events, loading, emptyLabel = "No audit events recorded.")
            AdminPage.ERRORS -> AdminEvents(events, loading, emptyLabel = "No errors recorded.")
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(onClick = { refresh(page) }, enabled = !loading) { Text("Refresh") }
    }
}

private suspend fun loadAdminPage(
    api: LifeLinkApi,
    page: AdminPage,
    onDashboard: (AdminDashboardResponse) -> Unit,
    onUsers: (List<AdminUser>) -> Unit,
    onEvents: (List<AdminAuditEvent>) -> Unit,
): String? {
    return try {
        when (page) {
            AdminPage.OVERVIEW -> {
                val response = api.adminDashboard()
                if (!response.isSuccessful) return "Dashboard request failed (${response.code()})."
                onDashboard(response.body() ?: return "Dashboard response was empty.")
            }
            AdminPage.USERS -> {
                val response = api.adminUsers()
                if (!response.isSuccessful) return "User list request failed (${response.code()})."
                onUsers(response.body()?.items ?: emptyList())
            }
            AdminPage.AUDIT, AdminPage.ERRORS -> {
                val response = if (page == AdminPage.AUDIT) api.adminAudit() else api.adminErrors()
                if (!response.isSuccessful) return "Log request failed (${response.code()})."
                onEvents(response.body()?.items ?: emptyList())
            }
        }
        null
    } catch (failure: IOException) {
        failure.message ?: "Network error while loading admin data."
    } catch (failure: JsonParseException) {
        failure.message ?: "The server returned unreadable admin data."
    }
}

@Composable
private fun AdminHeader(onSignOut: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text("LifeLink Admin", style = MaterialTheme.typography.headlineSmall)
            Text("Restricted operations console", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
    }
}

@Composable
private fun AdminNavigation(page: AdminPage, onSelect: (AdminPage) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AdminPage.entries.forEach { item ->
            if (page == item) {
                Button(onClick = { onSelect(item) }) { Text(item.title) }
            } else {
                OutlinedButton(onClick = { onSelect(item) }) { Text(item.title) }
            }
        }
    }
}

@Composable
private fun AdminOverview(data: AdminDashboardResponse?) {
    if (data == null) {
        Text("System overview")
        Text("Dashboard totals will appear after the API responds.")
        return
    }
    StatCard("Registered profiles", data.users.toString())
    StatCard("Donor records", data.donors.toString())
    StatCard("Emergency requests", data.requests.toString())
    Text("Updated: ${data.generatedAt}", style = MaterialTheme.typography.bodySmall)
    Text("Request ID: ${data.correlationId}", style = MaterialTheme.typography.bodySmall)
    Text(
        "Admin access is server-verified. User capability changes are audited; admin privileges cannot be granted here.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun AdminUsers(
    users: List<AdminUser>,
    loading: Boolean,
    onSetRole: (AdminUser, String) -> Unit,
) {
    if (users.isEmpty() && !loading) Text("No user profiles found.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(users, key = { it.userId }) { user ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        user.displayName?.takeIf { it.isNotBlank() } ?: "Unnamed user",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(user.email, style = MaterialTheme.typography.bodySmall)
                    Text("ID: ${user.userId}", style = MaterialTheme.typography.bodySmall)
                    Text("Role: ${user.role} · Request: ${user.canRequest} · Donate: ${user.canDonate}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onSetRole(user, "requester") }) {
                            Text("Set requester")
                        }
                        OutlinedButton(onClick = { onSetRole(user, "donor") }) {
                            Text("Set donor")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminEvents(
    events: List<AdminAuditEvent>,
    loading: Boolean,
    emptyLabel: String,
) {
    if (events.isEmpty() && !loading) Text(emptyLabel)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(events, key = { it.id }) { event ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(event.action, style = MaterialTheme.typography.titleSmall)
                    Text("Outcome: ${event.outcome ?: "event"} · ${event.createdAt ?: "time unavailable"}")
                    Text("Actor: ${event.actorId}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Target: ${event.targetType ?: "—"} / ${event.targetId ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    event.correlationId?.let {
                        Text("Correlation: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    event.metadata?.takeIf { it.isNotEmpty() }?.let {
                        Text(it.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

private suspend fun updateUser(
    api: LifeLinkApi,
    user: AdminUser,
    role: String,
): String {
    return try {
        val response = api.adminUpdateUser(
            user.userId,
            AdminUserUpdateRequest(
                role = role,
                canRequest = role == "requester",
                canDonate = role == "donor",
            ),
        )
        if (response.isSuccessful) {
            "User capabilities updated and audited."
        } else {
            "User update failed (${response.code()})."
        }
    } catch (failure: IOException) {
        failure.message ?: "Network error while updating the user."
    } catch (failure: JsonParseException) {
        failure.message ?: "The server returned unreadable update data."
    }
}
