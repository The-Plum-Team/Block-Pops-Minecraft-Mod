# ADR 0003: Accept operator-uploaded World collections

## Status

Accepted. It amends the rule in [`docs/ai/PROJECT.md`](../../ai/PROJECT.md) that Custom
collections are the only path by which the game loads content that does not ship with it.
[ADR 0002](0002-load-custom-collections-at-runtime.md) remains in force for Custom collections.

## Context

Operators want collections made in game, with their own skins, boxes, logos and GeckoLib models,
without publishing them to the BlockPops CDN. Custom collections cannot do that: their content is
published outside this repository and found by an access code.

The constraints ADR 0002 met still hold:

- GeckoLib 5.5 (Minecraft 26.x) builds its model and animation caches as unmodifiable maps, so
  content cannot be put into them;
- the release spans five GeckoLib versions and three render paths (immediate drawing, the 1.21.5
  render state and 26.x submission);
- clients must never be sent an id they cannot load: GeckoLib 4 and 5 throw on a missing model;
- `RuntimeAssets.clear()` wiped every runtime asset on each Custom sync.

Measured limits shaped the design:

- vanilla custom payloads are capped at 32767 bytes from client to server and 1048576 bytes from
  server to client, on every era from 1.20.1 to 26.3;
- the dedicated server has neither `NativeImage` nor LWJGL, so it cannot decode a PNG with the
  client's code;
- 26.3 replaced tinyfd and GLFW with SDL3, whose file-dialog filter accepts only bare extensions
  such as `png`, never `*.png`;
- `SyncDynamicCollectionsPacket` registers every collection it receives without checking it.

## Decision

- **The trust root is an operator at permission level 2**, which the server re-checks inside every
  upload and edit packet it handles. Players who are not operators only receive content.
- **Two sources only**: that operator's upload in the `WorldUpload*Packet`s, or a datapack's
  `blockpops_world_collections/` directory imported by command. Never a URL, a file path a server
  names, or the CDN.
- **The same pure-Java validators run on the server and again on the client**
  (`com.theplumteam.worldcontent`):
  - `PngValidator` accepts every legal bit depth up to 8, bounds the inflate exactly, refuses a
    preset dictionary, APNG chunks and trailing data, and keeps only the canonical chunks;
  - every JSON document is read with `StrictJson`;
  - `GeoJsonCheck` and `AnimationJsonCheck` allowlist the Bedrock geometry and animation schemas,
    without Molang, with geometry bounded to ±64 px and numeric time keys in ascending order;
  - `WorldCollectionCheck` checks the materialized definition, which may name only its own files,
    the default figure model, the shared poses and the bundled box colours.
- **Limits**: `WorldContentLimits` holds the byte quotas per file, per collection and per world,
  *and* the decoded budgets over everything any record references (pixels, cubes, keyframes),
  which both sides check. The server alone applies the per-player rate limits on uploads and
  edits (`WorldRateLimits`) and the upload concurrency caps and timeouts (`WorldUploads`).
- **Storage** is content-addressed by SHA-256 under the world's `data/` directory, with atomic
  writes, `.bak` and `.corrupt` copies, garbage collection of unreferenced files after a day, and
  a degraded read-only mode when the store cannot be read or breaks a limit.
- **Transport**: World collections travel only in `SyncWorldCollectionsPacket` and their files only
  in `WorldAssetChunkPacket`, gated by `canPlayerReceive` from 1.21 (Architectury's Forge
  networking for 1.20.1 never learns which ids a client receives, and every client there has the
  mod) and paced by a per-player window of unacknowledged bytes. They never ride the shared collection sync, the Custom packet or
  `blockpops-cache/`, and the shared sync refuses their ids on both sides.
- **Runtime assets** go into the `client.runtime` registries under ids `WorldContentIds` owns. They
  are never written into GeckoLib's caches, are refused when a bundled resource has the same id,
  and are left alone by `RuntimeAssets.clear()`, which now clears only the ids World content does
  not own.
- **Policies**:
  - collection ids are `w-<slug>`, outside the bundled and CDN id grammars;
  - a collection is archived, not deleted, from the editor; purging is a command that tombstones
    the id so it is never reused;
  - the editor picks files with the native dialog (tinyfd below 26.3, SDL3 from 26.3), with
    drag-and-drop and a typed path as fallbacks;
  - the client keeps downloaded files in memory only, for one server session.

## Consequences

- Operators can show any image or geometry within the limits to every player, so moderation is the
  server owner's job. No scripts, sounds or URLs can be uploaded.
- A join transfers at most the referenced files (at most 64 MiB stored, 8 Mpx decoded), paced.
- Local file paths and PNG ancillary metadata never leave the client. The deflate stream of a PNG
  is passed through unchanged.
- Packaged E2E can cover seeding by datapack import and rendering, including a held box, but not
  the native picker or the operator-only editor: packaged players are not operators.
- World ids never enter the global hidden list; archiving is the per-world control.
- A corrupt or missing stored file keeps its figure registered and drawn as the default figure
  until an operator uploads the same file again.
