# Development process

## Engineering standards

The repository-level [engineering instructions](../AGENTS.md) are mandatory for all implementation work. In particular:

- deliver one independently useful feature per commit, with that feature's tests, documentation, and follow-up iteration squashed into the same commit;
- use only modern, idiomatic Kotlin and platform practices compatible with the pinned target versions;
- add and enforce Kotlin-capable formatter and linter tooling as part of the build foundation; and
- test observable behaviour, error handling, and boundaries rather than internal implementation shape.

## Milestone 0 — repository and build foundation

Create a multi-project Gradle Kotlin build with `shared`, `fabric-client`, and `paper-plugin` modules under `packages/`. Pin the v1 Minecraft, Fabric API/Loader, Xaero compatibility, and Paper versions. Configure the Paper plugin's Kotlin runtime strategy and produce reproducible client-mod and plugin JARs. Add formatter and linter tasks, then expose them through mise before feature development begins.

## Milestone 1 — compatibility and filesystem spike

This milestone must finish before the production protocol:

1. Install the target Xaero Minimap version in a clean Fabric client.
2. Create waypoints in Overworld, Nether, End, and any selectable sub-world variants.
3. Record the full `xaero/minimap/Multiplayer_*` directory tree and build fixture files from it.
4. Confirm the inclusion rule excludes `config.txt` and includes every waypoint file.
5. Prove a Fabric client can exchange a versioned custom payload with Paper `26.2` build `121` in the configuration phase, before the world is usable.
6. Prove a received file snapshot is visible to Xaero after joining, without mixins or Xaero internals.

If any finding changes the file predicate or lifecycle assumptions, update the corresponding specification before continuing.

## Milestone 2 — shared model and persistence

Implement codecs, validation, deterministic snapshot hashing, JSON records, atomic writes, and tests for corrupted/truncated records. Add fixtures for multiple dimensions, ignored configuration, empty manifests, path traversal attempts, and unknown waypoint fields.

## Milestone 3 — connection synchronization

Implement the configuration-phase metadata exchange, chunk transfer, server-wins/client-wins replacement rules, and client sidecar state. Test fresh player, client-newer, server-newer, exact-tie, failed transfer, interrupted transfer, and server restart scenarios.

## Milestone 4 — safety-net uploads and recovery

Implement recursive watching, new-directory registration, debounce, periodic rescan, self-write suppression, disconnect upload, snapshots, and recovery commands. Test force-quit/crash simulation, Nether directory creation, duplicate events, partial writes, restore/reconnect, and concurrent joins by the same UUID.

## Release criteria

- All wire and persistence validation tests pass.
- The compatibility spike passes on the exact v1 versions.
- A clean second client receives Overworld and Nether waypoint files after joining the private server.
- Editing `config.txt` does not upload, download, or overwrite it.
- A forced client termination after a waypoint edit is recovered by the watcher before termination when sufficient debounce time has elapsed.
- Restore never changes a live client's waypoint files without an explicit reconnect.
- Vanilla clients can join with no error or player-facing Xaero Sync message.
