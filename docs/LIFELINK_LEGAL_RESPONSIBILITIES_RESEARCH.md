# LifeLink Legal Responsibilities Research Brief

**Product:** LifeLink, a Philippines-oriented Android/FastAPI blood-request matching platform  
**Status:** Implementation guidance for product, engineering, operations, partnerships, privacy, and safety teams  
**Important limitation:** This brief is implementation research, not legal advice. It does not determine that any statute, regulation, license, exception, or liability shield applies to LifeLink. Philippine counsel and the relevant Department of Health (DOH) Center for Health Development (CHD), licensed blood-service partners, privacy professionals, and clinical advisers must review the product before production launch.

## Executive conclusion

LifeLink should be designed as a **non-medical coordination and communication layer**. It may help a person contact a potential voluntary donor and a licensed or authorized blood-service facility. It should not present itself as a blood bank, blood center, blood collection unit, clinic, laboratory, hospital, ambulance, emergency service, donor-screening authority, compatibility authority, inventory system, or transfusion service.

The decisive product boundary is that **all clinical and blood-service functions remain with the licensed or authorized facility and qualified professionals**. LifeLink must not collect blood or samples, screen or approve donors, test for infection, determine blood type or compatibility, store or transport units, issue units, make clinical allocation decisions, or promise that a request is genuine, urgent, fulfilled, safe, compatible, or available. Philippine blood-service rules allocate those functions to authorized facilities and qualified personnel. WHO guidance independently supports the same separation of roles. [1] [2] [3] [4] [5]

The safest launch model has two distinct steps. A requester can submit a need, approximate location, and contact preference. A licensed facility then confirms the actual need, donor suitability, collection appointment, testing, compatibility, availability, and release. The app must display those facility-confirmed states separately from user-submitted claims and must never turn a profile, self-attestation, or match into an “approved,” “safe,” “negative,” or “compatible” status.

LifeLink should treat blood type, emergency-request content, and health-related messages as high-risk personal data. It should build privacy by design: minimum collection, explicit purpose separation, private-by-default sharing, role-based access, consent and notice versioning, field-level retention, vendor controls, auditability, and a tested incident-response plan. The platform should also operate a visible safety layer with a persistent emergency route, anti-coercion rules, reporting and blocking, age and vulnerable-user safeguards, and fast quarantine of unsafe listings.

## 1. How to use this brief

This document separates three different things that must not be conflated:

1. **Legally sensitive claims requiring Philippine counsel.** These are legal classifications, interpretations, current regulatory requirements, and liability conclusions. They are not product assumptions.
2. **High-confidence operational requirements.** These are conservative controls that follow directly from the cited blood-service, privacy, safety, and platform materials or are prudent implementation policies even where the law’s application to LifeLink is uncertain.
3. **Proposed product copy.** These are draft disclosures and interface language. They are not legal findings. Counsel and clinical reviewers should approve the final Filipino and English wording, local emergency instructions, and partner-specific variations.

The cited WHO, NHS, JPAC, American Red Cross, Australian Red Cross, and other non-Philippine materials are comparative medical, ethical, or service guidance. They do not create Philippine legal duties. The JPAC source expressly reflects UK guidance, and American Red Cross criteria are US-specific. They are used here to support safety design, not to establish Philippine eligibility rules. [6] [7] [8] [9] [10] [11] [12]

## 2. Responsibility model and non-negotiable boundary

| Actor | May do | Must not be assigned by LifeLink |
|---|---|---|
| **Requester or patient representative** | Submit a need, choose a facility or contact route, and communicate truthful information within the minimum necessary fields | Confirm donor eligibility, testing, compatibility, supply, transfusion indication, or clinical urgency through the app |
| **Potential donor** | Voluntarily express interest and arrange a facility appointment | Sell or condition donation, self-certify as medically eligible, disclose another person’s private data, or bypass facility screening |
| **Licensed/authorized blood-service facility** | Verify the request, screen and counsel donors, collect, test, process, store, transport, issue, and manage facility records according to applicable requirements | Delegate clinical or regulated functions to LifeLink merely because the platform displays a match |
| **Treating hospital and qualified clinician** | Assess and stabilize the patient, decide whether transfusion is indicated, perform identification and compatibility procedures, administer and monitor blood, and manage reactions | Treat a LifeLink profile or match as clinical evidence or a reservation |
| **LifeLink** | Facilitate discovery, controlled contact, consented referral, facility verification, safety moderation, and emergency redirection | Collect or test blood, screen or approve donors, make clinical decisions, guarantee availability, operate as emergency response, or expose unnecessary health data |

RA 7719 describes blood provision as a professional medical service, requires blood banks or centers to meet DOH requirements, and requires blood to be collected only from healthy voluntary donors. DOH issuances assign screening, collection, testing, processing, storage, issuance, and related duties to authorized facility types. [1] [2] [3] WHO describes donor suitability assessment, donation testing, grouping, compatibility, and quality assurance as blood-service responsibilities. [4] [5]

## 3. Legally sensitive claims requiring Philippine counsel before launch

### 3.1 Whether LifeLink is within the blood-service licensing regime

Counsel must determine whether any LifeLink activity could legally be characterized as establishing, operating, assisting, advertising, or performing a function of a blood bank, blood center, blood collection unit, apheresis facility, laboratory, hospital, or other regulated health service. RA 7719 prohibits operating a blood bank or center without DOH registration and a license to operate. AO 2005-0002 and AO 2008-0008 describe facility roles and authorization requirements. The platform should not assume that calling itself a “marketplace,” “directory,” or “matching service” resolves the issue. [1] [2] [3]

