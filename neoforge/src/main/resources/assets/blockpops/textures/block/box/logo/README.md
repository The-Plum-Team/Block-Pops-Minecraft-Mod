# Logo Textures

This directory contains logo textures that are displayed on collection boxes.

## How It Works

Logo textures are rendered on separate bones in the box model with different aspect ratios. This allows for high-resolution logos without being limited by the box texture's 16x16 resolution.

## Logo Types

There are three logo types to match different aspect ratios:

- **`square`** - 6×6 units - For square logos (1:1 aspect ratio)
- **`wide`** - 8×5 units - For wide logos (e.g., 16:10, 16:9 aspect ratios)
- **`tall`** - 5×8 units - For tall logos (e.g., 10:16, 9:16 aspect ratios)

## Adding a Logo

1. Create a PNG texture file (recommended: 64x64 or higher for best quality)
2. Place it in this directory (e.g., `jojos_logo.png`)
3. Reference it in your collection JSON file with the appropriate `logo_type`:

```json
{
  "id": "your_collection",
  "name": "Your Collection Name",
  "box_texture": "blockpops:textures/block/box/your_box.png",
  "logo_texture": "blockpops:textures/block/box/logo/your_logo.png",
  "logo_type": "wide",
  "figures": [...]
}
```

**Note**: If `logo_type` is not specified, it defaults to `"square"`.

## Logo Properties

- **Position**: Side face of the box
- **Format**: PNG with transparency support
- **Resolution**: Any size (64x64, 128x128, 256x256, etc.)
- **Optional**: Collections without logos will work fine

## Examples

### Wide Logo (JoJos Collection)
```json
{
  "id": "jojos",
  "logo_texture": "blockpops:textures/block/box/logo/jojos_logo.png",
  "logo_type": "wide"
}
```

For a logo with dimensions 1280×807 (aspect ratio ~1.6:1), use `"wide"`.

### Square Logo
```json
{
  "logo_texture": "blockpops:textures/block/box/logo/square_logo.png",
  "logo_type": "square"
}
```

For a logo with dimensions 512×512 (1:1), use `"square"` or omit `logo_type`.

### Tall Logo
```json
{
  "logo_texture": "blockpops:textures/block/box/logo/tall_logo.png",
  "logo_type": "tall"
}
```

For a logo with dimensions 600×960 (aspect ratio ~0.625:1), use `"tall"`.
