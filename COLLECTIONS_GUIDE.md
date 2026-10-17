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
| `pose_locked` | `true` keeps the figure in its pose instead of cycling through its poses on use. |
| `poses` | Its own poses between standing and sitting, by their animation names in `pose_animation`: `["Pose_Applause", ...]`. Using the figure goes from `Pose_Stand` through each of them, in order, to `Pose_Sit`, then back to standing. Without it a figure only stands and sits. |
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

## World Collections

World collections are made in game by an operator (permission level 2) from their own files, and
belong to one world. They need no CDN and no access code: the server stores the files and sends
them to every player who joins. Their ids are `w-<slug>`, where the slug is 1 to 24 characters of
`a-z`, `0-9` and `_`, chosen when the collection is created and never changed.

### Making one in game

The screens are still in progress: for now the **Creator** tab appears only in a development
environment (a game started from Gradle or an IDE). Elsewhere, use the [commands](#commands) and
[import the files from a datapack](#importing-from-a-datapack).

Open a claw machine's Settings and choose the **Creator** tab (shown to operators once the server
has sent its World collections, even when it has none), then **Open creator**. The list shows every World collection of the world:

- create one from a slug and a name;
- **Edit** opens the collection: its name, author, background colour, box and logo on the left,
  and its figures on the right, with **Add figure**, **Edit figure** and **Retire**/**Restore**;
- **Archive** (click twice) takes the collection out of every machine, while boxes and figures
  already in the world keep showing; **Restore** offers it again;
- **Unlock** gives you every figure of the collection, like the Cheats tab does for bundled ones.

Each file button opens your system's file dialog. If no dialog appears (for example on a Linux
desktop without one, or behind a fullscreen window), click the button anyway to choose where the
file goes, then drag the file onto the game window or type its full path and press **Use path**.
Nothing is sent until you press **Save**; the file's name and your local path never leave your
computer, and PNG metadata is removed before upload.

### Files

| File | Rules |
|---|---|
| Skin (a skin figure's texture) | PNG, exactly 64×64, at most 64 KiB |
| Box texture | PNG, exactly 64×64 in the `box_block` layout, at most 64 KiB; or one of the 16 plain box colours |
| Logo | PNG, 16 to 512 px per side, at most 256 KiB |
| Model texture (a 3D model figure's texture) | PNG, 16 to 512 px per side, at most 512 KiB, in the proportions of the model's `texture_width:texture_height` |
| Model | Bedrock `.geo.json` from Blockbench, at most 512 KiB, 128 bones and 2048 cubes, every value within ±64 px |
| Animation (optional, 3D model figures only) | Bedrock `.animation.json`, at most 256 KiB and 4096 keyframes, with a `Pose_Stand` animation and optionally `Pose_Sit` |

PNGs may use any colour type with 8 bits per channel, or a palette or greyscale of 1 to 8 bits;
16-bit PNGs and animated PNGs are refused. Models may not use `poly_mesh` or `texture_meshes`.
Animations may only hold numbers: Molang expressions, sounds, particles and timelines are refused,
time keys are plain numbers in ascending order, and a looping animation needs a length (for
example `"animation_length": 0.05`).

A skin figure uses the default figure model and the shared poses. A 3D model figure without its
own animation uses the shared poses and keeps standing; with a `Pose_Sit` animation it can sit
like a bundled figure. A figure's scale is one of 0.5, 0.75, 1, 1.25, 1.5 and 2. A 3D model figure
is first fitted to its box from the rest pose of its model, whatever units it was built in: about as
tall as the bundled 3D figures, centred, with its feet on the floor of the box and its front behind
the window. Its scale then multiplies that fit, in the box, in hand, as a placed figure and in the
collection screen.

A world holds at most 32 World collections of at most 128 figures each (retired ones included),
1024 files and 64 MiB of files; one collection may use at most 16 MiB. Everything the collections
use together may hold at most 8,388,608 texture pixels, 16,384 cubes and 65,536 keyframes.

### Retiring, archiving and purging

Figures and collections are never deleted from the editor, because boxes in the world still name
them. A retired figure and every figure of an archived collection can no longer be drawn,
unlocked or given, but placed and held ones keep showing. Purging deletes a collection for good,
only by command, and its id can never be used again in that world; its boxes then show empty.

### Commands

Every command needs permission level 2.

```
/blockpops worldcollections list | quota | gc
/blockpops worldcollections create <slug> <name>
/blockpops worldcollections rename <id> <name>
/blockpops worldcollections author <id> <author>
/blockpops worldcollections color <id> <r> <g> <b>
/blockpops worldcollections boxcolor <id> <color|none>
/blockpops worldcollections archive <id>
/blockpops worldcollections restore <id>
/blockpops worldcollections figure retire|restore <id> <figure>
/blockpops worldcollections importpack <slug> "<namespace>:<path>"
/blockpops worldcollections purge <id> <id>
```

`gc` deletes files no collection uses that were uploaded more than a day ago; the server also does
this when it starts.

### Importing from a datapack

Server administrators without the editor can ship a collection in a datapack and import it with
`importpack`. The pack lives under `data/<namespace>/blockpops_world_collections/<path>/`, where
`<path>` is 1 to 32 characters of `a-z`, `0-9` and `_`:

```
collection.json
box.png                        (optional)
logo.png                       (optional)
figures/<figure id>/texture.png
figures/<figure id>/model.geo.json              (3D model figures)
figures/<figure id>/animation.animation.json    (optional, 3D model figures)
```

```json
{
  "name": "My Set",
  "author": "Neb",
  "background_color": [152, 48, 167],
  "box_color": "red",
  "figures": [
    {"id": "luffy", "name": "Luffy", "kind": "skin"},
    {"id": "robot", "name": "Robot", "kind": "model", "scale": 1.5}
  ]
}
```

`box_color` (a plain box colour) and `box.png` are alternatives; give at most one. No other key is
accepted. Importing the same pack again changes nothing, and a figure the pack no longer lists is
retired, not deleted. The files are checked exactly as uploads are.

### Where it is stored

The world keeps its World collections in `data/blockpops-world-collections.json` (with a `.bak`
copy of the previous version) and the files in `data/blockpops-world-collections/blobs/`, named by
their SHA-256. If the file cannot be read at start, or a stored file was damaged, the server says
so in its log: a damaged file is moved to `data/blockpops-world-collections/corrupt/` and its figure shows as the default figure
until the same file is uploaded again, and an unreadable store leaves every World collection
read-only until the files are fixed and the server restarts. Players keep downloaded files only in
memory, until they leave the server.

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
