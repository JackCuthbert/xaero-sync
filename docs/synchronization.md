# Synchronization protocol

## Canonical model

A snapshot consists of a validated file manifest, a SHA-256 content hash, and an `updatedAt` instant. It represents all eligible waypoint files under one local Xaero connection root. It does not contain `config.txt`, World Map tiles, or arbitrary files.

The Paper server record is canonical. The winner is selected using `updatedAt`:

| Condition | Result |
| --- | --- |
| Client timestamp is newer than server timestamp | Client uploads; server atomically replaces its canonical record. |
| Server timestamp is newer than client timestamp | Server sends its canonical snapshot; client replaces eligible local waypoint files. |
| Timestamps are equal | Server wins. |
| No server record | Client uploads its snapshot, including an empty snapshot if that is the agreed initial state. |

This is whole-snapshot newest-wins behavior. If stale local data eventually reconnects with a newer timestamp, it can replace the server record. This is intentional for a server-side backup model and is not a multi-device merge system.

## Persistence format

The shared persistence record is strict versioned JSON containing the timestamp, deterministic content hash, and a Base64 copy of every raw waypoint file. Unknown JSON fields, malformed timestamps, invalid Base64, duplicate paths, ineligible paths, and hash mismatches are rejected. Xaero waypoint lines themselves are opaque bytes: future Xaero fields are preserved unchanged. Relative filenames are also opaque and preserved exactly because Xaero encodes its detected sub-world identity and displayed index in them.

Safety ceilings are 1,024 files, 512 UTF-8 bytes per relative path, 1 MiB per file, 32 MiB of decoded snapshot content, and 48 MiB for the JSON record. Absolute paths, traversal segments, and backslash platform separators are invalid on every operating system.

Records are written by flushing a temporary sibling file and then replacing the previous record with an atomic move where the filesystem supports it. A missing record represents no stored snapshot; a truncated or corrupt record is an error, never an empty snapshot.

## Timestamp rules

- The client creates a new timestamp only after it observes a genuine eligible-file manifest/content change.
- Applying a server snapshot must persist the server timestamp and content hash locally. A download must not immediately become a newer local change and upload back to the server. The client may record its locally migrated automatic-world filename hash against that same server timestamp.
- Use millisecond-or-better UTC instants in the wire format. Server wins exact ties.
- The server rejects malformed timestamps and may reject implausibly far-future timestamps.

## Connection lifecycle

### Configuration phase

Before the world is usable, the client sends its local snapshot metadata: protocol version, content hash, `updatedAt`, and manifest metadata. The server replies with one of:

- `UPLOADING_REQUIRED` — client sends the snapshot content;
- `DOWNLOAD` — server sends the canonical snapshot; or
- `IN_SYNC` — hashes and timestamps already match.

The client applies `DOWNLOAD` before normal gameplay starts. The production implementation is contingent on the compatibility spike proving the Paper endpoint can receive and reply during this phase.

### Play phase

The play channel exists for safety-net uploads and command responses only. It does not push unsolicited replacement snapshots. A debounced watcher builds a snapshot when waypoint files change; if its hash differs from the last successfully synchronized hash, it uploads it.

### Disconnect

Fabric's public play-disconnect event is notification-only and explicitly forbids sending packets. The client therefore flushes one
final comparison during `CLIENT_STOPPING`, while the play connection is still available, and uses `DISCONNECT` only to release watcher
resources. A normal return to the server list relies on the debounced watcher and periodic rescan having already uploaded the change.
Supporting a true pre-disconnect hook would require a mixin, which is outside this project's architecture.

## Wire rules

- Namespace all channels under the mod identifier.
- Version every message and reject unsupported protocol versions with a clear log/status result.
- Split client-to-server snapshot content into ordered chunks. Reassemble only after validating message type, transfer ID, count, total size, and checksum.
- Server-to-client transfers use the same integrity checks even if their practical payload headroom is larger.
- Require bounded total payload size, bounded chunk count, bounded path count, and bounded path length. These are safety limits, not player quotas.
- Never deserialize archive formats or accept paths containing `..`, an absolute path, or a platform separator outside the normalized relative-path format.