Counsel should also confirm current DOH and CHD requirements, including amendments, licensing assessment tools, facility categories, network requirements, and authority-to-operate evidence. The supplied regional DOH licensing page lists AO 2008-0008, AO 2008-0008-A, AO 2005-0002, earlier authorization rules, and revised assessment materials. That is a reason to verify current status directly rather than rely on an old facility list. [13]

**Conservative implementation position:** LifeLink may onboard only facilities whose legal name, facility type, DOH/CHD license or authority-to-operate identifier, validity, expiry, and verification timestamp have been recorded and periodically rechecked. Until counsel and the relevant CHD confirm otherwise, no blood-service action should be available to an unverified facility.

### 3.2 Whether particular platform workflows amount to regulated blood-service activity

Counsel must review the exact data flows and operational roles. A workflow that merely relays a request is materially different from one that ranks donors by medical eligibility, confirms a blood type, promises compatible supply, schedules collection on LifeLink’s own authority, transports units, receives a service fee tied to a unit, or makes clinical allocation decisions.

The platform should therefore avoid any product behavior that could be read as donor-history screening, physical screening, infection testing, typing, cross-matching, processing, storage, transport, issuance, or clinical allocation. AO 2005-0002 assigns authorized facility functions such as recruitment, screening, selection, collection, and transport to a blood center; it assigns testing and processing to designated centers. AO 2008-0008 addresses licensed and authorized facilities, network operation, testing, and issuance. [2] [3]

### 3.3 Voluntary donation, payments, fees, and commercial structure

Counsel must review LifeLink’s revenue model, facility contracts, subscription plans, sponsored placement, advertising, premium matching, and any payment collected from patients, relatives, donors, or facilities. AO 2015-0045 states “Blood is Free,” prohibits donor honoraria, and addresses deposits, blood bonds, replacement fees, and other blood-service fees. Its listed fee ceilings may change and must not be hard-coded without confirming the current DOH issuance. [14]

The product should not call a platform fee a payment for blood, donor compensation, a replacement fee, a deposit, or a blood bond. It should prohibit bidding, sale offers, honoraria, paid priority, and quid-pro-quo demands. Whether a particular lawful technology or administrative fee is permitted requires counsel and current DOH confirmation.

### 3.4 Internet Transactions Act, e-commerce status, and platform obligations

Counsel must determine whether LifeLink is an online merchant, digital platform, e-marketplace, third-party platform, or another covered actor under Republic Act No. 11967, the Internet Transactions Act (ITA), its implementing rules, or related DTI requirements. The supplied DTI materials describe regulatory jurisdiction, complaints, online business information, takedown and compliance orders, online dispute resolution, and e-marketplace conduct. A community matching service may not fit every e-commerce definition, but the classification cannot be assumed from the absence of a conventional sale. [15] [16] [17] [18]

Until counsel resolves the classification, LifeLink should implement the stricter operational posture: maintain internal identity and facility records; provide a complaint route; preserve evidence; support safety and privacy takedown; use transparent trust labels; and avoid representing a user or facility as licensed without independent verification.

### 3.5 Electronic assent, intermediary liability, and disclaimer limits

RA 8792 recognizes electronic contracting, but counsel must draft the actual clickwrap, evidence, retention, change, and dispute provisions. The Act’s service-provider liability boundary is conditional. It does not provide blanket immunity and does not eliminate contractual, regulatory, statutory, court-order, takedown, or evidence-preservation duties. The applicability of any protection depends on facts such as knowledge, financial benefit, direct participation, and inducement. [19]

LifeLink must not use “we are never responsible,” “all risk is yours,” or similar language as a substitute for safe design. Counsel should review any liability cap, release, indemnity, exclusion of consequential damages, governing-law clause, arbitration or online dispute-resolution clause, and any carve-out for fraud, willful misconduct, gross negligence, privacy duties, consumer rights, or non-waivable liability.

### 3.6 Consumer protection, deception, and defective-service exposure

Counsel must assess whether the service’s claims, safety representations, verification labels, urgency statements, availability displays, fees, and partner descriptions fall under Republic Act No. 7394 or other consumer, advertising, or health rules. The Consumer Act materials address safety, deceptive or unfair practices, redress, and liability for defective services or inadequate hazard information. LifeLink should avoid medical, safety, urgency, compatibility, response-time, survival, and success claims. [20]

### 3.7 Data Privacy Act roles, lawful bases, registration, and cross-border processing

LifeLink will generally be the personal information controller (PIC) for purposes it determines, while hosting, messaging, push, analytics, mapping, and support vendors may be personal information processors (PIPs) when acting only on LifeLink’s instructions. Counsel and a privacy professional must confirm the role for each arrangement, data-sharing relationship, joint decision, and partner integration. Outsourcing does not transfer LifeLink’s accountability. [21] [22] [23]

Blood type and emergency-request details that reveal health information should be treated as sensitive personal information (SPI). Account identifiers, contact details, approximate location, and push tokens are personal information when linkable to a person or device. A vital-interest basis may be relevant to a genuine emergency, but it is not a blanket basis for routine donor discovery, profiling, marketing, or account administration. LifeLink needs a documented basis for each purpose and stricter Section 13 analysis for SPI. [21] [22]

Counsel must confirm whether LifeLink must appoint and register a Data Protection Officer, register a processing system, notify the National Privacy Commission (NPC), or satisfy another current threshold. NPC Circular No. 2023-06 and the NPC’s current index should be checked at launch and after material changes. [24] [25]

