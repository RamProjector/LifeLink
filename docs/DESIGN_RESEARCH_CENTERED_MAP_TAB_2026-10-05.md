# Design research — centered action button with 4 tabs (mobile)

Scope: LifeLink Android (Jetpack Compose, Material 3). The bottom bar needs a
prominent **centered** control plus **four** destinations; the product owner also
wants the **profile** entry moved **beside the notification** entry instead of
occupying its own tab. This note gives layout options, platform conventions,
interaction/accessibility rules, a recommendation for the 4th tab, and the
trade-offs of the two ways to place profile.

## 1. Layout / structure options for a centered control

| # | Structure | How it looks | Pros | Cons |
|---|-----------|--------------|------|------|
| A | `NavigationBar` + centered **docked FAB** (5 slots, FAB overlaps the bar) | Raised round button cradled in the bar | Iconic; FAB is unmistakably primary; standard reachability | FAB-in-navbar is a Material 2 idiom; in M3 it needs custom docking; labels for the other 4 must stay legible |
| B | `NavigationBar` with an **elevated center item** (`NavigationBarItem`, tinted/offset) | Same row, center icon raised & coloured | Simplest; keeps 5 real item slots; badges, ripple, semantics free | Less prominent than a FAB; can't easily overflow above the bar |
| C | `BottomAppBar` (`centerDocked`) + FAB | Classic cradle notch | Strong visual identity; notch | M2-era pattern; M3 Express de-emphasises the cradle; FAB must not duplicate a destination |
| D | **Custom Row/Box** with a raised circular button overlaid | Exact control of size/colour/gradient | Pixel-exact match to the reference (gradient, elevation) | You re-implement semantics, insets, ripple, RTL |

**Applied here (this change):** the App needs to preserve the existing four
destinations (Home, Requests, Messaging, Profile) *and* still surface a
prominent centered map action, so option **B**, implemented as a lightweight
custom centered slot inside a `NavigationBar` (`ShellMapAction`), is used: the
four `NavigationBarItem`s keep their semantics and test tags, and a raised,
elevated circular button (`Surface`, `CircleShape`, `shadowElevation = 6.dp`,
`primary` colour) sits between them. This keeps the tab row materially
consistent while matching the reference's raised centered control.

If the product later commits to a **gradient** FAB look, upgrade to option **D**
and keep the same test tag/semantics so tests don't churn.

## 2. Platform conventions (Android / Material 3)

- **3–5 top-level destinations.** Material 3 advises against more than five;
  four tabs + one centre action sits at the comfortable end of the range.
- **A FAB performs an *action*, not navigation.** The center control here opens
  the full-screen map — a task, not a peer destination — which is why it reads
  as a raised action rather than a fifth tab.
- **Prefer promoting to hiding.** Destinations should stay visible; long-press
  tooltips are a secondary affordance, not the primary label.
- **Predictive back / system bars:** use `Scaffold` insets; the bar must sit
  above the navigation-bar inset (already handled via `Scaffold` padding).
- **RTL:** mirror the row; the centre slot stays centred automatically.

## 3. Interaction & accessibility

- **Touch targets ≥ 48dp** (center button is 52dp; nav items already ≥ 48dp).
- **TalkBack:** the centre button needs a real label — added as
  `stringResource(R.string.lifelink_map_action)` ("Open the donor map"); the
  four items announce their label text.
- **Don't encode meaning in colour alone** — the raised button also carries an
  icon and a content description.
- **Dynamic type:** long labels may wrap/ellipsise (`maxLines = 1`,
  `TextOverflow.Ellipsis`); ensure 200% font scale still shows a discernible
  icon per item. Consider icon-only + `contentDescription` if labels truncate.
- **Motion:** the raised button should keep the standard M3 state-layer ripple;
  respect "remove animations" (see the `Reduce motion` switch in Accessibility).
- **Reachability:** the centre control is the most reachable point on a phone —
  good for the map action.

## 4. What should the 4th tab be?

Constraint: profile must move **beside the notification**, so profile is *not* a
tab. That frees a slot. Candidates:

1. **Donate** (donor workspace / "become a donor") — *recommended*.
2. **Learn / Resources** — blood-donation facts, eligibility, emergency guidance.
3. **Updates / Notifications** — rejected: notifications already live in the top bar.
4. **Nearby / Explore donors** — rejected: duplicates the map tab.

**Recommendation: `Donate`.** It is the app's second genuine persona entry
(requester vs donor), is a first-class destination (donor profile, availability,
incoming requests), and does not duplicate the map or messaging. It also gives
the center map action a natural sibling in the donor journey ("find donors" vs
"be a donor"). `Learn` is the fallback if the donor workspace stays reachable
from Home.

## 5. Profile beside notifications vs. profile as a 4th tab

| Option | Pros | Cons |
|--------|------|------|
| **Profile beside notifications** (avatar next to the bell in the top bar) — *recommended* | Frees the 4th tab for a content destination (Donate); account is a secondary, infrequent task well-suited to a top-right avatar (a strong convention); keeps a single, consistent account affordance | Slightly less discoverable for first-run users; top bar can crowd; needs a clearly tappable avatar with an a11y label |
| **Profile as one of the 4 tabs** | Maximum discoverability; one obvious home for account; simplest top bar | Spends a scarce top-level slot on a secondary task; leaves no room for Donate/Learn |

**Recommended direction:** place a **person avatar next to the bell** in the top
bar; avatar → Profile, bell → Notifications. Use the freed tab for **Donate**.
Keep both top-bar affordances labelled for accessibility.

## 6. Concrete Compose checklist

- [x] `NavigationBar` hosts four `NavigationBarItem`s (semantics + `testTag("nav-…")` preserved).
- [x] Centered raised `Surface` (`CircleShape`, `primary`, `shadowElevation = 6.dp`, `size 52dp`, `offset(y = -6.dp)`), `testTag("open-fullscreen-donor-map")`.
- [x] `contentDescription` on the centre icon (localized).
- [ ] (Optional) Full custom bar (option D) if a gradient centre button is required.
- [ ] (Optional) Promote "Donate" to a tab and move profile to the top bar.
