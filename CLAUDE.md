# BlockPops - Claude Instructions

## Remote Collections (B2 CDN)

### B2 Uploads

Use the `b2` CLI tool to upload files to Backblaze B2. The game client fetches assets from B2, not local storage.

```bash
# Upload a file to B2
b2 file upload blockpops-assets "local/file" "path/to/file" --content-type "application/json"
```

### Upload Workflow

When uploading a new model/texture/animation to B2:

1. Compute SHA-256 of the new file
2. Upload the file to B2 with `b2 file upload`
3. Update `fabric/run/blockpops-cache/manifest.json`:
   - Bump the `version` number
   - Update the `sha256` hash for the changed file
4. Upload the updated manifest to B2 with `b2 file upload`
5. Optionally update the local cache file too (for local testing without re-download)

### B2 Bucket Structure

- Bucket name: `blockpops-assets`
- CDN URL: `https://f003.backblazeb2.com/file/blockpops-assets`
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
