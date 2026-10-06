# Design research — modernizing the LifeLink UI (unique, differentiated)

Goal: move LifeLink from "clean but generic Material 3" to a **distinctive,
trustworthy, human** identity — without a risky full redesign. Concrete,
ordered recommendations below.

## 1. Where the current UI sits

- Material 3 defaults with a single crimson primary (`#B91C3A`) on white;
  standard `NavigationBar`, cards, chips. Functional and accessible, but
  visually indistinguishable from countless M3 apps.
- Branding is a wordmark only ("LifeLink"); the app icon is not used in-product.
- Two current trends are already partially followed: **edge-to-edge** and
  **dynamic colour** (a `DYNAMIC` theme mode exists).

## 2. Direction: "warm clinical, calm urgency"

Pick **one** differentiator and commit to it everywhere. Recommended:
**"calm urgency"** — a medical-calm base with a single warm pulse of colour for
life/blood. Concretely:

- **Signature gradient.** One brand gradient (deep rose → bright coral,
  `#B91C3A → #FF5A5F`) used *only* for the primary action and the resting state
  of the center map button. This is the reference's most identifying trait and
  costs almost nothing to add.
- **Expressive shapes.** Move from default 12dp radii to a small scale
  (`8 / 16 / 28dp`) and a **28dp "hero" radius** for primary cards and the
  center button — rounder, friendlier, more ownable.
- **Colour roles.** Keep crimson as `primary`; add a **coral accent**
  (`secondary`/tertiary) for highlights (donor-available, live freshness);
  reserve a calm teal for neutral/positive states so the red never reads as alarm.
- **Typography.** Adopt a two-family system: a humanist sans for UI
  (e.g. Inter/`Roboto Flex`) and a slightly tighter display for numerals/Titles
  (e.g. `Fraunces`/`Lexend` are strong candidates) so headings feel branded
  without harming legibility. Respect dynamic type; never below 12sp.

## 3. Concrete, low-risk upgrades (in priority order)

1. **Brand lockup everywhere.** App icon beside the "LifeLink" title on Home,
   Profile and the auth screen (done in this change via `LifeLinkBrand`), and an
   **animated icon** on the loading screen (done via `AnimatedLifeLinkIcon`).
2. **Signature gradient center button** (see §2) — highest visual ROI.
3. **Iconography.** Replace mixed outline/filled icons with one rounded,
   consistent set; use **filled** for the selected tab, outline otherwise (M3
   convention), and give the map centre a distinctive pin/compass glyph.
4. **Motion.** Add one branded transition — a shared-element or fade-through
   between list and detail — plus the pulsing loading icon. Keep durations
   150–300ms and honour "remove animations".
5. **Empty & error states as brand moments.** Illustrated, on-brand empty states
   (a warm, non-graphic droplet/heart motif) instead of plain text; keep copy
   short and reassuring.
6. **Map as a hero surface.** Let the map run edge-to-edge under a translucent
   app bar with a frosted bottom sheet for the filter/`available donors` panel —
   the map already is the app's most differentiated screen.
7. **Dark mode parity.** Tune the gradient and coral for dark surfaces
   (avoid pure-white text, soften the red) so the identity survives the theme
   toggle.
8. **Accessibility as a feature, not a fix.** Ship the Accessibility settings tab
   (reduce motion, larger labels) and expose it in onboarding copy — a genuine
   differentiator for an emergency app used under stress.

## 4. What to avoid

- Don't chase both a gradient identity *and* full Material You dynamic colour;
  dynamic colour will wash the brand out. Offer dynamic colour as **opt-in**
  (already the case) and make the branded palette the default.
- Don't add illustration/animation to critical flows (request intake, matched
  location) — keep those fast and unambiguous.
- Don't exceed 5 bottom-bar destinations.

## 5. Roll-out

Ship in slices: (1) brand lockup + loading animation + signature button;
(2) shape/typography scale; (3) map hero treatment; (4) empty/error states;
(5) dark-mode tuning. Each slice is independently revertible and screenshot-
testable (extend the existing visual tests).
