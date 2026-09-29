# Branch protection & required checks

This document records the branch-protection settings that should be enabled on
`main`, and how to apply them. The repository is **PR-only**: nothing is pushed
directly to `main`.

## Required settings on `main`

| Setting | Value | Why |
|---|---|---|
| Require a pull request before merging | **on** | No direct pushes to `main`. |
| Required approvals | **1** | At least one human review. |
| Dismiss stale approvals on new commits | **on** | A review must cover the code that actually ships. |
| Require review from Code Owners | optional | Enable once a `CODEOWNERS` file exists. |
| Require status checks to pass | **on** | CI is the real gate. |
| Require branches to be up to date before merging | **on** | Prevents semantic merge conflicts. |
| Require conversation resolution | **on** | No merging with unresolved review threads. |
| Require linear history | optional | Keeps `main` bisectable. |
| Do not allow bypassing the above | **on** | Applies the rules to admins too. |
| Allow force pushes | **off** | History on `main` is immutable. |
| Allow deletions | **off** | `main` cannot be deleted. |

## Required status checks

Add these check names (they must have run at least once on a PR first):

- `backend` — FastAPI test suite (`.github/workflows/validate.yml`)
- `android` — Android quality gate: lint + detekt + spotless + unit tests + assembleDebug
- `dockerfile` — backend image builds
- `gitleaks (secret scanning)` — `.github/workflows/security-scan.yml`
- `Validate and build debug APK` — `.github/workflows/android-build.yml`
- `Capture Android visual evidence` — `.github/workflows/android-visual-review.yml` (UI changes only)

## Apply via the GitHub API

With a token that has `repo` + admin rights on the repository:

```bash
export GH_TOKEN=...            # never commit this
export GH_REPO=RamProjector/LifeLink

curl -sS -X PUT \
  -H "Authorization: token $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$GH_REPO/branches/main/protection" \
  -d '{
    "required_status_checks": {
      "strict": true,
      "contexts": ["backend", "android", "dockerfile", "gitleaks (secret scanning)"]
    },
    "enforce_admins": true,
    "required_pull_request_reviews": {
      "dismiss_stale_reviews": true,
      "required_approving_review_count": 1
    },
    "restrictions": null,
    "required_conversation_resolution": true,
    "allow_force_pushes": false,
    "allow_deletions": false
  }'
```

## Apply via the UI

**Settings → Branches → Add branch protection rule** (or edit the rule for `main`),
then set the values from the table above and add the required status checks by
name.

## Notes

- A status check can only be marked *required* after it has reported at least
  once, so open a PR that triggers every workflow before adding them.
- `enforce_admins: true` means the rules apply to repository admins as well —
  this is what makes the PR-only rule real rather than advisory.
