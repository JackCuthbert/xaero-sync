# Target-version compatibility evidence

This records manual evidence for the exact v1 compatibility target: Minecraft `26.2`, Fabric Loader `0.19.5`, Fabric API `0.159.0+26.2`, Xaero Minimap Fabric `26.4.2`, and Paper `26.2` build `121`.

## Confirmed

- Xaero creates `dim%0/mw$default_1.txt` for Overworld waypoints.
- Xaero creates the sibling `dim%-1/mw$default_1.txt` for Nether waypoints.
- On the clean localhost connection, Xaero created `dim%0/mw0,1,0_2.txt` for the automatic Overworld. Its `mw0,1,0` prefix matched the unsynced `config.txt` value `defaultMultiworldId:mw0,1,0`.
- On the same connection, Xaero created `dim%1/mw0,1,0_1.txt` for an End waypoint. This confirms the displayed numeric suffix is dimension-local.
- `config.txt` is connection-local minimap configuration and is excluded.
- The Fabric and Paper artifacts load on the pinned client and server without entry-point errors.
- The complete configuration probe round trip succeeds before world entry on Paper `26.2` build `121`: the server received it before its join message at `16:16:34`, and the client received the response on its Netty configuration thread at `16:21:29`.
- A pre-seeded `mw$default_1.txt` was visible after joining without mixins, proving pre-join loading. Because its identifier did not match the connection's current automatic-world identifier, Xaero correctly displayed it as a separate non-automatic sub-world.
- Platform unit tests cover the Fabric VarInt payload and ordered response-channel registration/probe policy, plus Paper's configuration-only response, malformed payload rejection, and supported-version response.

The target server exposes the automatic world plus the manually seeded default world captured above. If its Xaero sub-world configuration changes later, retain the actual new filenames as fixtures; the recursive `dim%*` and `mw*.txt` predicate is deliberately independent of a specific sub-world identifier.
