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

