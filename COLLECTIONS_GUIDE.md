# Figure Collections System Guide

This guide explains how to add new figure collections to BlockPops without modifying code.

## Overview

The Figure Collections system allows you to create themed sets of figures (e.g., Star Wars, JoJos) with custom box textures. Each collection is defined through a simple JSON file and associated assets.

## Quick Start: Adding a New Collection

### 1. Register the Collection ID

Edit `forge/src/main/java/com/theplumteam/figure/BuiltInCollections.java` and add your collection ID to the list:

```java
public static final List<String> COLLECTION_IDS = List.of(
    "default",
    "star_wars",
    "jojos",
    "your_collection_name"  // Add your new collection here
);
```

Also add a display name in the `getDisplayName()` method:

```java
case "your_collection_name" -> "Your Collection Name";
```

### 2. Create the Collection JSON

Create a new file: `forge/src/main/resources/assets/blockpops/collections/your_collection_name.json`

```json
{
  "id": "your_collection_name",
  "name": "Your Collection Name",
  "box_texture": "blockpops:textures/block/box/your_collection_name.png",
  "figures": [
    {
      "id": "character_1",
      "name": "Character 1",
      "model": "blockpops:geo/figure/your_collection_name/character_1.geo.json",
      "texture": "blockpops:textures/figure/your_collection_name/character_1.png",
      "animation": "blockpops:animations/figure/your_collection_name/character_1.animation.json"
    },
    {
      "id": "character_2",
      "name": "Character 2",
      "model": "blockpops:geo/figure/your_collection_name/character_2.geo.json",
      "texture": "blockpops:textures/figure/your_collection_name/character_2.png",
      "animation": "blockpops:animations/figure/your_collection_name/character_2.animation.json"
    }
  ]
}
```

### 3. Create Asset Directories

```bash
mkdir -p forge/src/main/resources/assets/blockpops/geo/figure/your_collection_name
mkdir -p forge/src/main/resources/assets/blockpops/textures/figure/your_collection_name
mkdir -p forge/src/main/resources/assets/blockpops/animations/figure/your_collection_name
```

### 4. Add Assets

#### Box Texture
- Path: `textures/block/box/your_collection_name.png`
- Recommended size: Match the existing box textures
- This is the texture for the box block itself

#### Figure Textures (Minecraft Skins)
- Path: `textures/figure/your_collection_name/character_name.png`
- Format: Standard Minecraft skin (64x64 pixels)
- These are the skins that will be displayed on the figures

#### Figure Models
- Path: `geo/figure/your_collection_name/character_name.geo.json`
- You can copy the default model: `geo/figure/box_figure_default.geo.json`
- The default model works with standard Minecraft skins

#### Figure Animations
- Path: `animations/figure/your_collection_name/character_name.animation.json`
- You can copy the default animation: `animations/figure/box_figure_default.animation.json`
- Or create custom animations using Blockbench

### 5. Build and Test

1. Build the mod: `./gradlew build`
2. Launch Minecraft
3. Find your new box in the BlockPops creative tab
4. Place it and test the figures

## Directory Structure

```
forge/src/main/resources/assets/blockpops/
├── collections/
│   ├── default.json
│   ├── star_wars.json
│   ├── jojos.json
│   └── your_collection_name.json
├── textures/
│   ├── block/box/
│   │   ├── default.png
│   │   ├── star_wars.png
│   │   ├── jojos.png
│   │   └── your_collection_name.png
│   └── figure/
│       ├── star_wars/
│       │   ├── luke.png
│       │   ├── darth_vader.png
│       │   └── yoda.png
│       ├── jojos/
│       │   ├── jotaro.png
│       │   ├── dio.png
│       │   └── joseph.png
│       └── your_collection_name/
│           └── character_1.png
├── geo/figure/
│   ├── star_wars/
│   │   └── luke.geo.json
│   ├── jojos/
│   │   └── jotaro.geo.json
│   └── your_collection_name/
│       └── character_1.geo.json
└── animations/figure/
    ├── star_wars/
    │   └── luke.animation.json
    ├── jojos/
    │   └── jotaro.animation.json
    └── your_collection_name/
        └── character_1.animation.json
```

## Adding Figures to Existing Collections

To add a new figure to an existing collection:

1. Edit the collection JSON file (e.g., `collections/star_wars.json`)
2. Add a new figure entry to the `figures` array:

```json
{
  "id": "new_character",
  "name": "New Character",
  "model": "blockpops:geo/figure/star_wars/new_character.geo.json",
  "texture": "blockpops:textures/figure/star_wars/new_character.png",
  "animation": "blockpops:animations/figure/star_wars/new_character.animation.json"
}
```

3. Add the corresponding assets (texture, model, animation)
4. Rebuild the mod

## Creating Custom Skins

### Using Existing Minecraft Skins

1. Find a Minecraft skin (64x64 PNG)
2. Save it as `textures/figure/collection_name/character_name.png`
3. Use the default model and animation
4. The figure will automatically use the skin!

### Creating Custom Models

1. Download [Blockbench](https://www.blockbench.net/)
2. Open the default model: `geo/figure/box_figure_default.geo.json`
3. Modify the model as needed
4. Export as GeckoLib model
5. Save to `geo/figure/collection_name/character_name.geo.json`

### Creating Custom Animations

1. Open your model in Blockbench
2. Create animations in the Animation tab
3. Export as GeckoLib animation
4. Save to `animations/figure/collection_name/character_name.animation.json`

## Tips

- **Reuse Assets**: You can reference the same model/animation for multiple figures with different textures
- **Standard Format**: The default model works with standard Minecraft 64x64 skins
- **Box Textures**: Box textures should match the theme of your collection
- **Testing**: Use `/reload` in-game to reload resources without restarting

## Example: Adding a "Marvel" Collection

1. Add to `BuiltInCollections.java`:
```java
"marvel"  // in COLLECTION_IDS list
case "marvel" -> "Marvel";  // in getDisplayName()
```

2. Create `collections/marvel.json`:
```json
{
  "id": "marvel",
  "name": "Marvel Collection",
  "box_texture": "blockpops:textures/block/box/marvel.png",
  "figures": [
    {
      "id": "spiderman",
      "name": "Spider-Man",
      "model": "blockpops:geo/figure/marvel/spiderman.geo.json",
      "texture": "blockpops:textures/figure/marvel/spiderman.png",
      "animation": "blockpops:animations/figure/marvel/spiderman.animation.json"
    }
  ]
}
```

3. Add assets:
   - `textures/block/box/marvel.png` (box texture)
   - `textures/figure/marvel/spiderman.png` (Minecraft skin)
   - `geo/figure/marvel/spiderman.geo.json` (copy from default)
   - `animations/figure/marvel/spiderman.animation.json` (copy from default)

4. Build and test!

## Troubleshooting

**Collection not loading?**
- Check the JSON syntax (use a JSON validator)
- Ensure file paths match exactly (case-sensitive!)
- Check the game logs for errors

**Textures not showing?**
- Verify texture paths in JSON match actual file locations
- Ensure textures are in PNG format
- Check texture dimensions (64x64 for skins)

**Figures not animating?**
- Verify animation file exists and path is correct
- Check animation JSON syntax
- Default animation works for most cases

## Need Help?

Check the example collections:
- `collections/default.json` - Simple single-figure collection
- `collections/star_wars.json` - Multi-figure collection example
- `collections/jojos.json` - Another multi-figure example
