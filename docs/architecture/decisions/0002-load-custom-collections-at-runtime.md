# ADR 0002: Load Custom collections at runtime

## Status

Accepted.

## Context

BlockPops 1.5.0 let an operator enable "Custom" collections: collections published on the
BlockPops CDN (`https://f003.backblazeb2.com/file/blockpops-assets`) and found by an access code.
The server downloaded each collection's definition; every player downloaded its models, animations
and textures and loaded them while the game ran. The shared-source migration left the feature out.
Bringing it back had to deal with what 1.5.0 got wrong and with what changed underneath it:

- 1.5.0 concatenated codes and ids into URLs, kept files whose SHA-256 did not match the manifest,
  read unbounded bodies, resolved manifest paths without checking them and accepted any namespace,
  so a manifest entry could write outside the cache or replace vanilla textures;
- it put models and animations into GeckoLib's own caches. Those are rebuilt on every resource
  reload, and GeckoLib 5.5 (Minecraft 26.x) builds them as unmodifiable maps, so the same approach
  throws on every 26.x target;
- it drew `extra_textures` with a GeckoLib 4 render layer, which has no GeckoLib 5.5 equivalent;
- the release spans GeckoLib 4.7, 4.8, 5.1, 5.2 and 5.5 and three render paths (immediate drawing,
  the 1.21.5 render state, and 26.x submission), so anything done in the renderers is done many
  times over;
- clients without the feature must never be sent a model id they cannot load: GeckoLib 4 and 5
  throw on a missing model.

## Decision

- **A Minecraft-free, verified store** (`com.theplumteam.remote`). It reads from one fixed HTTPS
  origin, with redirects off and timeouts. Every file comes from an allowlisted per-collection
  layout in the `blockpops` namespace and has byte and dimension caps. It is written only once its
  SHA-256 matches the manifest, through a staging file and an atomic move. Paths are resolved
  inside the cache root and refuse link components. JSON is parsed strictly. A failing file only
  leaves its own asset missing.
- **Runtime registries consulted first** (`client.runtime`). `RuntimeGeo` bakes models and
  animations with the running GeckoLib's own bakers, and the figure models ask it before GeckoLib
  through `getBakedModel` and the animation lookup. `RuntimeTextures` uploads dynamic textures under
  the ids the collection names. Nothing can take an id a bundled resource has. An id that has been
  announced but not loaded yet is drawn as the default figure.
- **`hidden_bones` and `extra_textures` as data transforms** (`FigureVariants`), so every GeckoLib
  version draws them in one ordinary pass. Hidden bones lose their cubes. Extra textures are placed
  beside the skin's texture in one image, and their bones' UVs are moved onto their part.
- **The server owns the list.** The enabled ids are stored per world, with a one-time import of
  1.5.0's `blockpops_world.dat`. Operators at level 2 enable a collection by code, which the server
  looks up, or disable one by id. Definitions are registered from the cache at start and refreshed
  in the background.
- **Every definition is checked** on the server and again on the client. It may only name its own
  files or the shared default model and poses.
- **Custom collections travel in their own packet**, never in the shared collection sync. The
  packet carries the definitions and the files pinned to their hashes, and the client announces
  every file before registering anything.

## Consequences

- Custom collections work on every release target, including 26.x, and survive F3+T.
- The manifest is not signed: trust rests on TLS and on whoever controls the bucket, bounded by the
  allowlist, the limits and the definition check. Signing it is a possible follow-up.
- The CDN, its manifest and its `codes/` documents are published outside this repository. A file
  re-uploaded without regenerating the manifest is refused until the manifest is republished.
- The CDN sees the IP of every player who downloads. Downloads are always on, as in 1.5.0.
- CI never contacts the CDN; coverage of the remote store relies on fixtures and a fake source.
