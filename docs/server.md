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

- `/xaerosync status` — show server canonical timestamp/hash and snapshot count; show client sync information when that client is connected with the mod.
- `/xaerosync backup` — write a named or timestamped snapshot of the current canonical record.
- `/xaerosync snapshots` — list available restore points.
- `/xaerosync restore <snapshot>` — snapshot the current canonical record, restore the selected record, then instruct the player to reconnect. It never silently replaces waypoint files during live play.

The exact command argument syntax and permission nodes are implementation details, but destructive restore must require a confirmation step or explicit confirmation flag.

The implemented syntax is `/xaerosync <status|backup|snapshots> [uuid]` and
`/xaerosync restore <snapshot> confirm [uuid]`. Players may omit their own UUID. Targeting another UUID requires
`xaerosync.admin`; restore always requires the literal `confirm` argument and tells an online target to reconnect.

## Failure behavior

- Invalid payloads, invalid manifests, unsupported versions, failed hashes, and oversized transfers are rejected without altering storage.
- A failed client download/application must not cause the client to acknowledge sync.
- Server file I/O occurs off the main server thread; Bukkit/Paper player interactions return to the correct scheduler/thread.
- The server logs UUID, operation, hash prefix, and failure category, never waypoint contents by default.
