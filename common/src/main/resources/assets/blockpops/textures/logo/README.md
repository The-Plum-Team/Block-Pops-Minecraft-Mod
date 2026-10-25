# Logo Textures

This directory contains the collection logos shown on the front of the box, under the window, and
on the collection's card in the machine menu. Both draw each logo as a texture of its own. The logos
are kept out of `textures/block/` because the game stitches every texture there into the shared
block atlas, where a logo would take room and video memory without ever being drawn from it.

## Adding a Logo

1. Put the PNG here as `logo_{id}.png`. Any size works, but there is no need for more than 512 on
   the longest side. Do not crop or stretch it: the box ignores transparent margins, but the
   collection menu draws the whole image.
2. Name it in the collection JSON:

```json
"logo": {
  "texture": "blockpops:textures/logo/logo_{id}.png"
}
```

Nothing else is needed. The box fits the logo by itself (`LogoLayout`): the visible part of the
image keeps its proportions, sits at the bottom-left corner of the window frame, and gets the
size the existing logos were hand-placed at.

## Adjusting a Logo

Only when a logo should differ from that fit, add any of these keys:

| Key | Default | Meaning |
|---|---|---|
| `scale` | `1.0` | Multiplies the fitted size; `0.9` is 10% smaller. |
| `offset_x` | `0.0` | Moves the logo right, in box pixels. The front of the box is 11 wide. |
| `offset_y` | `0.0` | Moves the logo up, in box pixels. The front of the box is 14 tall. |

```json
"logo": {
  "texture": "blockpops:textures/logo/logo_winx.png",
  "scale": 1.21,
  "offset_x": -0.62,
  "offset_y": -0.71
}
```

The older `position_x/y/z` and `scale_x/y/z` keys still work. They are the renderer's raw
transform and bypass the fit, so new collections should not use them.
