package com.lifelink.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.auth.UserRole

@Composable
fun RoleSelectionScreen(onRoleSelected: (UserRole) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("How will you use LifeLink?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Choose the experience that fits you. You can change this later from your profile.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        UserRole.values().forEach { role ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(role.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(role.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { onRoleSelected(role) }, modifier = Modifier.fillMaxWidth()) { Text("Continue as ${if (role == UserRole.DONOR) "donor" else "requester"}") }
                }
            }
        }
        Text("LifeLink helps people connect; it does not replace medical professionals or blood-bank screening.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
