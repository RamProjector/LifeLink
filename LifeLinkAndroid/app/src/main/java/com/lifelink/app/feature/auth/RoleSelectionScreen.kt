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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.auth.UserRole

/**
 * Presents the two LifeLink capabilities as selectable cards.
 *
 * LifeLink accounts are capability-based, so this screen is informational only:
 * it explains what each capability does and reports the chosen [UserRole] through
 * [onRoleSelected]. It is not a mandatory gate — new accounts start in the
 * requester shell and donor mode is entered later from Profile.
 */
@Composable
fun RoleSelectionScreen(onRoleSelected: (UserRole) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier =
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                RoleSelectionHeader()
                UserRole.entries.forEach { role ->
                    RoleCard(role = role, onSelect = { onRoleSelected(role) })
                }
                SafetyNote()
            }
        }
    }
}

/**
 * Safety-critical disclaimer, given its own tonal surface so it reads as guidance rather than
 * fine print. The icon is decorative; the text carries the meaning.
 */
@Composable
private fun SafetyNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "LifeLink helps people connect; it does not replace medical professionals or blood-bank screening.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** Brand mark, headline, and subtitle shown at the top of the role-selection screen. */
@Composable
private fun RoleSelectionHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier =
            Modifier
                .size(32.dp)
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
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            "LifeLink",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "How will you use LifeLink?",
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Choose the experience that fits you. You can change this later from your profile.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Short, scannable benefit lines shown inside each capability card. */
private fun roleHighlights(role: UserRole): List<String> =
    when (role) {
        UserRole.REQUESTER ->
            listOf(
                "Post an emergency request in seconds",
                "See eligible donors near you",
                "Message responders privately",
            )
        UserRole.DONOR ->
            listOf(
                "Set your availability and blood type",
                "Get notified about nearby requests",
                "Choose when to share your contact",
            )
    }

/** Displays one capability as a card with an icon, title, description, highlights, and a full-width action. */
@Composable
private fun RoleCard(role: UserRole, onSelect: () -> Unit) {
    val isDonor = role == UserRole.DONOR
    val accent = if (isDonor) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
    val onAccent = if (isDonor) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    val highlightTint = if (isDonor) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = accent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isDonor) Icons.Default.VolunteerActivism else Icons.Default.Favorite,
                            contentDescription = null,
                            tint = onAccent,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        role.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(role.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                roleHighlights(role).forEach { highlight ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = highlightTint,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(highlight, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Continue as ${if (isDonor) "donor" else "requester"}")
            }
        }
    }
}
