# Figure Collections System Guide

This guide explains how to add new figure collections to BlockPops without modifying code.

## Overview

The Figure Collections system allows you to create themed sets of figures (e.g., JoJos, Jujutsu Kaisen) with custom box textures. Each collection is defined through a simple JSON file and associated assets.

## Quick Start: Adding a New Collection

### 1. Register the Collection ID

Edit `forge/src/main/java/com/theplumteam/figure/BuiltInCollections.java` and add your collection ID to the list:

```java
public static final List<String> COLLECTION_IDS = List.of(
    "default",
    "jojos",
    "jujutsukaisen",
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
│   ├── jojos.json
│   ├── jujutsukaisen.json
│   └── your_collection_name.json
├── textures/
│   ├── block/box/
│   │   ├── default.png
│   │   ├── jojos.png
│   │   ├── jujutsukaisen.png
│   │   └── your_collection_name.png
│   └── figure/
│       ├── jojos/
│       │   ├── jotaro.png
│       │   ├── dio.png
│       │   └── joseph.png
│       ├── jujutsukaisen/
│       │   ├── gojo.png
│       │   ├── yuji.png
│       │   └── sukuna.png
│       └── your_collection_name/
│           └── character_1.png
├── geo/figure/
│   ├── jojos/
│   │   └── jotaro.geo.json
│   ├── jujutsukaisen/
│   │   └── gojo.geo.json
│   └── your_collection_name/
│       └── character_1.geo.json
└── animations/figure/
    ├── jojos/
    │   └── jotaro.animation.json
    ├── jujutsukaisen/
    │   └── gojo.animation.json
    └── your_collection_name/
        └── character_1.animation.json
```

## Adding Figures to Existing Collections

To add a new figure to an existing collection:

1. Edit the collection JSON file (e.g., `collections/jojos.json`)
2. Add a new figure entry to the `figures` array:

```json
{
  "id": "new_character",
  "name": "New Character",
  "model": "blockpops:geo/figure/jojos/new_character.geo.json",
  "texture": "blockpops:textures/figure/jojos/new_character.png",
  "animation": "blockpops:animations/figure/jojos/new_character.animation.json"
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

A figure on its own model usually needs some of these optional keys, next to `model`:

| Key | Meaning |
|---|---|
| `pose_animation` | Its own `Pose_Stand` and `Pose_Sit`, for a model whose bones the shared poses (`figure_poses`) do not drive. Without it a sitting figure is posed as a skin figure. |
| `scale` | Its size in the box and as a placed figure, scaled about the centre of the block. Default `1`. |
| `gui_scale` | Its size in the collection screen. Defaults to `scale`. |
| `box_face_uv` | Where the face shown on the front of the box lies in its texture, in texels: `{"u": 8, "v": 8, "w": 8, "h": 8, "tex_width": 128, "tex_height": 128}`. Without it a figure on its own model shows no face there. |
| `offset_x`, `offset_y`, `offset_z` | Its position in the box, in place of the box default. A box an operator has moved keeps its own position. |
| `show_box_face` | Whether the box shows its face, when the default is not right: by default it does with `box_face_uv`, or on the default skin model. `true` without `box_face_uv` reads the face from a skin layout, hat layer included. |
| `pose_locked` | `true` keeps the figure in its pose instead of cycling between standing and sitting on use. |
| `hidden_bones` | Bones of its model that are not drawn, with their children, such as alternative expressions. An alternative may name its own. |
| `extra_textures` | For a model laid out across several images: `[{"texture": "...", "bones": ["Head", ...]}]`. Those bones are drawn with that texture instead of the figure's. An alternative on another model does not use them. |

An entry in `alternatives` may also name its own `model` and `scale`, and its own `show_box_face` together with its own `box_face_uv`; whatever it leaves out comes from the figure. A skin on the default skin model always takes the shared poses; a skin on any other model takes the figure's `pose_animation`.

```json
{
  "id": "goku",
  "name": "Goku",
  "model": "blockpops:geo/figure/dragonballz/goku.geo.json",
  "texture": "blockpops:textures/block/figure/dragonballz/goku.png",
  "animation": "blockpops:animations/figure/box_figure_default.animation.json",
  "pose_animation": "blockpops:animations/figure/figure_poses_dragonball_goku.animation.json",
  "scale": 0.29,
  "gui_scale": 0.35,
  "box_face_uv": {"u": 8, "v": 8, "w": 8, "h": 8, "tex_width": 128, "tex_height": 128},
  "alternatives": [
    {
      "name": "Goku (SSJ)",
      "model": "blockpops:geo/figure/dragonballz/goku_ssj1.geo.json",
      "texture": "blockpops:textures/block/figure/dragonballz/goku_ssj1.png"
    }
  ]
}
```

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

## Custom Collections

Custom collections are published on the BlockPops CDN instead of shipping with the mod. An operator
(permission level 2) enables one in a world by its access code, from the **Custom** tab of the claw
machine's Settings or with a command:

```
/blockpops customcollections add <code>
/blockpops customcollections remove <id>
/blockpops customcollections list
```

The world remembers the list in `data/blockpops-custom-collections.json` (worlds from 1.5.0 are
imported once from `data/blockpops_world.dat`). Every player downloads the collection's files when
they join, into `blockpops-cache/` in the game directory, and a figure is drawn as the default figure
until its own files arrive.

A Custom collection is published with a definition at `data/blockpops/collections/<id>.json` and its
files under `assets/blockpops/`, all listed with their SHA-256 in the CDN's `manifest.json`, and an
access code at `codes/<code>.json` (`{"id": "<id>", "name": "<name>"}`). Only these layouts are
downloaded:

- `geckolib/models/figure/<id>/<name>.geo.json`
- `geckolib/animations/figure/figure_poses_<id>[_<name>].animation.json`
- `textures/block/figure/<id>/<name>.png`
- `textures/block/box/<id>.png` and `textures/block/box/logo/logo_<id>.png`

The definition may name only those files, plus the default figure model and the shared poses. Every
file is checked against its manifest hash, so a file uploaded again needs the manifest regenerated.

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
- `collections/jojos.json` - Multi-figure collection example
- `collections/jujutsukaisen.json` - Another multi-figure example
