package com.lifelink.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
internal data class LegalDocument(val title: String, val effectiveDate: String, val sections: List<Pair<String, String>>)

internal val PrivacyPolicyPhilippines = LegalDocument(
    title = "Privacy Policy",
    effectiveDate = "October 3, 2026",
    sections = listOf(
        "What this app does" to
            "LifeLink helps requesters and voluntary donors coordinate blood-related requests. It is not a hospital, blood bank, medical screening service, emergency dispatcher, or substitute for licensed health professionals.",
        "Information we collect" to
            "We may process your email address, account identifier, profile and blood-type information, donor availability, request details, approximate location used for matching, messages, contact details that you choose to share, push-notification tokens, and technical activity needed to secure and operate the service.",
        "Why we use it" to
            "We use information to authenticate accounts, match requests and available donors, protect users, deliver requested notifications, support conversations and contact sharing, prevent abuse, troubleshoot failures, and maintain audit and security records.",
        "Philippine privacy basis" to
            "LifeLink is designed for use in the Philippines and is intended to follow the principles of Republic Act No. 10173, the Data Privacy Act of 2012, and its implementing rules. Health-related and location-related information may be sensitive or high-risk; the app therefore uses data minimization, consent choices, access controls, approximate donor-map areas, and server-side authorization.",
        "Sharing and visibility" to
            "Your exact location is not a public donor-map feature. Contact details and exact location are shared only through an authorized request/contact relationship and only when the applicable user action and privacy conditions are satisfied. Service providers such as Supabase, Render, and Firebase may process data under their service roles and security terms.",
        "Your choices and rights" to
            "You may review and correct account information, control availability and location-sharing preferences, limit what contact details you share, stop notifications through device settings, and request privacy assistance or account deletion through the support channel configured for this deployment. Requests may be subject to identity verification, security, legal-retention, and operational limits.",
        "Retention and security" to
            "We retain information only as needed for the purposes above, safety investigations, legal obligations, and reliable account operation. No online service can guarantee absolute security. Do not place passwords, one-time codes, medical records, patient names, financial credentials, or unnecessary exact addresses in requests or messages.",
        "Contact and review" to
            "The operator should publish a Philippine privacy contact or Data Protection Officer contact before production release. This policy is product documentation, not a legal opinion; it should be reviewed and finalized for the operator, retention schedule, data-processing agreements, breach process, and actual hosting configuration before public launch.",
    ),
)

internal val TermsAndConditionsPhilippines = LegalDocument(
    title = "Terms and Conditions",
    effectiveDate = "October 3, 2026",
    sections = listOf(
        "Acceptance" to
            "By creating a LifeLink account, you confirm that you have read and agree to these Terms and the Privacy Policy. If you do not agree, do not create or use an account. The operator may update these documents and will identify the version accepted at registration.",
        "Emergency and medical limitation" to
            "LifeLink is a coordination tool only. For an emergency, contact the appropriate Philippine emergency service, hospital, physician, blood bank, or licensed facility. LifeLink does not determine medical eligibility, guarantee donor availability, guarantee blood safety or compatibility, arrange transport, or replace clinical screening and blood-bank procedures.",
        "Voluntary donation and no payment" to
            "Use LifeLink only for lawful, voluntary coordination. Do not sell blood, demand deposits or replacement fees, offer improper incentives, pressure a donor, or condition assistance on money, sex, employment, services, or gifts. Blood collection and screening must be handled by authorized facilities under applicable Philippine requirements, including the National Blood Services Act of 1994 (Republic Act No. 7719).",
        "Accurate and safe use" to
            "Provide truthful information, keep your account secure, use only your own account, and share only what is necessary. Do not impersonate another person, submit fraudulent requests, harass users, publish private information, attempt unauthorized access, or use the service to collect or exploit personal data.",
        "User interactions" to
            "A match, message, acceptance, or contact share is not an endorsement or guarantee. Verify identity and details independently, meet only in safe and appropriate settings, involve a trusted person where appropriate, and follow the instructions of a licensed facility. Report unsafe behavior and block users through the app when available.",
        "Availability and changes" to
            "The service may be unavailable, delayed, rate-limited, changed, or discontinued. Matching results, map areas, notifications, and messages may be incomplete or stale. The operator may suspend accounts or content to protect users, investigate abuse, or comply with law.",
        "Governing context" to
            "These Terms are intended for a LifeLink deployment operated in the Philippines and should be finalized by the operator with Philippine legal advice, including terms for liability, consumer protection, minors, dispute handling, intellectual property, and the designated operator/contact details.",
    ),
)

/**
 * Displays [document]'s effective date and sections in a scrollable dialog.
 * Invokes [onDismiss] when the dialog is dismissed or its Close button is pressed.
 */
@Composable
internal fun LegalDocumentDialog(document: LegalDocument, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(document.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Effective ${document.effectiveDate}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                document.sections.forEach { (heading, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(heading, style = MaterialTheme.typography.titleSmall)
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
