package com.lifelink.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

internal typealias LegalSection = Pair<String, String>

internal data class LegalDocument(val title: String, val effectiveDate: String, val sections: List<LegalSection>)

internal val PrivacyPolicyPhilippines =
    LegalDocument(
        title = "Privacy Policy",
        effectiveDate = "October 3, 2026",
        sections =
        listOf(
            "What this app does" to
                "LifeLink helps requesters and voluntary donors coordinate blood-related requests. It is not " +
                "a hospital, blood bank, medical screening service, emergency dispatcher, or substitute for " +
                "licensed health professionals.",
            "Information we collect" to
                "We may process your email address, account identifier, profile and blood-type information, " +
                "donor availability, request details, approximate location used for matching, messages, " +
                "contact details that you choose to share, push-notification tokens, and technical activity " +
                "needed to secure and operate the service.",
            "Why we use it" to
                "We use information to authenticate accounts, match requests and available donors, protect " +
                "users, deliver requested notifications, support conversations and contact sharing, prevent " +
                "abuse, troubleshoot failures, and maintain audit and security records.",
            "Philippine privacy basis" to
                "LifeLink is designed for use in the Philippines and is intended to follow the principles of " +
                "Republic Act No. 10173, the Data Privacy Act of 2012, and its implementing rules. " +
                "Health-related and location-related information may be sensitive or high-risk; the app " +
                "therefore uses data minimization, consent choices, access controls, approximate donor-map " +
                "areas, and server-side authorization.",
            "Sharing and visibility" to
                "Your exact location is not a public donor-map feature. Contact details and exact location " +
                "are shared only through an authorized request/contact relationship and only when the " +
                "applicable user action and privacy conditions are satisfied. Service providers such as " +
                "Supabase, Render, and Firebase may process data under their service roles and security " +
                "terms.",
            "Your choices and rights" to
                "You may review and correct account information, control availability and location-sharing " +
                "preferences, limit what contact details you share, stop notifications through device " +
                "settings, and request privacy assistance or account deletion through the support channel " +
                "configured for this deployment. Requests may be subject to identity verification, security, " +
                "legal-retention, and operational limits.",
            "Retention and security" to
                "We retain information only as needed for the purposes above, safety investigations, legal " +
                "obligations, and reliable account operation. No online service can guarantee absolute " +
                "security. Do not place passwords, one-time codes, medical records, patient names, financial " +
                "credentials, or unnecessary exact addresses in requests or messages.",
            "Contact and review" to
                "The operator should publish a Philippine privacy contact or Data Protection Officer contact " +
                "before production release. This policy is product documentation, not a legal opinion; it " +
                "should be reviewed and finalized for the operator, retention schedule, data-processing " +
                "agreements, breach process, and actual hosting configuration before public launch.",
        ),
    )

internal val TermsAndConditionsPhilippines =
    LegalDocument(
        title = "Terms and Conditions",
        effectiveDate = "October 3, 2026",
        sections =
        listOf(
            "Acceptance" to
                "By creating a LifeLink account, you confirm that you have read and agree to these Terms and " +
                "the Privacy Policy. If you do not agree, do not create or use an account. The operator may " +
                "update these documents and will identify the version accepted at registration.",
            "Emergency and medical limitation" to
                "LifeLink is a coordination tool only. For an emergency, contact the appropriate Philippine " +
                "emergency service, hospital, physician, blood bank, or licensed facility. LifeLink does not " +
                "determine medical eligibility, guarantee donor availability, guarantee blood safety or " +
                "compatibility, arrange transport, or replace clinical screening and blood-bank procedures.",
            "Voluntary donation and no payment" to
                "Use LifeLink only for lawful, voluntary coordination. Do not sell blood, demand deposits or " +
                "replacement fees, offer improper incentives, pressure a donor, or condition assistance on " +
                "money, sex, employment, services, or gifts. Blood collection and screening must be handled " +
                "by authorized facilities under applicable Philippine requirements, including the National " +
                "Blood Services Act of 1994 (Republic Act No. 7719).",
            "Accurate and safe use" to
                "Provide truthful information, keep your account secure, use only your own account, and share " +
                "only what is necessary. Do not impersonate another person, submit fraudulent requests, " +
                "harass users, publish private information, attempt unauthorized access, or use the service " +
                "to collect or exploit personal data.",
            "User interactions" to
                "A match, message, acceptance, or contact share is not an endorsement or guarantee. Verify " +
                "identity and details independently, meet only in safe and appropriate settings, involve a " +
                "trusted person where appropriate, and follow the instructions of a licensed facility. Report " +
                "unsafe behavior and block users through the app when available.",
            "Availability and changes" to
                "The service may be unavailable, delayed, rate-limited, changed, or discontinued. Matching " +
                "results, map areas, notifications, and messages may be incomplete or stale. The operator may " +
                "suspend accounts or content to protect users, investigate abuse, or comply with law.",
            "Governing context" to
                "These Terms are intended for a LifeLink deployment operated in the Philippines and should be " +
                "finalized by the operator with Philippine legal advice, including terms for liability, " +
                "consumer protection, minors, dispute handling, intellectual property, and the designated " +
                "operator/contact details.",
        ),
    )

/**
 * Displays [document]'s effective date and sections in a scrollable dialog.
 * Invokes [onDismiss] when the dialog is dismissed or its Close button is pressed.
 *
 * Sections are numbered and each heading carries heading semantics so screen-reader users can
 * navigate the document by heading. The dialog is width-constrained for comfortable line length
 * on tablets and foldables, the body is selectable so readers can copy clauses, and vertical
 * padding keeps the sheet clear of screen edges on small devices.
 */
@Composable
internal fun LegalDocumentDialog(document: LegalDocument, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier =
        Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        title = { LegalDocumentTitle(document) },
        text = { LegalDocumentBody(document) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Document icon, title, and effective-date chip shown in the dialog header. */
@Composable
private fun LegalDocumentTitle(document: LegalDocument) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                document.title,
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                "Effective ${document.effectiveDate}",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Selectable, scrollable list of numbered sections with heading semantics. */
@Composable
private fun LegalDocumentBody(document: LegalDocument) {
    // Cap the scrollable body at roughly 60% of the available height so the dialog never
    // overflows on short screens while still using the space on tall ones.
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    SelectionContainer {
        Column(
            Modifier
                .heightIn(max = maxBodyHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            document.sections.forEachIndexed { index, (heading, body) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${index + 1}. $heading",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "You can reopen this document at any time before you create your account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
