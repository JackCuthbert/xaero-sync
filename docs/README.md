# Xaero Sync specification

Xaero Sync is a private-server waypoint backup system consisting of:

- a Fabric client mod for Minecraft `26.2`, using Fabric Language Kotlin;
- a Paper plugin for Paper `26.2` build `121`, written in Kotlin; and
- a server-authoritative, whole-snapshot sync protocol.

Source code is organized under `packages/`: `packages/shared`, `packages/fabric-client`, and `packages/paper-plugin`. The local, Git-ignored Paper compatibility server lives separately at `dev/paper-server`.

For the local PrismLauncher instance, copy `.envrc.example` to `.envrc`, adjust `XAERO_SYNC_PRISM_MODS_DIR` if needed, and run `direnv allow`. Then `mise run copy-client-mod` builds and installs the Fabric mod into that instance's `mods` directory.

It supports Xaero's Minimap Fabric `26.2-26.4.2`. The product is deliberately a backup/sync tool, not a live collaborative waypoint system. A client may join without the mod; the Paper plugin must remain silent for such clients.

## Specifications

- [Architecture](architecture.md) — components, responsibilities, and scope.
- [Synchronization protocol](synchronization.md) — connect, play, disconnect, and conflict rules.
- [Client waypoint files](client-files.md) — discovery, filtering, snapshots, and file watching.
- [Server persistence and commands](server.md) — JSON records, snapshots, and recovery commands.
- [Development process](development.md) — milestones, compatibility spike, tests, and release criteria.

## Non-goals for v1

- Live waypoint updates while a player is in-game.
- Merging individual waypoints, tombstones, or conflict UI.
- Xaero World Map explored-map/tile synchronization.
- Sharing other players' waypoint sets or public profiles.
- Proxy or multi-server shared storage.

## Local development tooling

This project uses [mise](https://mise.jdx.dev/) to provide the required Java 25 JDK. After installing mise and activating it for your shell, run:

```sh
mise install
mise run doctor
```

The `doctor` task will work once the Gradle project and checked-in `./gradlew` wrapper exist. Contributors should always use the wrapper through mise tasks, rather than installing Gradle globally:

```sh
mise run build
mise run test
mise run format
mise run lint
mise run check
mise run verify
```