### 3.8 Consent, notice, data subject rights, retention, and breach thresholds

Counsel must approve the lawful-basis map, privacy notice, consent language, data-sharing arrangements, retention schedule, rights process, and cross-border transfer terms. NPC Circular No. 2023-04 says consent should be freely given, specific, informed, evidenced, granular for unrelated purposes, and withdrawable as easily as it was given. A privacy notice is distinct from a consent form. [26]

LifeLink must not assume every data flow requires consent or that consent cures excessive collection. It should document the purpose, data, scope, duration, recipients, controller identity, rights process, and applicable basis. It should provide access, correction, objection, portability, and deletion, blocking, or withdrawal channels where the law allows. [21] [22] [26] [27]

Counsel and the DPO must confirm whether a specific incident meets the NPC breach-notification threshold. The supplied NPC breach materials describe notification within the applicable 72-hour framework when sensitive or identity-fraud-enabling information was acquired by an unauthorized person and a real risk of serious harm exists, with required reports and affected-person communications. LifeLink must prepare to act quickly; it must not wait for legal analysis before containment. [23] [28]

### 3.9 Cybercrime, online harassment, defamation, and evidence disclosure

Counsel must review the moderation and evidence model against RA 10175, including fraud, identity theft, forgery, illegal access or interception, and online libel. User allegations about donors, hospitals, or staff can create safety and reputational risks. LifeLink should not adjudicate truth casually, should preserve relevant evidence securely, and should use a fair notice-and-appeal process. [29]

RA 11313 section 12 addresses gender-based online sexual harassment, including cyberstalking, non-consensual sharing, impersonation, and related conduct. The source identifies the PNP Anti-Cybercrime Group as the primary implementing body for complaints. Counsel should confirm duties, routing, preservation, and safeguarding for reports involving sexual harassment, minors, trafficking, abuse, or exploitation. [30]

### 3.10 Emergency care, minors, guardians, and accessibility

Counsel and clinical partners must confirm the treatment of emergency referrals, unaccompanied or incapable patients, minors, guardian consent, and facility handoff. RA 8344 addresses emergency treatment and stabilization, including conditions for transfer. LifeLink must never present its in-app assent as consent to transfusion or treatment. [31]

The Philippine Red Cross publishes donor-age and guardian-consent information, but facility rules can change. LifeLink must not hard-code a global donor age or treat that public guidance as a legal rule for every facility. The receiving facility must apply current criteria. [32] Accessibility guidance from the NCDA supports WCAG-oriented design and non-data alternatives, while DILG describes Unified 911 as a free, 24/7 emergency route. Counsel and local partners should confirm the appropriate current emergency integration and language. [33] [34]

## 4. High-confidence operational requirements

The controls below are recommended as launch requirements. They are conservative product and safety requirements, not a claim that every item is an express statutory command.

### 4.1 Service-boundary architecture

Implement a hard boundary in both backend permissions and interface copy:

- LifeLink may create a request, facilitate a consented contact, show a facility directory, relay a callback, and record non-clinical workflow states.
- LifeLink may not collect blood or samples; conduct donor-history or physical screening; determine eligibility; test for HIV, hepatitis, syphilis, malaria, or other markers; type or cross-match blood; process, store, transport, or issue units; or make a clinical allocation decision.
- Only the licensed facility or qualified clinician may confirm the medical need, donor eligibility, testing, compatibility, availability, release, transfusion indication, administration, monitoring, and adverse-event response. [1] [2] [3] [4] [5]
- No algorithm, badge, profile field, chat message, or user declaration may imply that a donor is eligible, safe, disease-free, negative, compatible, or approved.

The backend should encode these boundaries as permissions and state transitions rather than relying only on terms. For example, a requester can create `REQUEST_SUBMITTED`; a facility can create `FACILITY_CONTACTED`, `APPOINTMENT_CONFIRMED`, `COLLECTION_CONFIRMED`, `TESTING_CONFIRMED`, or `ISSUE_CONFIRMED`. A requester cannot create any facility-confirmed state.

### 4.2 Facility onboarding and recurring verification

Permit blood-service actions only for a verified licensed or authorized facility or an expressly verified network partner. Record at least:

- legal name and public display name;
- facility type, such as blood center, blood bank, blood collection unit, or station;
- DOH or CHD license or authority-to-operate identifier;
- issuing authority and source record;
- effective date and expiry date;
- verification method, verifying staff member, and timestamp;
- current status, scope, and partner relationship; and
- re-verification due date and outcome.

Re-check the status periodically and immediately when a facility reports a change. Expired or unverifiable facilities must be suspended from blood-service actions. Maintain a report and takedown route for suspected impersonation, unauthorized facilities, inaccurate credentials, or unsafe conduct. Publicly expose only the minimum verification label; do not publish a license image or exact facility contact details unless necessary and authorized.

### 4.3 Two-step request and handoff workflow

**Step 1: request submission.** Collect only the minimum needed to route the request: facility or hospital name where available, requester callback route, component or blood-group information where the requester has a legitimate reason to provide it, approximate area, urgency wording, and a private facility callback field. Warn that the request is not a reservation or guarantee.

**Step 2: facility confirmation.** A licensed facility or hospital confirms the actual need, donor suitability, appointment, collection, testing, compatibility, availability, and release. Store each confirmation with actor role, source facility, timestamp, and expiry or freshness information. A stale facility status must never look current merely because the request remains open.

Do not publish patient names, diagnoses, medical-record images, exact locations, private phone numbers, government IDs, blood-test results, or detailed health history in public listings. Use opaque request IDs and authenticated access.

