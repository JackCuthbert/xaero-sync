# Changelog

## [1.0.1](https://github.com/JackCuthbert/xaero-sync/compare/xaero-sync-v1.0.0...xaero-sync-v1.0.1) (2026-09-04)


### Bug Fixes

* **ci:** avoid concurrent Gradle verification ([5f8a904](https://github.com/JackCuthbert/xaero-sync/commit/5f8a90465a5cbbc8a7358ae09a7a452e6f22077a))

## 1.0.0 (2026-09-03)


### Features

* add configuration compatibility probe
* add snapshot persistence model
* add waypoint snapshot recovery commands
* improve waypoint recovery command UX
* limit retained waypoint snapshots
* replace waypoints from another player
* synchronize waypoint snapshots on connect
* upload waypoint changes during play


### Bug Fixes

* complete configuration probe exchange
* defer automatic waypoint restore until Xaero initializes
* explain deferred waypoint restore to players
* harden deferred waypoint migration
* harden snapshot validation
* include detected Xaero world files
* preserve server data for fresh clients
* remove xaerosync command alias
* resolve server scope during configuration
* restore legacy auto waypoints into Xaero auto world
* support Fabric Loader 0.19.3

## Changelog

Notable changes to Xaero Sync are recorded here by Release Please.
