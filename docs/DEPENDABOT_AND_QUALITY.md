# Dependency updates and code quality

LifeLink uses GitHub Dependabot for automated dependency maintenance. The configuration lives at `.github/dependabot.yml`.

## Dependabot coverage

- **Android / Kotlin:** Gradle dependencies under `/LifeLinkAndroid`, scheduled weekly.
- **Backend:** Python packages under `/lifelink_fastapi`, scheduled weekly.
- **GitHub Actions:** action versions used by workflows, scheduled weekly.

Dependabot opens dependency-update pull requests and can report known vulnerable dependencies. It is **not** a general-purpose code reviewer and does not validate application behavior, authorization logic, database isolation, or UX correctness by itself.

## Merge safeguards

Use the existing required validation workflows and repository review policy as independent safeguards. For relevant changes, confirm that backend tests (including PostgreSQL integration tests), backend lint/type checks, Android lint/static analysis/unit tests/build, Docker build, CodeQL, and secret scanning pass. Review application behavior and security-sensitive changes manually; a green dependency update is not proof that the app is correct or production-ready.

No OpenAI API key is required for Dependabot. Do not add an `OPENAI_API_KEY` secret for this purpose.