### 4.4 Voluntary, unpaid, and non-coercive donation policy

LifeLink should require a separate, unticked acknowledgement that donation and contact are voluntary. Prohibit payments, honoraria, bidding, sale offers, replacement fees, deposits, blood bonds, paid priority, debt, employment or school pressure, gifts conditioned on donation, and demands for money, sex, personal services, or reciprocal medical treatment. Do not use guilt, countdowns, default opt-in contact, or manipulative urgency.

Non-monetary recognition should be enabled only if the responsible facility and current DOH rules permit it. The platform’s fee model must be separately reviewed and must not be described as payment for blood. [14]

### 4.5 Eligibility and consent UX

LifeLink must not hard-code age, weight, deferral, infection-risk, interval, travel, medication, or other donor criteria as an app decision. Collect only preliminary self-reported information needed for routing, label it “not medical screening,” and direct the user to the receiving facility’s current criteria and confidential questionnaire. The facility—not LifeLink—determines eligibility and deferral. [4] [5] [6] [7] [8] [9]

If LifeLink refers a donor to a collection appointment, the app should provide plain-language information about the procedure, expected duration, possible deferral, testing, material risks, data handling, and the right to stop or withdraw at the facility. Accepting a LifeLink chat is not consent to donate. Consent for collection must be obtained by trained facility personnel before each donation under the facility’s applicable process. [6]

### 4.6 Privacy-by-design data model

Maintain a processing record and field inventory covering account identifiers, names, phone numbers, email, blood type, emergency text, approximate location, push tokens, messages, reports, timestamps, recipients, storage locations, cross-border transfers, retention triggers, lawful basis, and deletion method. Classify blood type and health-containing emergency details as SPI; classify other linkable fields as personal information. [21] [22] [24]

Run and document a privacy impact assessment (PIA) before launch and after a major feature, vendor, purpose, location, or data-flow change. The PIA should describe the data lifecycle, sources, repositories, transfers, storage, disposal, responsible persons, privacy-principle assessment, and risks to confidentiality, integrity, availability, and data-subject rights. [24] [35]

Publish a layered, plain-language privacy notice at registration and just in time before collecting blood type, location, emergency text, or enabling push notifications. State the fields, purpose, basis, recipients, processors, storage period, cross-border processing, security summary, PIC and DPO contact, rights, complaint route, and any profiling or automated matching. [22] [26]

Use separate choices for:

- core account and service operation;
- blood-type matching and emergency disclosure;
- approximate or precise location sharing;
- push notifications; and
- optional analytics or marketing.

Store consent and notice version, exact UI text or content hash, timestamp, purpose, locale, acceptance method, and withdrawal event. Make withdrawal at least as easy as consent. Do not bundle optional processing into acceptance of the core service. [26]

Make blood type optional unless needed for a selected service function. Use coarse location by default and foreground location only for an active request. Avoid continuous background tracking. Keep emergency free text short and warn users not to submit medical records. Store push tokens separately from profile and health tables, and delete stale tokens.

Create a field-level retention schedule. Delete or irreversibly anonymize inactive account and contact data after the service-defined period. Delete emergency location and free text promptly after fulfillment, safety follow-up, and any legally required hold. Delete expired push tokens. Retain only a documented suppression or audit record when needed to honor deletion or withdrawal. Apply documented exceptions for legal claims, statutory obligations, and security investigations. [21] [22] [24]

### 4.7 Access, security, vendor, and breach controls

Enforce least-privilege, need-to-know role-based access control:

- users see their own data;
- matching staff see only fields needed for an active task;
- responders receive only an active request’s necessary contact or location details;
- support staff do not see blood type or emergency free text by default; and
- exports and administration require separate privileged roles.

Require strong unique credentials, staff and administrator MFA, authorization checks on every API, rate-limiting against enumeration, access and change logs, periodic access review, and secure authentication. Encrypt data in transit and at rest, encrypt backups, segregate production from development, prohibit production SPI in test data, tokenize or pseudonymize matching identifiers, manage secrets and keys, patch dependencies, monitor vulnerabilities, and test restoration. [21] [24]

Execute written PIP agreements with hosting, database, push, email/SMS, mapping, analytics, and support vendors. Address instructions, purposes, confidentiality, security baseline, subprocessors, breach escalation, rights-request assistance, deletion or return, audit evidence, locations and transfers, and no vendor reuse for advertising or model training without a separate assessment. [21] [23]

Maintain an incident-response plan with a named privacy lead and safety lead. Detect, contain, preserve evidence, assess affected fields and harm, reset credentials or tokens, notify partners and affected people where appropriate, determine whether NPC notification is triggered, and document the decision. Vendor contracts must escalate incidents immediately and early enough to support applicable notification deadlines. [23] [28]

### 4.8 Safety, emergency, and accessibility controls

Put a persistent emergency action on request, donor, and patient-facing screens. It must instruct users to call 911 or go to the nearest emergency department for unconsciousness, severe or uncontrolled bleeding, breathing difficulty, chest pain, signs of shock, or rapidly worsening conditions. It must say not to wait for a match or platform response. Do not diagnose or triage beyond displaying authoritative warning signs and referral instructions. [31] [34]

An outage mode should show the emergency instruction without requiring login or a working match service. The app should provide a voice or non-data fallback and avoid claiming that a platform message is emergency response. [34]

Treat unsafe matches, impersonation, harassment, privacy exposure, incorrect blood-type claims, fraudulent requests, outages, and emergency-delay allegations as safety events. Immediately quarantine the listing or account where necessary, preserve only the minimum evidence with restricted access, notify the responsible safety or privacy owner, assess whether a hospital, facility, emergency service, regulator, or law-enforcement referral is required, investigate root cause, and document corrective and preventive action.

