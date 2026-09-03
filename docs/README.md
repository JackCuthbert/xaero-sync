# Xaero Sync specification

Xaero Sync is a private-server waypoint backup system consisting of:

- a Fabric client mod for Minecraft `26.2`, using Fabric Language Kotlin;
- a Paper plugin for Paper `26.2` build `121`, written in Kotlin; and
- a server-authoritative, whole-snapshot sync protocol.

Source code is organized under `packages/`: `packages/shared`, `packages/fabric-client`, and `packages/paper-plugin`. The local, Git-ignored Paper compatibility server lives separately at `dev/paper-server`.

For the local PrismLauncher instance, copy `.envrc.example` to `.envrc`, adjust `XAERO_SYNC_PRISM_MODS_DIR` if needed, and run `direnv allow`. Then `mise run copy-client-mod` builds and installs the Fabric mod into that instance's `mods` directory.

It supports Xaero's Minimap Fabric `26.2-26.4.2`. The product is deliberately a backup/sync tool, not a live collaborative waypoint system. A client may join without the mod; the Paper plugin must remain silent for such clients.

## Build and install

Install [mise](https://mise.jdx.dev/), then run these commands from the repository root:

```sh
mise install
mise run verify
```

`verify` runs the formatter check, automated tests, and the complete build. The distributable files are then available at:

- `packages/fabric-client/build/libs/xaero-sync-fabric-26.2-0.1.0-SNAPSHOT.jar` — send this to each player.
- `packages/paper-plugin/build/libs/xaero-sync-paper-26.2-0.1.0-SNAPSHOT.jar` — install this on the Paper server.

Each player puts the Fabric JAR in their Minecraft instance's `mods/` directory. They need Minecraft `26.2`, Fabric Loader, Fabric API, Fabric Language Kotlin, and Xaero Minimap Fabric `26.4.2`.

Put the Paper JAR in the server's `plugins/` directory and restart Paper `26.2` build `121`. Do not use the similarly named `-plain.jar`: it does not include the Kotlin runtime required by the plugin.

## Specifications

- [Architecture](architecture.md) — components, responsibilities, and scope.
- [Synchronization protocol](synchronization.md) — connect, play, disconnect, and conflict rules.
- [Client waypoint files](client-files.md) — discovery, filtering, snapshots, and file watching.
- [Target-version compatibility evidence](compatibility.md) — observed files and outstanding manual checks.
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
