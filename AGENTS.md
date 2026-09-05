# Xaero Sync agent guide

## Architecture

- Xaero Sync is a Fabric client mod plus a Paper plugin that backs up Xaero's Minimap waypoint files.
- The server owns the canonical whole-file snapshot. Sync is connection/change based, not live collaboration.
- Clients without the mod must join without plugin messages or backup creation.
- Keep protocol, validation, and persistence platform-independent in `packages/shared`; isolate Fabric and Paper code in their modules.
- Preserve compatibility with versions pinned by the build.

## Repository map

- `packages/shared`, `packages/fabric-client`, `packages/paper-plugin`: shared, client, and server code respectively.
- `docs/architecture.md` and `docs/synchronization.md`: responsibilities, scope, protocol, and conflicts.
- `docs/client-files.md` and `docs/server.md`: Xaero file rules, persistence, and commands.
- `docs/compatibility.md` and `docs/development.md`: version evidence, manual checks, development, and releases.
- Read only documentation relevant to the task; this map is not a required reading list.

## Tooling and tests

- Run project tooling through Mise, never Gradle directly. Use `mise run verify` for full verification; narrower tasks include `test`, `lint`, `check`, and `build`.
- Formatter and linter failures are verification failures.
- Test observable behaviour, boundaries, and failures with realistic Xaero files and protocol payloads where practical.
- Create worktrees only under the ignored `.worktrees/` directory.

## Safety and commits

- Never modify a protected/reference Minecraft instance. Ask for a disposable path before instance-changing tests.
- Ask the user to perform Minecraft or PrismLauncher GUI work; do not automate or terminate user-run processes.
- Keep each independently useful change, including tests, docs, formatting, and fixes, in one verified commit; exclude unrelated changes.
- Write commits as player/server-admin release notes describing observable behaviour.
- Use normal punctuation, not escaped representations such as `\x27`, in GitHub and other project-facing text.
