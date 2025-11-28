# Create New Collection

Create a new BlockPops figure collection from assets in the ModelToProcess folder.

## Arguments
- $ARGUMENTS: The collection name (e.g., "Alien Stage", "My Collection")

## Instructions

You are creating a new collection called: **$ARGUMENTS**

### Step 1: Validate ModelToProcess Folder

First, check the `ModelToProcess` folder at the project root for required files:

**Required files:**
1. **Figure skins** - At least one PNG file for character skins (e.g., `1_charactername.png`, `charactername.png`)
2. **Logo** - `logo.png` for the collection logo
3. **Box texture** - `Box.png` or `box.png` for the box appearance

Run these checks:
```bash
ls -la "ModelToProcess"
```

Then verify each file is a **real PNG** (not WebP disguised as PNG):
```bash
file ModelToProcess/*
```

**STOP and notify the user if:**
- The ModelToProcess folder is empty or missing
- No figure skin PNGs are found
- logo.png is missing
- Box.png/box.png is missing
- Any file is WebP format instead of PNG (convert using: `ffmpeg -y -i "input.png" -update 1 -frames:v 1 "output.png"`)

### Step 2: Determine Collection ID

Convert the collection name to a lowercase ID with no spaces:
- "Alien Stage" → "alienstage"
- "My Cool Collection" → "mycoolcollection"

### Step 3: Create Collection JSON

Create `forge/src/main/resources/assets/blockpops/collections/{id}.json`:

```json
{
  "id": "{id}",
  "name": "{Name} Collection",
  "author": "The Plum Team",
  "author_url": "",
  "box_texture": "blockpops:textures/block/box/{id}.png",
  "logo": {
    "texture": "blockpops:textures/block/box/logo/logo_{id}.png",
    "position_x": -1.5,
    "position_y": -0.2,
    "position_z": 0.0,
    "scale_x": 6.0,
    "scale_y": 4.0,
    "scale_z": 1.0
  },
  "background_color": {
    "r": 30,
    "g": 30,
    "b": 50
  },
  "figures": [
    {
      "id": "{figure_id}",
      "name": "{Figure Name}",
      "model": "blockpops:geo/figure/box_figure_default.geo.json",
      "texture": "blockpops:textures/figure/{id}/{figure_id}.png",
      "animation": "blockpops:animations/figure/box_figure_default.animation.json"
    }
  ]
}
```

Extract figure names from the PNG files:
- `1_mizi.png` → id: "mizi", name: "Mizi"
- `character_name.png` → id: "character_name", name: "Character Name"

### Step 4: Update BuiltInCollections.java

Edit `forge/src/main/java/com/theplumteam/figure/BuiltInCollections.java`:

1. Add the collection ID to `COLLECTION_IDS` list
2. Add a case to `getDisplayName()` switch statement

### Step 5: Copy Assets

1. **Create figure textures directory:**
   ```bash
   mkdir "forge/src/main/resources/assets/blockpops/textures/figure/{id}"
   ```

2. **Copy and rename figure skins** (remove number prefixes):
   ```bash
   cp "ModelToProcess/1_name.png" "forge/src/main/resources/assets/blockpops/textures/figure/{id}/name.png"
   ```

3. **Copy logo:**
   ```bash
   cp "ModelToProcess/logo.png" "forge/src/main/resources/assets/blockpops/textures/block/box/logo/logo_{id}.png"
   ```

4. **Copy box texture:**
   ```bash
   cp "ModelToProcess/Box.png" "forge/src/main/resources/assets/blockpops/textures/block/box/{id}.png"
   ```

### Step 6: Create Item Model

Create `forge/src/main/resources/assets/blockpops/models/item/box_block_{id}.json`:

```json
{
  "parent": "builtin/entity",
  "gui_light": "front"
}
```

### Step 7: Add Language Entry

Edit `forge/src/main/resources/assets/blockpops/lang/en_us.json` and add:
```json
"block.blockpops.box_block_{id}": "{Name} Box",
```

### Step 8: Verify All Files

Confirm all files are in place:
- `collections/{id}.json`
- `textures/figure/{id}/*.png` (all figures)
- `textures/block/box/{id}.png`
- `textures/block/box/logo/logo_{id}.png`
- `models/item/box_block_{id}.json`
- Lang entry in `en_us.json`
- Entry in `BuiltInCollections.java`

### Step 9: Summary

Report to the user:
- Collection name and ID
- Number of figures added
- List of all created/modified files
- Remind to rebuild the project

## Important Notes

- All textures MUST be actual PNG files (64x64 for skins, any size for logo/box)
- WebP files disguised as PNG will cause missing textures (magenta/black)
- The author can be changed from "The Plum Team" if needed
- Logo position/scale may need adjustment after testing in-game
