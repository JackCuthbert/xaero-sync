# Client waypoint files

## Xaero layout observed for the target version

For a server connection, Xaero Minimap stores data beneath a path like:

```text
xaero/minimap/Multiplayer_example.invalid/
├── config.txt                         # ignore
├── dim%0/mw$default_1.txt             # Overworld waypoint set
└── dim%-1/mw$default_1.txt            # Nether waypoint set
```

The exact target build also generated `dim%0/mw0,1,0_2.txt` for the currently detected automatic Overworld and `dim%1/mw0,1,0_1.txt` for the automatic End. The `mw0,1,0` portion matched `defaultMultiworldId` in the connection's `config.txt`; the numeric suffix is assigned independently per dimension. A pre-seeded `mw$default_1.txt` remained visible but was treated as a separate, non-automatic sub-world.

Observed waypoint files begin with a Xaero header and records such as:

```text
#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination
waypoint:Test Waypoint:T:-4843:51:14332:1:false:0:gui.xaero_default:false:0:0:false
```

Dimensions and Xaero sub-worlds are separate concepts. The client must preserve all eligible waypoint files, including any additional dimension or sub-world variants Xaero creates.

## Inclusion rule

Starting at the current connection root, recursively include a file only when all conditions hold:

1. its normalized relative path is below a `dim%*` directory;
2. its filename begins with `mw` and ends with `.txt`; and
3. its contents carry Xaero's waypoint header or a valid `waypoint:` record.

`config.txt` is always excluded. All other files, including Xaero World Map data, are excluded. Preserve the selected files' relative paths and bytes exactly; do not parse, normalize, reorder, or rewrite waypoint lines.

The observed default Overworld, Nether, detected automatic-Overworld, and automatic-End files are retained as test fixtures in `packages/shared/src/test/resources/fixtures/xaero-minimap`. Any broadened pattern needs a fixture and an explicit specification update.

## Snapshot creation and application

- Sort manifest paths deterministically.
- Hash the relative path and raw byte content of every included file with an unambiguous length-prefixed encoding.
- Store a local sidecar outside the Xaero directory with the last synchronized hash and server timestamp.
- Write received files atomically: write a temporary sibling, flush, then rename. Remove only previously managed eligible files absent from the received manifest.
- Never modify `config.txt` or unrelated files/directories.
- Suppress watcher events caused by the mod's own writes using the expected hash/manifest, not timing alone.

### Automatic-world migration on download

`config.txt` remains local-only, but its `defaultMultiworldId` identifies the currently selected automatic Xaero world. A brand-new Xaero connection creates that configuration only after the configuration-phase sync, so a legacy automatic-world download is acknowledged immediately and held until Xaero has created its connection configuration after joining. The client then writes `mw$default_<index>.txt` under that local automatic-world ID instead. If Xaero has already created the matching local file, waypoint records from the download are appended without duplicating identical records. If it has not, the client preserves the incoming dimension-specific numeric suffix while creating the local automatic filename.

This narrow migration prevents a restored legacy automatic world from appearing as a separate numbered sub-world on a fresh installation. It applies only to `mw$default_*`; all other sub-world filenames remain distinct and are preserved unchanged. The server keeps its canonical snapshot filename, and the local migration is recorded as synchronized so it does not immediately rewrite the server record.

## File watcher

Watch the entire connection root recursively. When Xaero creates a directory such as `dim%-1`, register it for watching immediately. Also rescan the complete manifest periodically: recursive watch registration can miss directory creation or platform-specific events.

Debounce bursts of create/modify/delete events. An upload begins only after a stable rescan produces a content hash different from the last successful sync hash. Files that are temporarily incomplete must be retried on the next debounce/rescan instead of being uploaded as a partial snapshot.
