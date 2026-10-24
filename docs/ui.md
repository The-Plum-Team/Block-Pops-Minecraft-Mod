# Menu style and scale

BlockPops' screens share Quick Skin's look and its way of sizing menus, so the two mods read as
one family. The shared pieces live in `common/src/main/java/com/theplumteam/client/gui/style/`;
a screen builds from them instead of drawing its own variant.

## Scale: never the player's GUI Scale option

No BlockPops screen writes the GUI Scale option or calls `resizeDisplay()`: other mods break when
the option changes or the display resizes under them.

- A full-screen menu (the machine screen, the favourite-colour prompt, the creator) implements
  `MenuGuiScale.Scaled`, calls `MenuGuiScale.enter(minecraft, this)` first in `init()`, calls
  `MenuGuiScale.reapply(minecraft, this)` from `tick()` (and rebuilds its widgets when it returns
  true), and calls `MenuGuiScale.restore(minecraft)` from `removed()`. The scale is chosen from the
  window size (`MenuGuiScale.forFramebuffer`) as Quick Skin chooses its own, but for a canvas of at
  least 640x360 GUI units: 640x360 at 720p, 1080p, 1440p and 4K, 800x450 at 1600x900, wider on
  ultrawide windows, and smaller (down to 400x300) only in windows under 1280x720.
- A dialog drawn over a menu (`DialogScreen`, or a screen that calls `MenuGuiScale.follow` first in
  `init()` and `MenuGuiScale.leave` from `removed()`) takes the menu's scale, so the menu behind it
  keeps its layout.
- A dialog that opens on its own, with no menu under it (the figure position editor, opened from a
  block), is a `DialogScreen` that also implements `MenuGuiScale.Scaled`: it calls `enter` before
  `super.init()` and `restore` from `removed()`, and the world stays visible under its veil.
- Layouts are responsive within that canvas: sizes come from `this.width` and `this.height`, never
  from a fixed GUI scale, and every screen stays usable at 400x300.

## Surfaces and colours

`Surfaces` holds the palette. Everything is drawn from fills, text and items.

- **Raised** (`Surfaces.raised`): black outline, thin grey border, charcoal face, darker lip
  underneath. Buttons, cards, tiles and knobs.
- **Well** (`Surfaces.well`): outline, border, dark field with a shaded top edge. Text fields,
  slider tracks.
- **Backdrop** (`Surfaces.backdrop`): translucent dark area with corner brackets, behind a group of
  controls that stands on the starry background.
- Selection is the blue `Surfaces.SELECTED` border; hover lightens the face and border.
- Text on dark faces is drawn with `UiText` (a dark one-pixel drop shadow), not vanilla's shadow.

## Components

| Need | Use |
|---|---|
| A button | `StyledButton`, with `.tone(PRIMARY)` for the main action (green), `DANGER` (red), `GOLD` or `BLUE` |
| A screen's main action or a dialog button | `StyledButton.pixelLabel()`, or `DialogFrame.button(...)` |
| An icon button | `new StyledButton(..., Component.empty(), ...).icon(PixelIcon.X).tooltip(...)` |
| A dialog | `DialogScreen` (frame, veil over the parent, X in the header, Escape, follow scale); fill panels in `renderBody`, draw labels in `renderDialog` |
| A dialog panel inside another screen | `DialogFrame.draw` with an `Accent` and a `PixelIcon` |
| An on/off setting | `ToggleRow` |
| A slider | extend `StyledSlider` |
| A sidebar tab | `TabButton` with `setIcon(PixelIcon.X)` |
| A title or a big label | `PixelFont` (upper-case blocky font, crisp sizes only) |
| A small label in a corner | `Chip` (dark, the pixel font at half size, an optional icon, gold under the mouse) |
| A menu's opening | `HalftoneTransition` (a ring of black halftone dots travels from the middle outward, the world beyond it and the menu inside it) |
| Text on a dark face | `UiText.draw`, `drawFitted` (cut with "..."), `wrap` (word-wrapped lines) |
| A text field in the creator | `creator.ui.UiField`, an `EditBox` in a well |