Target WCAG 2.0 AA or later. Provide semantic labels, screen-reader announcements for urgent alerts, keyboard navigation, captions and transcripts, alternative text, high contrast, non-colour status cues, adjustable text, large touch targets, plain-language Filipino and English, appropriate local-language support, voice or TTY alternatives, and an accessibility-help link. [33]

### 4.9 Reporting, blocking, moderation, and anti-fraud

Place Report, Block, and safety actions on every profile, request, match, and message. Use categories for coercion, harassment or sexual harassment, fraud or impersonation, privacy exposure, medical misinformation, solicitation or payment, minor safety, threats, doxxing, and emergency risk.

On a report, stop further contact where needed, preserve only necessary evidence, rate-limit or hold suspected accounts, show a ticket number and status, and provide an explanation and appeal path. Do not promise absolute confidentiality where lawful disclosure or immediate safety action may require it. Route imminent danger to emergency services or the appropriate authority. Never let moderation delay a user’s call to 911 or hospital care.

Ban fake emergency requests, paid priority, sale of blood, coercion, phishing, off-platform payment requests, account sharing, OTP or password requests, forged IDs or medical documents, targeted pressure on minors, impersonation of a clinician or facility, false testing or compatibility claims, bypassing screening or deferral, and publication of test results. Warn users not to share financial credentials, IDs, exact addresses, medical records, or live location.

### 4.10 Governance, auditability, and quality management

Assign a named product and safety owner, clinical adviser or hospital partner, licensed blood-bank partner, privacy or DPO lead, safeguarding lead, operations owner, and audit or quality owner. Maintain partner due diligence, training records, standard operating procedures, a hazard register, a clinical-safety boundary document, a privacy management program, and management review.

Use append-only, timestamped, tamper-evident event records for account verification, consent and guardian capture, request creation and edits, match inputs and algorithm version, messages and referrals, emergency-banner display and 911 click, facility status changes, reports and moderation, data access/export/deletion, notifications, incidents, and administrator actions. Use UTC timestamps, unique event and request IDs, actor role and device metadata, reason codes, retention schedules, and controlled export for authorized investigations. Do not silently edit evidence.

Track safety and privacy indicators, including stale listings, unsafe-match reports, time to containment, emergency referrals, facility verification failures, privacy incidents, accessibility defects, block rates, and partner response. Review them at least quarterly and after significant incidents.

## 5. Proposed product copy

The following is draft copy for counsel and clinical review. It should be localized into Filipino and other appropriate languages without changing the substantive boundary.

### 5.1 Scope disclosure at registration and first contact

> **What LifeLink does**  
> LifeLink helps people contact potential voluntary donors and licensed or authorized blood-service facilities. It is not a blood bank, clinic, laboratory, hospital, ambulance, or emergency medical service. LifeLink does not screen donors, collect or test blood, store or transport blood, cross-match blood, issue blood, or guarantee blood availability, safety, compatibility, or donation.
>
> **Who decides**  
> The licensed or authorized facility and qualified health professionals—not LifeLink—determine donor eligibility, testing results, compatibility, availability, collection, release, and transfusion care.

### 5.2 Emergency banner

> **Emergency: do not wait for a match.** If someone is unconscious, has severe or uncontrolled bleeding, trouble breathing, chest pain, signs of shock, or is getting worse, call **911** or go to the nearest emergency department now. LifeLink is not an ambulance, hospital, blood bank, or medical advice service.

### 5.3 Request and supply disclaimer

> A LifeLink match is only a contact lead. It is not a reservation, confirmed supply, medical approval, compatibility result, or guarantee that a donation will occur. The hospital and licensed blood-service facility must confirm the need, donor eligibility, testing, blood group and antibody testing, compatibility, storage, and release. Never collect, transport, or transfuse blood privately based only on this app.

### 5.4 Voluntary-donation acknowledgement

> I understand that donation and contact are voluntary. I will not offer, request, or demand payment, honoraria, deposits, blood bonds, replacement fees, gifts conditioned on donation, sex, personal services, or reciprocal medical treatment. I will not pressure, threaten, shame, or discriminate against anyone who declines or is deferred. Only the licensed facility decides whether donation can proceed.

### 5.5 Eligibility disclaimer

> Any information I enter about age, health, blood type, prior donation, travel, medication, or eligibility is self-reported and is **not medical screening**. A licensed blood-service facility must apply its current confidential criteria and make the final eligibility and deferral decision. LifeLink will not label me “eligible,” “safe,” “negative,” “disease-free,” or “compatible.”

### 5.6 Clinical boundary

> Only a qualified clinician and licensed blood-service facility can decide whether transfusion is needed and can perform patient identification, sample labelling, compatibility testing, bedside checks, administration, and monitoring. Do not rely on a profile blood type, match, badge, or chat message for a transfusion decision.

### 5.7 Contact and privacy warning

> Share only what is necessary. Do not send passwords, OTPs, financial credentials, government IDs, medical records, test results, exact home or workplace addresses, or live location. Arrange donation only through the official hospital or licensed blood-service facility. Use public or authorized-facility settings, consider bringing someone you trust, and report pressure, threats, payment demands, impersonation, or unsafe medical advice.

### 5.8 Facility status labels

Use labels that describe source and freshness rather than medical certainty:

- **Request posted — user-submitted**
- **Facility contacted — contact recorded, not a clinical confirmation**
- **Appointment confirmed — facility-reported appointment**
- **Collection/testing/issue confirmed — facility-reported; see timestamp and source**

