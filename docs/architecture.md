# Architecture

## Product boundary

Each Paper server is one independent sync authority. It stores one canonical waypoint snapshot per authenticated Minecraft player UUID. The client determines which local Xaero connection directory corresponds to its current server connection; the server never receives or trusts a UUID supplied by a custom-payload body.

The canonical state is a complete set of Xaero Minimap waypoint files for that one server connection. A complete snapshot replaces the previous canonical snapshot. There is no waypoint-level merge.

## Components

### Shared Kotlin module

Contains only platform-neutral code:

- wire message definitions and codecs;
- snapshot manifest and content-hash calculation;
- JSON persistence model;
- validation limits and path validation; and
- timestamp comparison rules.

It must not depend on Fabric, Minecraft client classes, Bukkit, Paper, or Xaero internals.

### Fabric client mod

The client mod:

1. identifies the local Xaero connection root for the current server;
2. creates raw-file snapshots of waypoint files beneath that root;
3. participates in configuration- and play-phase custom payloads;
4. applies a received server snapshot before the world becomes usable;
5. watches for changes during play and uploads changed snapshots; and
6. exposes client-side status and restore acknowledgement where required.

The mod must use filesystem observations only. It must not use mixins, reflection, or Xaero internal APIs.

### Paper plugin

The plugin:

1. derives the player UUID from the authenticated connection;
2. advertises and processes the custom payload channels;
3. compares, validates, and atomically persists snapshots;
4. sends the canonical snapshot when it wins; and
5. provides server commands and snapshot recovery.

The plugin also persists opt-in player subscriptions. Canonical changes enqueue a deduplicated prompt for each subscriber; they never initiate a client transfer. An accepted prompt copies the exact offered source set into the subscriber's canonical record through the same recoverable replacement path, and the existing configuration-phase protocol applies it after reconnecting.

Plugin Kotlin runtime packaging must be decided during project setup: shade Kotlin stdlib into the plugin, unless a verified shared runtime plugin is deliberately selected. Do not use an unverified Kotlin `PluginLoader` approach.

## Trust model

This is for trusted private servers. Server operators can read waypoint names and coordinates. A player may change only the record derived from their currently authenticated UUID. Custom payload values must never select another UUID, arbitrary server record, or filesystem path.

## Platform constraints

- Minecraft/Fabric/Paper target: `26.2` only for v1.
- Paper target: build `121`.
- Xaero target: Fabric `26.2-26.4.2`.
- Paper compilation uses the Java version required by the selected Paper API.
- A configuration-phase Fabric-to-Paper payload round trip is a required compatibility spike before building the production sync protocol.
