# BlockPops - Claude Instructions

## Remote Collections (R2 CDN)

### Wrangler Uploads

**ALWAYS use `--remote` when uploading to R2.** Never upload to local only. The game client fetches assets from the remote CDN, not local wrangler dev server.

```bash
# CORRECT - uploads to remote R2 (what the game actually uses)
wrangler r2 object put "blockpops-assets/path/to/file" --file="local/file" --content-type="application/json" --remote

# WRONG - only uploads locally, game will never see it
wrangler r2 object put "blockpops-assets/path/to/file" --file="local/file" --content-type="application/json"
```

### Upload Workflow

When uploading a new model/texture/animation to R2:

1. Compute SHA-256 of the new file
2. Upload the file to R2 with `--remote`
3. Update `fabric/run/blockpops-cache/manifest.json`:
   - Bump the `version` number
   - Update the `sha256` hash for the changed file
4. Upload the updated manifest to R2 with `--remote`
5. Optionally update the local cache file too (for local testing without re-download)

### R2 Bucket Structure

- Bucket name: `blockpops-assets`
- CDN URL: `https://pub-6c5186db85af42818afa82b1f49fb875.r2.dev`
- Manifest: `manifest.json` (version number + file list with SHA-256 hashes)
- Collection JSON: `data/blockpops/collections/<id>.json`
- Models: `assets/blockpops/geckolib/models/figure/<collection>/<figure>.geo.json`
- Textures: `assets/blockpops/textures/block/figure/<collection>/<figure>.png`
- Animations: `assets/blockpops/geckolib/animations/figure/<animation>.animation.json`

### BBModel Conversion

Use `tools/bbmodel_to_geo.py` to convert BBModel files to GeckoLib .geo.json format. The script:
- Handles visibility flags (hidden bones in Blockbench get their cubes stripped)
- Sets proper geometry identifiers (`geometry.blockpops.<collection>.<figure>`)
- Handles CPM format models
- Updates the collection JSON and manifest automatically

## Build Commands

- Fabric: `./gradlew fabric:build`
- NeoForge: `./gradlew neoforge:build`
- Run client (Fabric): `./gradlew fabric:runClient`
- Run client (NeoForge): `./gradlew neoforge:runClient`
