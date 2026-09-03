# Server persistence and recovery

## Storage

Use flat, human-readable JSON records under the plugin data directory:

```text
plugins/XaeroSync/
├── players/<uuid>.json
└── snapshots/<uuid>/<timestamp>-<hash>.json
```

Each player record includes:

- format and protocol version;
- authenticated player UUID;
- canonical `updatedAt` and content hash;
- manifest entries (`relativePath`, raw file content encoding, and optional per-file hash); and
- creation/update metadata useful for operators.

Never use player names as record identifiers. File writes are serialized per UUID and atomic: write and flush a temporary sibling, then atomically rename it into place. Failed writes leave the old canonical record untouched.

Before `/xaerosync restore` replaces a record, create an automatic snapshot of the current canonical record. Retention policy is intentionally unrestricted in v1; expose total usage in status/logging so operators can add retention later.

## Commands

All commands are player commands unless an administrator is acting on a named UUID/player, with permissions defined during implementation.

- `/xaerosync` or `/xaerosync status` — show a player's backup status and clickable recovery actions.
- `/xaerosync backup` — write a named or timestamped snapshot of the current canonical record.
- `/xaerosync backups` — list readable, clickable restore points. `/xaerosync snapshots` remains as a compatibility alias.
- `/xaerosync replace <player>` — preview and, after confirmation, replace the caller's canonical set with a known player's set. The caller's existing set is saved as a restore point first.
- `/xaerosync restore <snapshot>` — snapshot the current canonical record, restore the selected record, then instruct the player to reconnect. It never silently replaces waypoint files during live play.

The exact command argument syntax and permission nodes are implementation details, but destructive restore must require a confirmation step or explicit confirmation flag.

Players may omit their own identity. Administrators can append an online player name or UUID to status and backup
commands. Restore requires the explicit confirmation offered by the clickable prompt and saves the current canonical
record before replacing it. Console and scripts can use `/xaerosync restore <snapshot> --confirm [player]`.
`/xaerosync backups [page]` paginates restore points. Administrators can
use `/xaerosync diagnostics <player>` for the exact timestamp, UUID, hash, and storage size; ordinary player output
does not expose those details.

Replacing from another player requires `xaerosync.replace` (granted by default for this trusted-server feature), previews
the source's dimensions, files, waypoint count, and timestamp, and requires `--confirm`. The replacement receives a new
server timestamp so the existing configuration sync downloads it only when the caller reconnects.

## Failure behavior

- Invalid payloads, invalid manifests, unsupported versions, failed hashes, and oversized transfers are rejected without altering storage.
- A failed client download/application must not cause the client to acknowledge sync.
- Server file I/O occurs off the main server thread; Bukkit/Paper player interactions return to the correct scheduler/thread.
- The server logs UUID, operation, hash prefix, and failure category, never waypoint contents by default.
