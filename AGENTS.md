# Engineering instructions

Apply these rules to every implementation change in this repository.

## Commits

- One feature or independently useful change equals one commit.
- Keep follow-up fixes, tests, documentation, and formatting changes for that feature in its original feature commit by amending or squashing before hand-off.
- Do not mix unrelated refactors, dependency upgrades, or formatting-only churn into a feature commit.
- A commit must build and pass its relevant verification tasks on its own.

## Code quality

- Use current, idiomatic Kotlin, Gradle Kotlin DSL, Fabric, and Paper practices compatible with the versions pinned by this project.
- Introduce and enforce a formatter and linter when the Gradle build is scaffolded. Prefer tools with strong Kotlin and Gradle Kotlin DSL support, and run them through Gradle and mise tasks.
- Treat formatter and linter failures as verification failures. Do not hand-format around automated tooling.
- Keep platform-specific code at module boundaries; shared protocol, validation, and persistence code must remain platform-independent.

## Testing

- Test externally observable behaviour and failure handling, not private implementation shape.
- Add tests with each feature for its successful behaviour, relevant boundary cases, and expected failures.
- Prefer realistic fixtures for Xaero waypoint files and protocol payloads over mocks that duplicate production implementation details.
- Run the relevant test suite, formatter, linter, and build checks before considering a feature ready.

## Tooling and validation environment

- Run project and application tooling through the repository's Mise tasks (for example, `mise run verify`, `mise run build`, and `mise run paper`). Do not invoke Gradle or other project-managed tools outside Mise.
- Global repository and service utilities such as `git` and `gh` may be invoked directly; Mise is for application/toolchain management, not a wrapper for every shell command.
- Never modify a user-designated protected/reference Minecraft instance. If an instance is needed for testing, ask the user which disposable instance or path to use before changing it.
- When manual Minecraft, PrismLauncher, or other GUI/window interaction is needed, ask the user to perform it and wait for confirmation. Do not automate GUI interaction or terminate user-run windows/processes.
- When writing GitHub issues, pull requests, comments, or other user-facing project text, use normal punctuation characters directly. Never emit shell- or language-escaped text such as `\\x27` where an apostrophe (`'`) is intended.
