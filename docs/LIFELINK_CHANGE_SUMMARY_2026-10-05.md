# LifeLink change summary — 2026-10-05

Branch: `fix/a11y-backend-map-legal-cleanup` (restacked on post-PR-#78/#80 `main`).

## What changed

### Audit items (recommended priority order)
- **a. Accessibility / localization (PR #78 debt) — verified present.**
  `MessagingScreen` and `UpdatesScreen` use the `onClickLabel` accessibility
  labels from `res/values/strings.xml` (`messaging_open_conversation`,
  `updates_open_activity*`) instead of a hardcoded `contentDescription` that
  overrode the visible text. No further gap found; verified only.
- **b. Backend chat-status correctness gap — verified present.**
  The merged backend resolves chat/conversation status through the
  conversation generation guard in `PrivacyViewModel` + the backend conversation
  routes; no remaining defect found in the merged head.
- **c. UI/UX-review follow-on polish** — implemented (see "Profile & branding"
  and "Map" below).
- **d. Repo hygiene** — branch restacked on the post-#80 main; design docs added
  under `docs/`.

### Map additions & the 404 fix
- **Root cause of the map 404:** only the PostgreSQL adapter
  (`app/main_postgres.py`) defined `GET /v1/donor-map`; the default in-memory
  service (`app/main.py`) had **no such route**, so the map screen's
  `donorMap()` call returned **404** whenever the API ran without a database
  (the default local/demo deployment).
- **Fix:** added `GET /v1/donor-map` to `app/main.py`, reusing the existing
  privacy models (`DonorMapOut`/`DonorMapEntry`) and mirroring the Postgres
  policy — only opted-in (`map_visible`), available, profile-visible donors with
  a fresh snapshot appear, coordinates coarsened to ~1 km, no identity.
- **Full-screen, already-loaded map:** `MapLibreLocationPicker` renders the
  native map immediately and loads the style in the background — no placeholder,
  no "load map" action. The map screen no longer fires a load action on open.
- **No text-based options; single three-dot overflow top-right:** all options
  live in the overflow `DropdownMenu` (`donor_map_refresh`,
  `donor_map_visibility`, `donor_map_available_donors`); retry is a compact
  icon-only control shown only on failure.
- **Available-donors-sharing-location view with blood-type filter:** the map
  tab shows currently-available donors who are sharing their location, with
  `FilterChip`s for **any type** and each specific type (e.g. **A-**) wired to
  the existing `location-sharing donors` feed (`/v1/donor-map` →
  `DonorMapArea`), not a parallel source.
- **Centered map button restyled** to the elevated, circular reference look
  (`Surface`, `CircleShape`, `primary`, `shadowElevation = 6.dp`, raised).

### Profile legal entry cleanup
- The dangling **"Legal Safety Center"** entry is removed and no navigation
  references it. The still-required **PR #80 legal-consent flow is untouched**
  (`AuthScreen` acceptance + `LegalDocumentDialog`, `LIFELINK_PRIVACY_VERSION` /
  `LIFELINK_TERMS_VERSION`).

### UI & branding
- **Requester/donor switch moved** off the Home header into a new **Accessibility**
  settings tab (`SettingsSection.ACCESSIBILITY`); the home header no longer
  competes with it.
- **App icon beside the "LifeLink" title** wherever the title appears
  (Home header, Profile `StartContent`, Auth screen) via `LifeLinkBrand`.
- **Loading screen** now shows the **animated** LifeLink icon
  (`AnimatedLifeLinkIcon`: pulse + slow rotate, static under preview).

### Design research (deliverable 7b)
- `docs/DESIGN_RESEARCH_CENTERED_MAP_TAB_2026-10-05.md` — centered 4-tab button
  layout options, Material 3 conventions, accessibility, the **4th-tab
  recommendation (Donate)**, and the profile-beside-notifications vs profile-as-tab
  trade-offs (recommends a top-bar avatar next to the bell).
- `docs/DESIGN_RESEARCH_UI_MODERNIZATION_2026-10-05.md` — UI modernization
  direction ("calm urgency"), a signature gradient, shape/typography scale,
  ordered, revertible slices.

## Not verifiable locally
- **Android build / lint / unit & visual tests** could not run here — no Android
  SDK/JDK is present in this environment. `./gradlew` (`assembleDebug`,
  `detekt`, `spotlessCheck`, `testDebugUnitTest`, `connectedDebugAndroidTest`)
  is delegated to **CI**. Changes were format-checked (`spotless`-alignment,
  140-col, import order) and brace/paren-balanced locally.
- **FastAPI tests** (`tests/test_privacy_flows.py`) require `pgserver`; the demo
  `/v1/donor-map` route itself imports cleanly and reuses existing models.

## Constraints honoured
- Scoped to the described screens/features only.
- PR #80 legal-consent flow preserved.
- Map tab reuses the existing location-sharing donors feature — no parallel source.
