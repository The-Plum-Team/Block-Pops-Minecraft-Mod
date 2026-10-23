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

The screens are still in progress: for now the creator opens only in a development environment (a
game started from Gradle or an IDE). Elsewhere, use the [commands](#commands) and [import the files
from a datapack](#importing-from-a-datapack).

There, an operator opens it once the server has sent its World collections, even when it has none:
with the **Creator** button of a claw or gacha machine's screen, with **Open creator** in the
**Creator** tab of the machine's Settings, or with the client command `/blockpopscreator`, which
opens it without a machine.

**Collections in this world** shows every World collection as a card: its box with the logo, its
name and author, how many figures it has, are in the draw and are retired, how much space its files
take, and a badge saying whether it is published, a draft or archived, or how many of its files the
server lost. Tabs show every collection, or only the published ones, the drafts or the archived
ones, the search (Ctrl+F) finds a name or an author, and the list sorts by the last edit, the name or the number of
figures. The meter at the top shows how many collections and how much of the world's file space are
in use. Each card has **Edit** (or Enter) and a menu:

- **Put in this machine**, when the creator was opened from a machine. It works only for a
  collection that is in the machines: published, not archived and not hidden by the server;
- **Give me every figure** (for testing), like the Cheats tab does for bundled ones;
- **Duplicate** makes a draft copy (see [below](#retiring-archiving-and-purging));
- **Archive...** takes the collection out of every machine after asking, while boxes and figures
  already in the world keep showing, and offers to undo it for a few seconds; **Restore** offers it
  again;
- **Delete forever...** deletes an archived collection or a draft once you type its name.

**Import from a data pack...** at the bottom starts a [datapack import](#importing-from-a-datapack)
on the server, and **Done** goes back.

**New collection** (the first card, or Ctrl+N) asks for a name, an author (your player name to
start with), an optional logo and a background colour, which **Take from the logo** can pick for
you, and shows the machine screen as you type. The id is made from the name; **Advanced options**
shows it and lets you type another, because it cannot change later. **Create and add figures**
creates the collection as a draft, out of every machine until you publish it, and opens it in the
editor.

The editor has three tabs. Its top bar counts the unsaved changes and has **Undo** (Ctrl+Z),
**Save** (Ctrl+S) and **Review and publish**; leaving with unsaved changes asks whether to save
them, discard them or stay. **Next steps** under the tabs says what the collection still needs.
Every change shows at once in the editor's views, unsaved files included, and is sent only with
**Save** unless said otherwise below.

- **Identity & box** sets the name, the author (or **Use my player name**), an optional author
  link, which players can open from the machine screen, the background colour, the box and the
  logo. The box is a solid colour, your own 64×64 image or the mod's default box; **Download box
  template** writes a template with each face in its own colour, and a guide naming the faces, to
  `blockpops/templates/` in the game folder and opens that folder. The logo's size on the box (50
  to 150 %) and position can be changed. The view beside it shows the box, the box placed in the
  world or the machine screen; dragging turns it, and **Light** shows it by day or by night.

- **Figures** shows every figure as a card, with a search and chips for the skins, the 3D models,
  the retired figures and those with a problem. **Add figure** adds an empty one. **Add
  several...**, or dropping files or a folder on the grid, first lists what the files make: a skin
  figure for each 64×64 PNG and a 3D model figure for each model and texture with the same name
  (with the poses of that name, if any), named after the file with or without its numbers, at a
  default size. Each row shows a preview, and its name and size can be changed in place (Tab moves
  between them); a row whose files break a rule says why and is skipped, and one whose id is taken
  gets another. **Add** puts the ready rows in the collection and saves them at once, a few figures
  at a time, with a bar you can pause or stop: what was saved stays saved, and the rest stays in
  the editor, unsaved. Ctrl or Shift + click picks several figures to retire, restore, resize or
  duplicate together, and dragging a card (with every figure shown and no search) changes their
  order.

  Beside the grid, the inspector shows the selected figure live: loose beside a 1x figure for
  scale, in its box, or in the machine's grid as discovered or not, standing or sitting, and
  dragging turns it. **‹ ›** or the left and right arrow keys step through the cards. There you
  rename it (a figure never saved takes the id its name makes, fixed once saved), set its size,
  make it a Minecraft skin or a Blockbench model, and drop or choose each of its files, each with
  its checks. **Use my skin** fills a skin figure with your own skin from the game's skin cache on
  this computer, without downloading anything, or with the default skin when the cache has none.
  Under the files, **Rarity** sets how often the figure comes out, with its chance in one draw
  shown live; **Skin variants** adds up to 4 other textures, each named, which **‹ ›** in the view
  steps through; and **Box face** turns the face on the box on or off, where a 3D model figure's
  face is chosen by dragging across its texture (after a click the arrow keys move it one pixel at
  a time, and resize it with Shift). The links under it retire the figure from the draw or restore
  it, or, in a draft, delete it for good; **Save figure** sends that figure alone and lists what is
  still wrong while it cannot.

- **Review & publish** lists what the collection still needs, each with a link to where it is
  fixed, and how its draw works: every figure's chance, this server's token rules and about how
  many days of tokens completing the collection takes. **Draw by rarity** sets the weight of each
  rarity, **Collection type** puts it in the claw machine as boxed figures or in the gacha
  machine's **Plushes** tab, handed out as figure blocks, and **Visibility** chooses between draft
  and published, or, once published, between published and archived. **How players will see it**
  shows the machine screen as a new player or a collector sees it, with **Test pull** and **Pull
  100 times**, which spend no tokens and give nobody anything. To publish a draft, choose
  **Published** and press **Publish collection**, which saves every change and publishes in one
  step, and stays disabled while the list has a ✖. A dialog then offers to put the collection in
  this machine, give you every figure, show players' progress, go back to the collections or
  archive it again.

Each file box says what it takes and opens your system's file dialog when clicked. A file dropped
on the game window goes to the box under the pointer, else to the only box that takes that kind of
file, else the game asks which one. If no dialog can be shown (for example on a Linux desktop
without one), clicking asks for the file's full path instead, and **Use path** reads it; if a
dialog hides behind a fullscreen window, leave fullscreen or drop the file. Nothing is sent until
you press **Save** (or **Add** in **Add several...**, or **Save figure**); the file's name and your
local path never leave your computer, and PNG metadata is removed before upload.

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

A figure may have up to 4 skin variants, which using a placed figure cycles through: each is only
another texture (a 64×64 skin for a skin figure, or a texture in the model's proportions for a 3D
model figure) and shares the figure's model, poses, size and box face. A collection may have at
most 128 variants in all.

A world holds at most 32 World collections of at most 128 figures each (retired ones included),
1024 files and 64 MiB of files; one collection may use at most 16 MiB, and its definition at most
96 KiB. Everything the collections use together may hold at most 8,388,608 texture pixels, 16,384
cubes and 65,536 keyframes.

### Retiring, archiving and purging

Published figures and collections are never deleted from the editor, because boxes in the world
still name them. A retired figure and every figure of an archived collection can no longer be drawn
or given, but placed and held ones keep showing. An operator's **Give me every figure** still gives
the figures of an archived collection that are not retired, and no machine can be set to an
archived collection. Purging deletes a collection for good, and its id can never be used again in
that world; its boxes then show empty. The `purge` command, given the id twice, deletes any
collection; the editor's **Delete forever...** deletes only an archived collection or a draft, once
you type its name, so archive a collection before deleting it there.

A draft is a collection that has not been published yet. Operators see and edit it, and **Give me
every figure** gives its figures for testing, but no machine offers it. Other players are not sent
it at all: a player who is deopped stops seeing drafts within a second, and one made operator
starts. Only a draft's figures can be deleted outright rather than retired; a box unlocked from a
deleted figure then shows empty. A draft cannot be archived. Publishing it, which needs at least
one figure that is not retired, puts it in the machines and cannot be undone; archive the
collection to take it out again. The `create` command and a datapack import of a new collection
make it published at once; importing into a draft keeps it a draft. An import started from the
editor can make a new collection a draft instead.

Duplicating a collection in the editor makes a draft copy with a new slug and name: every figure,
retired ones included, its files and its extras. The copy uses the same stored files, so nothing is
uploaded again and no file quota grows.

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
/blockpops worldcollections publish <id>
/blockpops worldcollections figure retire|restore <id> <figure>
/blockpops worldcollections importpack <slug> "<namespace>:<path>"
/blockpops worldcollections purge <id> <id>
```

`gc` deletes files no collection uses that were uploaded more than a day ago; the server also does
this when it starts.

Commands have no rate limit. The editor does: an operator may make 5 edits a second and 60 a
minute (a batch of up to 16 changes counts as one), start 2 datapack imports a minute, and begin
120 uploads or 32 MiB of uploads a minute. A file the server already has costs nothing, and only
one upload per player runs at a time.

### Importing from a datapack

Server administrators without the editor can ship a collection in a datapack and import it with
`importpack`; operators in the editor can start the same import by typing `<namespace>:<path>`.
The game only ever reads a pack from the server's own datapacks, by that exact name. The pack lives
under `data/<namespace>/blockpops_world_collections/<path>/`, where `<path>` is 1 to 32 characters
of `a-z`, `0-9` and `_`:

```
collection.json
box.png                        (optional)
logo.png                       (optional)
figures/<figure id>/texture.png
figures/<figure id>/variant_<n>.png             (one per variant, n = 1 to 4)
figures/<figure id>/model.geo.json              (3D model figures)
figures/<figure id>/animation.animation.json    (optional, 3D model figures)
```

```json
{
  "name": "My Set",
  "author": "Neb",
  "background_color": [152, 48, 167],
  "box_color": "red",
  "author_url": "https://example.com/neb",
  "logo_layout": {"scale_pct": 120, "offset_x": 0, "offset_y": -10},
  "rarity_weights": [100, 40, 15, 5],
  "figures": [
    {"id": "luffy", "name": "Luffy", "kind": "skin", "rarity": "rare",
     "variants": [{"name": "Gear 5"}], "box_face": {"show": true}},
    {"id": "robot", "name": "Robot", "kind": "model", "scale": 1.5,
     "box_face": {"show": true, "uv": [8, 8, 8, 8]}}
  ]
}
```

Required keys are `name`, `author`, `background_color` and `figures`, and each figure's `id`,
`name` and `kind`. The optional keys are:

- `box_color`: a plain box colour, as an alternative to `box.png`; give at most one;
- `author_url`: a link to the author's page, `http` or `https`, at most 256 characters of
  printable ASCII without spaces. The game never opens it by itself: players see it under the
  collection's name and clicking it opens it in their browser the way a chat link opens, after
  the game asks them (unless they turned that prompt off in their chat settings);
- `logo_layout`: needs `logo.png`. `scale_pct` sizes the logo from 50 to 150 % of its automatic
  fit, and `offset_x` and `offset_y` move it right and up by -40 to 40 tenths of a model pixel;
- `plush`: `true` lists the collection in the gacha machine's **Plushes** tab. Its figures are
  handed out as figure blocks showing the figure, because real plush blocks exist only for the
  bundled plushes;
- `rarity_weights`: the draw weights of common, rare, epic and legendary figures, each 1 to 1000
  (100, 40, 15 and 5 when left out). They matter only once a figure has a rarity: with the
  defaults each legendary figure comes up once for every 20 times each common figure does. A
  guaranteed token still gives a figure the player has not found yet, picked by the same weights
  among those;
- a figure's `scale`: one of the sizes listed under [Files](#files);
- a figure's `rarity`: `rare`, `epic` or `legendary`; a figure without one is common, and while
  every figure is common they are all equally likely;
- a figure's `variants`: up to 4 entries `{"name": …}`, each with its texture in
  `figures/<figure id>/variant_<n>.png`, numbered in order from 1;
- a figure's `box_face`: whether its box shows its face. A skin figure gives only `show`; a 3D
  model figure also gives `uv`, the face's `[u, v, width, height]` in its texture's pixels.

No other key is accepted. Importing the same pack again changes nothing, and a figure the pack no
longer lists is retired, not deleted, keeping its rarity, variants and box face. The files are
checked exactly as uploads are.

### Where it is stored

The world keeps its World collections in `data/blockpops-world-collections.json` (with a `.bak`
copy of the previous version) and the files in `data/blockpops-world-collections/blobs/`, named by
their SHA-256. If the file cannot be read at start, or a stored file was damaged, the server says
so in its log: a damaged file is moved to `data/blockpops-world-collections/corrupt/` and its
figure shows as the default figure until the same file is uploaded again, and an unreadable store
leaves every World collection read-only until the files are fixed and the server restarts. Players
keep downloaded files only in memory, until they leave the server.

Players and the server need the same BlockPops version. The file keeps format 1, so a world made
with an earlier BlockPops loads unchanged, but once a collection is created or changed (each change
records when it was made), or uses drafts, rarities, variants, a box face, a logo layout, an author
link or the plush tab, an older BlockPops cannot read the file. That older version then sets the file aside as
`blockpops-world-collections.json.corrupt` and runs on the `.bak` copy when that still reads,
losing the newest change, or else leaves every World collection read-only. Do not move such a world
back to an older BlockPops.

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