Dialog accents: `SETTINGS` (slate) for settings, `EDIT` (blue) to edit or rename, `CREATE` (green)
to create or upload, `DANGER` (red) to delete, `COLLECTION` (purple) for collection content.

Messages stay as written (for example "Done", "Use Regular Token"): the pixel font upper-cases only
what it draws, and the packaged E2E scenario finds widgets by their message.

## Opening transition

The machine menu opens with a comic halftone: black dots on a grid of 10 GUI units grow over the
world from the middle of the screen outward, and before they reach the corners the dots in the
middle already shrink away to show the menu, so a ring of black travels outward with the world
beyond it and the menu inside it (about 1.3 s).

- **Timing.** Every dot grows (380 ms), holds (80 ms) and shrinks (380 ms) on the same sine
  ease-in-out curve; only its start is delayed, by its distance from the middle (up to 500 ms to
  the corners), so the front moves at an even speed. The dot's area follows the curve.
- **The world beyond the ring** is the frame the game showed when the menu opened, captured as F2
  captures a screenshot (read back from the GPU a frame or two later from 1.21.5). The menu, at
  its own scale from the start, is drawn under it, so the hotbar never changes size on screen.
- **Drawing.** The dots are drawn at the window's real pixels from sprites with anti-aliased edges,
  one per half pixel of radius, and grown dots as whole black cells, all in one batch (through the
  text render type before 1.21.2, which batches GUI blits from then on).
- The menu takes no clicks until the first dots shrink, and the packaged E2E plays no transition.

## A figure's versions in the grid

A figure with alternative skins, or a plush with variants, has several versions. Its cell in the
machine menu says which one it shows in a dark chip in its top right corner ("1/3", the pixel
font at about half a GUI unit per font pixel, crisp), and a click anywhere on the cell shows the
next one, back to the figure's own after the last, with a button's click. The chip turns gold
under the mouse, and the cell's name follows the version.

- The menu keeps the versions shown while it is open, through a resize or a tab switch; it opens
  again on every figure's own. The draw's result card always shows the figure's own.
- A plush variant is a block of its own, so the base plush's entry in its plush collection lists
  the others as `alternatives` with their own models, in step with `BuiltInPlushes.VARIANT_GROUPS`
  (what a right-click on a placed plush turns it into). A supporter's secret plush is not listed.
- The creator's previews show the skin they are told and no chip.

## A collection's card in the list

A collection's card shows its logo in a box on the left, up to twice as wide as tall, or a
monogram when it has none. Only the drawn part of the image counts (what `LogoLayout` measures
for the box front: texels of at least 10% alpha), so transparent margins in the file do not
shrink it; that part keeps its shape and fills the box's height or its width.

A collection with an author link shows a `Chip` "WEB" with an arrow in the top right corner of
its card while the card is under the mouse, and the name stops short of it; a click opens the
link through the game's own link handling, as a link in chat.

## The token lines

Each token count above its button ends in an "i" in the token's colour. Under the mouse it turns
white and the token's cinema ticket opens above it (`TokenTicket`), blue or gold: ADMIT ONE up its
stub; the wait for the next one (or the guaranteed one's renewal, in the player's own time once the
server has said its hour) beside the clock it runs on, game time or real time; a pill for each of
what runs and what stops that clock; and the tickets that add more. Its art is drawn pixel by
pixel into a texture, made once for each token; its words are drawn over it. The small pixel font
takes half a ticket unit per font pixel, and the whole ticket is drawn at the scale that makes
that a whole number of screen pixels, so it keeps its layout and stays crisp at every GUI scale.

## Versions

New widgets override the hook of each era themselves, because the build's rename table
(`gradle/build-conventions.gradle`) lists only existing files: `renderWidget` before 26.1,
`extractContents` for a `Button` and `extractWidgetRenderState` for a slider or other
`AbstractWidget` from 26.1, behind `//? if >=26 {` blocks. The graphics parameter is named
`graphics` so the build's `GuiGraphics` method renames apply.
