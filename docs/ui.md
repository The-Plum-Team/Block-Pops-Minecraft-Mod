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
| Text on a dark face | `UiText.draw`, `drawFitted` (cut with "..."), `wrap` (word-wrapped lines) |
| A text field in the creator | `creator.ui.UiField`, an `EditBox` in a well |

Dialog accents: `SETTINGS` (slate) for settings, `EDIT` (blue) to edit or rename, `CREATE` (green)
to create or upload, `DANGER` (red) to delete, `COLLECTION` (purple) for collection content.

Messages stay as written (for example "Done", "Use Regular Token"): the pixel font upper-cases only
what it draws, and the packaged E2E scenario finds widgets by their message.

## Versions

New widgets override the hook of each era themselves, because the build's rename table
(`gradle/build-conventions.gradle`) lists only existing files: `renderWidget` before 26.1,
`extractContents` for a `Button` and `extractWidgetRenderState` for a slider or other
`AbstractWidget` from 26.1, behind `//? if >=26 {` blocks. The graphics parameter is named
`graphics` so the build's `GuiGraphics` method renames apply.
