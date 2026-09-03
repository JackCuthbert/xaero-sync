# Target-version compatibility evidence

This records manual evidence for the exact v1 compatibility target: Minecraft `26.2`, Fabric Loader `0.19.5`, Fabric API `0.159.0+26.2`, Xaero Minimap Fabric `26.4.2`, and Paper `26.2` build `121`.

## Confirmed

- Xaero creates `dim%0/mw$default_1.txt` for Overworld waypoints.
- Xaero creates the sibling `dim%-1/mw$default_1.txt` for Nether waypoints.
- `config.txt` is connection-local minimap configuration and is excluded.
- The Fabric and Paper artifacts load on the pinned client and server without entry-point errors.
- The complete configuration probe round trip succeeds before world entry on Paper `26.2` build `121`: the server received it before its join message at `16:16:34`, and the client received the response on its Netty configuration thread at `16:21:29`.
- Platform unit tests cover the Fabric VarInt payload and ordered response-channel registration/probe policy, plus Paper's configuration-only response, malformed payload rejection, and supported-version response.

## Manual checks still required

- Confirm the pre-seeded `Xaero Sync Probe` waypoint is visible after joining, proving Xaero reads a pre-join file replacement without mixins.
- Create an End waypoint with the target Xaero build and replace the synthetic End test fixture with the observed bytes.
- If the target server exposes additional Xaero sub-world choices, create one waypoint in each and retain their actual filenames as fixtures. The recursive `dim%*/mw$*.txt` predicate already includes such files, but the compatibility evidence must reflect what the target server actually exposes.
