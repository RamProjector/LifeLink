# Parallel AI code review

LifeLink's AI review is a **supplementary reviewer**, not an authority and not a replacement for CI, security tooling, or human review.

## Specialist passes

The workflow asks four independent reviewers to run concurrently:

1. Android architecture and runtime behavior
2. Backend API and security
3. Database, Supabase, and account isolation
4. UX, accessibility, and test adequacy

The workflow publishes one consolidated PR comment and updates that comment on subsequent pushes, rather than creating four separate comment threads. Reviewers should report only actionable findings with severity and evidence; findings still require human verification.

## Enable it

Configure a repository Actions secret named `OPENAI_API_KEY` with an API key authorized to use the OpenAI API. Optionally configure the Actions variable `OPENAI_REVIEW_MODEL` to a model available to that API project; if unset, the workflow uses `gpt-4.1-mini`.

API usage may incur charges under the API account's billing terms. Four review requests run per PR update, so avoid pushing many trivial commits. The review is skipped with a warning if the secret is absent.

## Security constraints

- The workflow uses `pull_request_target` to access the repository secret, but checks out only the trusted default branch. **Never change it to check out or execute code from the pull request head.**
- The pull request diff is treated as untrusted text and sent to the configured OpenAI API. Do not put credentials, donor data, private coordinates, or production records into code or PR diffs.
- The workflow grants only read access to repository contents and write access to PR/issue comments. It does not merge PRs, push commits, change settings, or access production systems.
- The model may be wrong, may miss defects, and may be influenced by malicious instructions embedded in code. The prompt tells it to ignore such instructions, but this is not a perfect defense.
- AI review is advisory. Keep build, tests, CodeQL, secret scanning, and human review as independent safeguards.

## Known limitations

- Diffs over 60,000 characters are truncated and the comment states that limitation.
- The reviewers only see the PR title and diff, not a full live runtime or database. They cannot prove deployment correctness, real-device GPS behavior, RLS state in the live Supabase project, or production readiness.
- The default model name must be available to the configured API project. If the API call fails, the workflow logs the failure and reports that reviewer as unavailable.