Never use “approved donor,” “safe blood,” “negative,” “compatible,” “guaranteed,” or “available now” as a LifeLink-generated label.

## 6. Recommended in-app Legal & Safety Center

LifeLink should provide a persistent, searchable **Legal & Safety Center** from the registration screen, request screen, profile, chat, facility page, and settings. It should be available before login for emergency and scope information. The center should contain the following sections.

### 6.1 Start Here: scope and emergency action

Show the scope disclosure, emergency banner, Call 911 action, nearest-emergency-department instruction, and non-data fallback. This page should state that LifeLink is not emergency response and that a match is not a reservation or guarantee.

### 6.2 How blood-service decisions work

Explain, in plain language, which decisions belong to the hospital, clinician, licensed blood center, blood bank, or collection unit. Link to the responsible facility’s current eligibility, consent, aftercare, and complaint materials. Show retrieval or last-review dates and the facility or source owner.

### 6.3 Verified facilities and partner directory

For each facility, show the public name, legal name where appropriate, type, verification status, source and date of verification, validity or expiry where safe to display, official contact route, and report-facility option. Explain that “verified” means only that LifeLink completed the stated credential check; it is not a medical or safety guarantee.

### 6.4 Donor and requester safety

Include voluntary-unpaid donation rules, anti-coercion rules, safe-meeting guidance, off-platform scam warnings, eligibility disclaimer, post-donation referral instructions, minor protections, and a clear statement that accepting a LifeLink chat is not consent to donate.

### 6.5 Emergency and adverse-event handoff

Provide the persistent emergency guidance, 911 route, local hospital and facility contacts, and instructions to contact the responsible blood service or clinician for concerning post-donation symptoms. Display authoritative source and review date. Do not supply diagnosis or individualized treatment advice.

### 6.6 Privacy notice and privacy controls

Provide the layered privacy notice, field categories, purposes, legal bases, recipients and processors, cross-border processing, retention, security summary, PIC/DPO contacts, rights, complaint route, profiling or automated matching explanation, and current notice version. Add controls to view and change consent, disable push, stop location sharing, object, request access or correction, request portability, and request deletion or blocking where applicable.

### 6.7 Terms, Community Rules, and acceptable use

Provide separate, downloadable Terms of Use, Community Rules, and Privacy Notice. Include roles, service boundary, prohibited conduct, verification limits, user responsibility, reporting and moderation, appeals, evidence preservation, third-party and off-platform risk, electronic assent, dispute route, and contact details. Avoid blanket immunity language and obtain counsel approval for liability provisions.

### 6.8 Report, block, and urgent safety concern

Expose reporting from every relevant object. Explain categories, what happens after a report, expected response targets, emergency routing, evidence preservation, privacy limits, appeal, and how to request an account or content review. A reporter should not have to confront the reported user.

### 6.9 Data subject requests and complaints

Provide a form and email or alternative contact route for access, correction, objection, consent withdrawal, deletion or blocking, portability, privacy complaints, and safety complaints. Show a request ID, status, identity-verification method, expected next step, and escalation route. Keep the request workflow at least as easy as consent.

### 6.10 Source library and change log

List the current official sources used for blood-service, privacy, emergency, accessibility, and safety content. Include title, URL, jurisdiction, source owner, retrieval date, last review date, and the LifeLink copy or control affected. Flag comparative foreign guidance as non-Philippine. Keep old versions available for accepted users and authorized audits.

## 7. Acceptance and versioning requirements

Every legally or safety-relevant document and acknowledgement must be independently versioned. Use immutable IDs such as `TERMS-2026-01-15-en-v1`, `PRIVACY-2026-01-15-en-v1`, `COMMUNITY-RULES-2026-01-15-en-v1`, `DONATION-VOLUNTARY-2026-01-15-en-v1`, and `EMERGENCY-SAFETY-2026-01-15-en-v1`. Localized copies need their own version and must identify the base content version.

For each acceptance, record:

- account or actor ID;
- document or acknowledgement ID and version;
- locale and exact rendered text or content hash;
- purpose and whether the choice was required or optional;
- affirmative action and UI surface;
- timestamp in UTC;
- notice and consent version shown together;
- device or session metadata needed for audit;
- withdrawal or supersession event and timestamp; and
- administrator or migration reason if a legacy acceptance is imported.

Use separate unticked choices for core service, blood-type or emergency disclosure, location, push, analytics, marketing, facility referral, and voluntary-donation acknowledgement. Do not use prechecked boxes, forced scrolling, guilt, countdowns, or a single “I agree to everything” control for unrelated purposes.

Provide a downloadable copy before and after acceptance. Preserve the accepted version even after the current document changes. A material change should trigger clear notice and, where counsel determines it is required, fresh affirmative acceptance before the affected feature is used. Examples include a new purpose or recipient, new sensitive-data category, new facility-sharing flow, changed liability or dispute term, new automated matching, changed emergency route, or changed voluntary-donation rule. Non-material formatting or typo corrections should still create a new version and change-log entry but may not require re-acceptance.

A withdrawn optional consent must stop the relevant processing without undue delay where no other lawful basis supports it. Propagate withdrawal to application caches, search indexes, analytics, push systems, vendors, and backups through a documented deletion or suppression process. Retain only the minimum audit record needed to prove the event or honor the withdrawal.

The app should show a compact “last updated” and “accepted version” indicator in the Legal & Safety Center. It should not silently treat a passive visit, continued use, or a chat reply as acceptance of a new material document. Counsel should approve the exact reassent design under the electronic-contract framework. [19] [26]

