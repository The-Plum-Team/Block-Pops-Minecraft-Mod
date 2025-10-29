# Figure Collections

This directory contains JSON definitions for figure collections.

## Format

Each collection is defined in a JSON file with the following structure:

```json
{
  "id": "collection_id",
  "name": "Display Name",
  "box_texture": "blockpops:textures/block/box/texture_name.png",
  "figures": [
    {
      "id": "figure_id",
      "name": "Figure Display Name",
      "model": "blockpops:geo/figure/collection_id/figure_id.geo.json",
      "texture": "blockpops:textures/figure/collection_id/figure_id.png",
      "animation": "blockpops:animations/figure/collection_id/figure_id.animation.json"
    }
  ]
}
```

## Available Collections

- **default.json** - Default collection with basic figures
- **star_wars.json** - Star Wars themed collection
- **jojos.json** - JoJo's Bizarre Adventure themed collection

## Adding New Collections

See the main COLLECTIONS_GUIDE.md in the root directory for detailed instructions on adding new collections.

### Quick Steps:

1. Add collection ID to `BuiltInCollections.java`
2. Create `collection_name.json` in this directory
3. Add box texture to `textures/block/box/`
4. Add figure assets to `textures/figure/collection_name/`
5. Add models to `geo/figure/collection_name/`
6. Add animations to `animations/figure/collection_name/`
7. Build and test!

## Notes

- All resource paths use the `blockpops:` namespace
- Figure textures should be standard Minecraft skins (64x64 PNG)
- You can reuse the default model and animation for most figures
- Collections are loaded at resource pack reload time
