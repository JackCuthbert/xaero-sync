# Xaero Sync

Xaero Sync backs up your [Xaero's Minimap](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap) waypoints to a Paper server. Install the Fabric mod on your client and the Paper plugin on the server; after that, waypoint backup and recovery happen through normal server connections.

It supports Minecraft `26.2`, Xaero's Minimap Fabric `26.4.2`, Fabric Loader `0.19.3` or newer, and Paper `26.2` build `121`.

## Install Xaero Sync

### Players

Download the Fabric client JAR from [Releases](https://github.com/JackCuthbert/xaero-sync/releases) or ask your server administrator for it. Use the same Xaero Sync release as the server. In your Fabric Minecraft `26.2` instance, place these files in the instance's `mods` directory:

- Xaero Sync's Fabric client JAR (`xaero-sync-fabric-26.2-<version>.jar`);
- Fabric API for Minecraft `26.2`;
- Fabric Language Kotlin; and
- Xaero's Minimap Fabric `26.4.2`.

Start Minecraft and join the server. There is no Xaero Sync screen to configure. On your first connection, Xaero Sync compares the waypoint backup on the server with the waypoint files on this computer and keeps the newer set. If no server backup exists, your local waypoints are uploaded.

The backup is scoped to the server connection, not to every server you play on. It includes Xaero waypoint files in every dimension and sub-world for that connection. It does not back up Xaero settings, World Map tiles, or unrelated files.

### Server owners

Download the Paper plugin JAR from [Releases](https://github.com/JackCuthbert/xaero-sync/releases) (`xaero-sync-paper-26.2-<version>.jar`) and put it in the Paper server's `plugins` directory. Restart the server, then ask players to install the matching Fabric client JAR as described above.

Use the regular Paper JAR, not a `-plain.jar`. The distributable plugin includes the Kotlin runtime it needs.

Players without the client mod can still join normally; the plugin stays quiet and does not create a waypoint backup for them.

## Use Xaero Sync

Most of the time, just play normally. Xaero Sync uploads waypoint-file changes while you are connected and compares data again when you next connect. This is deliberately not live collaboration: another device or a restored backup is applied when you reconnect, never by silently replacing Xaero files mid-session.

Run `/xaerosync` in-game to check that the server has a backup. It shows when your waypoints were last backed up, how many waypoint files are included, and how many restore points you have.

### Make and restore a backup

Run `/xaerosync backup` to create a restore point before experimenting with waypoints. Run `/xaerosync backups` to see restore points. Each entry has a clickable **Restore** action.

Selecting Restore shows what will happen and offers a separate confirmation. Xaero Sync saves your current server backup before restoring the selected one. After confirming, disconnect and reconnect to load the restored waypoints into Xaero.

### Copy another player's waypoints

On a trusted server where you have the `xaerosync.replace` permission, run:

```text
/xaerosync replace PlayerName
```

Xaero Sync previews that player's backup before changing anything. Confirm the offered action to save your current backup and replace it with the selected set. Disconnect and reconnect to apply it locally.

## Troubleshooting

- **Nothing seems to happen:** confirm that both the server plugin and your Fabric client mod are installed, and that every dependency is for Minecraft `26.2`.
- **Waypoints do not appear after a restore or replacement:** reconnect to the server. Xaero Sync intentionally does not rewrite live waypoint files.
- **A server has no backup for you:** join once with Xaero Sync installed and then run `/xaerosync` after connecting.
- **You cannot use a command:** ask the server owner about `xaerosync.command` or `xaerosync.replace` permissions.

## Server installation and administration

Xaero Sync stores each player's canonical waypoint backup under `plugins/XaeroSync/`, keyed by Minecraft UUID. Keep this directory when updating the plugin; it contains the backups and restore points.

| Permission | Default | Purpose |
| --- | --- | --- |
| `xaerosync.command` | Everyone | View and manage your own backup. |
| `xaerosync.replace` | Everyone | Replace your backup from another trusted player's backup. |
| `xaerosync.admin` | Operators | Target another player and view diagnostics. |

Administrators can run `/xaerosync diagnostics <player>` for the UUID, hash, exact timestamp, and storage usage of a player's backup. They can target an online player name or UUID with the applicable administrative commands. Console commands require an explicit player name or UUID.

To build an unreleased development version, install [mise](https://mise.jdx.dev/), then run:

```sh
mise install
mise run verify
```

The Fabric and Paper JARs are created under `packages/fabric-client/build/libs/` and `packages/paper-plugin/build/libs/`. See [docs/README.md](docs/README.md) for protocol, file-layout, and development details.