## 8. Launch sequence and stop-ship gates

### Before any pilot

1. Obtain Philippine counsel’s written classification analysis for blood-service regulation, ITA coverage, consumer rules, data protection, minors, emergency handling, and contract terms.
2. Obtain DOH/CHD and licensed-partner confirmation of the facility verification model and permitted platform role.
3. Complete the data inventory, lawful-basis map, PIA, retention schedule, vendor agreements, and DPO/registration determination.
4. Build the service-boundary permissions and state machine so users cannot create facility-confirmed or clinical states.
5. Approve English and Filipino scope, emergency, voluntary-donation, eligibility, safety, and privacy copy with counsel and clinical reviewers.
6. Implement facility verification, consent/version records, role-based access, audit logs, report/block, and incident runbooks.

### Stop-ship conditions

Do not launch blood-service matching if any of the following is true:

- a facility lacks current, independently verified DOH/CHD authority or the platform cannot show verification freshness;
- the product labels a donor or unit as approved, safe, negative, compatible, or guaranteed based on user data;
- LifeLink collects or retains unnecessary health histories, test results, exact locations, IDs, or medical records;
- users can pay for blood, paid priority, or a replacement/bond-like service through the platform;
- emergency guidance is absent, hidden behind login, or dependent on a match response;
- staff or vendors can access SPI without least-privilege authorization and audit logging;
- material terms, privacy notices, and consent choices are not versioned and retrievable;
- reporting, blocking, urgent escalation, and evidence preservation are not operational; or
- the platform cannot suspend an unsafe listing or facility rapidly.

## 9. Open questions for counsel and partners

- Is LifeLink’s proposed feature set outside DOH blood-service licensing, and which specific facility or network authorizations are required for each partnership model?
- Does any referral, scheduling, ranking, fee, advertising, or data-sharing arrangement create a regulated or prohibited blood-service activity?
- Does LifeLink fall within the ITA’s digital-platform or e-marketplace scope, and what registration, business-information, complaint, takedown, ODR, or Trustmark duties follow?
- Which processing purposes use consent, contract, vital interests, legal obligation, or another basis, and which data are SPI under the applicable DPA analysis?
- Do DPO, processing-system registration, or other NPC obligations apply to the expected user base, fields, vendors, or cross-border transfers?
- What precise incident facts trigger NPC notification, and how should LifeLink coordinate with a hospital, facility, law enforcement, or emergency service?
- What is the lawful age and guardian model for donor and requester contact, and how should minors be protected from direct solicitation?
- Which emergency hotline, facility, aftercare, and adverse-event routes are current for each supported locality?
- Which fee and partnership structures are permissible under current DOH rules, and how should they be described without characterizing blood as a commodity?
- Which terms, liability limits, releases, indemnities, dispute clauses, and electronic reassent controls are enforceable and appropriate?

## References

[1]: https://lawphil.net/statutes/repacts/ra1994/ra_7719_1994.html "Republic Act No. 7719, National Blood Services Act of 1994"

[2]: https://elibrary.judiciary.gov.ph/thebookshelf/showdocs/10/50961 "Department of Health Administrative Order No. 2005-0002"

[3]: https://elibrary.judiciary.gov.ph/thebookshelf/showdocs/10/55301 "Department of Health Administrative Order No. 2008-0008"

[4]: https://www.who.int/news-room/fact-sheets/detail/blood-safety-and-availability "World Health Organization, Blood safety and availability"

[5]: https://www.who.int/publications/i/item/9789241548519 "World Health Organization, Blood donor selection: guidelines on assessing donor suitability for blood donation"

[6]: https://www.jpac.org.uk/guidelines/red-book/chapters/3/3-4/ "JPAC, Informed consent – Care and selection of whole blood and component donors"

[7]: https://www.blood.co.uk/the-donation-process/donation-advice-and-information/donation-safety-and-testing/your-safety/ "NHS Blood and Transplant, Your safety"

[8]: https://www.blood.co.uk/the-donation-process/donation-advice-and-information/if-youve-just-donated/bruising-and-arm-pain/ "NHS Blood and Transplant, Bruising and arm pain"

[9]: https://www.redcrossblood.org/donate-blood/how-to-donate/info-for-student-donors.html "American Red Cross Blood, Information for Young Blood Donors"

[10]: https://www.lifeblood.com.au/privacy "Australian Red Cross Lifeblood, Privacy Policy"

[11]: https://www.redcrossblood.org/privacy-policy "American Red Cross, Privacy Policy"

[12]: https://www.who.int/publications/i/item/9789241548519 "World Health Organization, Blood donor selection guidance landing page"

[13]: https://ro4a.doh.gov.ph/services-rled/ "DOH Regional Office IV-A, Regional Licensing and Enforcement Division services"

[14]: https://elibrary.judiciary.gov.ph/thebookshelf/showdocs/10/91973 "Department of Health Administrative Order No. 2015-0045"

[15]: https://ecommerce.dti.gov.ph/internet-transactions-act-of-2023/ "Department of Trade and Industry, Internet Transactions Act of 2023"

[16]: https://ecommerce.dti.gov.ph/wp-content/uploads/2024/05/20231205-RA-11967-FRM-1.pdf "Republic Act No. 11967, Internet Transactions Act"

[17]: https://ecommerce.dti.gov.ph/implementing-rules-and-regulations/ "Department of Trade and Industry, Internet Transactions Act implementing rules and regulations"

[18]: https://ecommerce.dti.gov.ph/wp-content/uploads/2024/06/Joint-Administrative-Order-No.-24-03-1.pdf "Joint Administrative Order No. 24-03, Internet Transactions Act implementing rules"

[19]: https://www.officialgazette.gov.ph/2000/06/14/republic-act-no-8792-s-2000/ "Republic Act No. 8792, Electronic Commerce Act of 2000"

[20]: https://www.officialgazette.gov.ph/1992/04/13/republic-act-no-7394-s-1992/ "Republic Act No. 7394, Consumer Act of the Philippines"

[21]: https://privacy.gov.ph/data-privacy-act/ "National Privacy Commission, Republic Act No. 10173 Data Privacy Act"

[22]: https://privacy.gov.ph/implementing-rules-regulations-data-privacy-act-2012/ "National Privacy Commission, Data Privacy Act implementing rules and regulations"

[23]: https://privacy.gov.ph/wp-content/uploads/2022/01/sgd-npc-circular-16-03-personal-data-breach-management.pdf "National Privacy Commission, NPC Circular No. 16-03, Personal Data Breach Management and Notification"

[24]: https://privacy.gov.ph/wp-content/uploads/2024/03/NPC-Circular-Repeal-16-01-Signed.pdf "National Privacy Commission, Circular No. 2023-06 security of personal data and related obligations"

[25]: https://privacy.gov.ph/pips-and-pics/advisories-circulars/ "National Privacy Commission, PIPs and PICs advisories and circulars index"

[26]: https://privacy.gov.ph/wp-content/uploads/2023/11/NPC-Circular-No.-2023-04_Guidelines-on-Consent_07Nov2023.pdf "National Privacy Commission, NPC Circular No. 2023-04, Guidelines on Consent"

[27]: https://privacy.gov.ph/5-pillars-of-compliance-3/ "National Privacy Commission, Five Pillars of Compliance"

[28]: https://privacy.gov.ph/wp-content/uploads/2022/01/NPC_AdvisoryNo.2017-03.pdf "National Privacy Commission, Advisory Opinion No. 2017-03"

[29]: https://www.lawphil.net/statutes/repacts/ra2012/ra_10175_2012.html "Republic Act No. 10175, Cybercrime Prevention Act of 2012"

[30]: https://www.officialgazette.gov.ph/downloads/2019/04apr/20190417-RA-11313-RRD.pdf "Republic Act No. 11313, Safe Spaces Act"

[31]: https://www.lawphil.net/statutes/repacts/ra1997/ra_8344_1997.html "Republic Act No. 8344, emergency treatment and transfer requirements"

[32]: https://redcross.org.ph/how-to-donate/ "Philippine Red Cross, How to donate blood"

[33]: https://ncda.gov.ph/disability-laws/joint-circulars/accessible-website-design-guidelines/ "National Council on Disability Affairs, Accessible Website Design Guidelines"

[34]: https://dilg.gov.ph/news/One-Number-for-All-Emergencies-Unified-911-to-Launch-Nationwide/NC-2025-1177 "Department of the Interior and Local Government, Unified 911"

[35]: https://privacy.gov.ph/wp-content/uploads/2022/01/NPC_AdvisoryNo.2017-03.pdf "National Privacy Commission, privacy impact assessment guidance source"

[36]: https://www.who.int/publications/i/item/9789240068636 "World Health Organization, guidance on clinical transfusion safety"

[37]: https://iris.who.int/server/api/core/bitstreams/04800755-5603-4140-955a-ef3cb09df11d/content "World Health Organization, blood transfusion safety technical resource"

[38]: https://www.who.int/health-topics/blood-transfusion-safety "World Health Organization, Blood transfusion safety"

[39]: https://caro.doh.gov.ph/wp-content/uploads/2022/08/Blood-Service-Facility-8-31-2022.pdf "Department of Health, Blood Service Facility licensing assessment"

[40]: https://ehotlines.e.gov.ph/ "Philippine government eHotlines directory"

[41]: https://redcross.org.ph/tag/blood-services/ "Philippine Red Cross, Blood Services"

[42]: https://www.officialgazette.gov.ph/2019/04/17/republic-act-no-11313/ "Official Gazette landing page, Republic Act No. 11313"

[43]: https://www.officialgazette.gov.ph/downloads/2019/04apr/20190417-RA-11313-RRD.pdf "Official Gazette PDF, Republic Act No. 11313"

[44]: https://ecommerce.dti.gov.ph/faqs/ "Department of Trade and Industry, Internet transactions FAQs"

[45]: https://privacy.gov.ph/wp-content/uploads/2022/01/AONo_2018-056.pdf "National Privacy Commission, Privacy Advisory Opinion No. 2018-056"

[46]: https://www.who.int/publications/i/item/9789241548519 "World Health Organization, donor suitability guideline"

[47]: https://lawphil.net/statutes/repacts/ra1994/ra1994_7719.html "Lawphil mirror, Republic Act No. 7719"

[48]: https://www.lawphil.net/statutes/repacts/ra2012/ra2012_10173.html "Lawphil mirror, Republic Act No. 10173"

[49]: https://privacy.gov.ph/wp-content/uploads/2022/01/sgd-npc-circular-16-03-personal-data-breach-management.pdf "NPC Circular 16-03 breach-management source"

[50]: https://www.who.int/publications/i/item/9789241548519 "WHO blood donor suitability source"

> **Review note:** This brief intentionally uses conservative product controls where the legal classification is unresolved. Before production, replace every unresolved assumption with a written counsel or regulator position, record the date and issuing authority, and update the Legal & Safety Center source library and acceptance versions.
