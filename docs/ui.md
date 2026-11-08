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
  ultrawide windows, and smaller only in windows under 1280x720, down to 320x240: 400x300 in an
  800x600 window, and 427x240 in Minecraft's default 854x480 window.
- A dialog drawn over a menu (`DialogScreen`, or a screen that calls `MenuGuiScale.follow` first in
  `init()` and `MenuGuiScale.leave` from `removed()`) takes the menu's scale, so the menu behind it
  keeps its layout.
- A dialog that opens on its own, with no menu under it (the figure position editor, opened from a
  block), is a `DialogScreen` that also implements `MenuGuiScale.Scaled`: it calls `enter` before
  `super.init()` and `restore` from `removed()`, and the world stays visible under its veil.
- Layouts are responsive within that canvas: sizes come from `this.width` and `this.height`, never
  from a fixed GUI scale. Screens are laid out from 400x300 up; the creator's sidebar screens
  (`SidebarScreen`) are laid out for 427x240 too, and stay usable through their scrolling areas
  down to 320x240.

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
| A mark over something not ready | `Stamp` (a rubber stamp in red ink, turned a little; Settings' Custom tab says IN DEVELOPMENT) |
| A menu's opening | `HalftoneTransition` (a ring of black halftone dots travels from the middle outward, the world beyond it and the menu inside it) |
| Text on a dark face | `UiText.draw`, `drawFitted` (cut with "..."), `wrap` (word-wrapped lines) |
| A text field in the creator | `creator.ui.UiField`, an `EditBox` in a well; its hint is cut to the field (`setFittedHint`) and it shows the start of its text once it loses the keyboard |
| A creator screen: a sidebar and one content panel | `creator.ui.SidebarScreen`: both panels laid out from the canvas (narrow under 360 content units wide, short under 300 high), one baseline for every bottom button, a heading with glyph buttons, a status slot, toasts over the content, and a budget of 15 models a screen |
| A row of a creator sidebar | `creator.ui.UiSideRow` (icon or box, label, count, status, dot or chevron; selecting one plays the tab sound), in a `creator.ui.UiSideList` (selection by key, folds, arrow keys, lock, only the rows in sight drawn) |
| A settings row that opens something or acts | `creator.ui.UiFoldRow` (label, faint hint, dot, chevron; red for what takes something away); a hint may have a short form for a narrow row, and `keepHint` cuts the label rather than a short value |
| An on/off setting in a creator list | `creator.ui.UiToggleRow`, a node on `ToggleRow`'s look that scrolls and can unfold content under it; `wrapLabel` wraps a long label instead of cutting it |
| A dialog that stands on a button | `creator.ui.UiPopover`, opened with `CreatorScreen.openPopover`; it shares the one dialog slot with `UiModal`, keeps its buttons on the screen, can line up with its panel's edge (`leftEdge`) or be held inside a panel on every side (`within`), and runs `onClose` whatever closes it |
| A collection's showcase | `creator.ui.UiStage` (its box and the figures that fit on a ledge, then a "+N" tile) |
| A row of figure thumbnails | `creator.ui.UiThumbStrip` (selection, arrow keys, drag to reorder, a "+" tile, scrolling arrows; `selectable(false)` for one that only shows); every figure drawn at the usual size, one mark at most, and a thumbnail left without a model drawn flat from its texture |
| A figure's big preview | `creator.ui.UiFigureView`: the figure alone on a stage in the collection's colour, 70% of its height, with one "View ⌄" (`UiViewButton`) whose menu picks the view, the pose, the light, the spinning and the skin variant, kept inside its panel (`within`); a model is fitted by the extent its host gives (`setExtent`), and a figure of another size stands beside the 1x figure; a hidden drop zone laid over it takes a file dropped on the figure |
| A place to drop or choose a file | `creator.ui.UiDropZone` (one slot, or `many` for the bulk add); `hidden()` draws nothing, takes a drop only under the pointer and is never named in the which-slot menu, and `routing` hands each file to the zone of its kind |
| A row of tiles to pick from (colours, boxes, Image, More) | `creator.ui.UiTileRow`: `UiSwatches`' tiles and selection ring kept inside the row, tiles asked for on every frame, optional captions, wrapping, a tooltip a tile, and a keyboard cursor once a key moves it |
| The head of a form's section, or a narrow row's label | `creator.ui.UiSectionHead`: a line, the small pixel-font name and its value on the right; `stacked` puts the value under the name |
| A preview's corner controls | `creator.ui.UiViewButton` ("View ⌄", a small grey button with a pixel chevron that opens a menu) and `creator.ui.UiIconButton` (14×14, a pixel icon, lit blue while on), placed by `UiBoxView.corners` |
| The front of a box with the logo to drag on it | `creator.ui.UiBoxFrontEditor`, shown by `UiBoxView.frontEditor` in place of the box; the logo sits where the box draws it (`LogoLayout.onFace`) |

Dialog accents: `SETTINGS` (slate) for settings, `EDIT` (blue) to edit or rename, `CREATE` (green)
to create or upload, `DANGER` (red) to delete, `COLLECTION` (purple) for collection content.

Messages stay as written (for example "Done", "Use Regular Token"): the pixel font upper-cases only
what it draws, and the packaged E2E scenario finds widgets by their message.

## The creator

The World-collection creator's screens, its home and its editor, are laid out as Quick Skin's
settings: a sidebar and one content panel, both from `SidebarScreen`. Its home lists the
collections in the sidebar and shows the selected one in the content; its editor lists the
collection's tabs (Figures, Look, Options) and shows the selected tab's page.

- **Panels.** Both panels sit on the starry background, 8 units in from the canvas edge, together
  at most 900x520. The sidebar is a quarter of that width plus 20, from 116 to 156 units; the
  content takes the rest, 6 units to its right. The sidebar holds a heading with glyph buttons at
  its right (the help, and on the home a cross that leaves; the editor's heading is a back arrow
  before the collection's name), a list of whole rows, a one-line status slot and one button at its
  foot.
- **One green action.** Each screen state has one PRIMARY button: **Open** on the home, **Create**
  in its form (and the empty world's invitation), **Publish** or **Publish changes** in the editor.
  While the publish popover is open the editor's button is drawn pressed in the SECONDARY face, so
  the popover's own button is the only green. Rarer actions stay out of the main view: a
  collection's in the home's **More** menu (archiving, deleting and importing also in the editor's
  Options), the test draw in the publish popover (or at the editor's foot on a published or archived
  collection with nothing waiting), the collection's settings in Options.
- **Footer baseline.** Every bottom button of either panel sits on one line, `footerY`. Toasts are
  centred over the content panel just above it, or over its foot on a small canvas when the screen
  has no buttons there.
- **Narrow and short.** The content is narrow under 360 units wide (in an 800x600 or 854x480
  window), which tightens its padding and puts side by side less: the home's showcase holds fewer
  figures and its **More** becomes a glyph, Look's view becomes a band over one labelled row a
  section, and the Figures column shortens its hints. The canvas is short under 300 units high
  (427x240 in Minecraft's default window), which raises the content's title and lets the columns
  that no longer fit scroll. Below that, down to 320x240, the scrolling areas keep every control in
  reach.
- **Model budget.** From 1.21.6 each figure or box drawn into a screen takes a render target of its
  own, so a creator screen draws at most 15 models, handed out in a fixed order: the big preview or
  the showcase's box, the showcase's figures or Look's sample, the figure strip's thumbs from the
  selected one, then the sidebar's boxes. A node left without one draws a flat stand-in (a thumb
  its figure's texture, a sidebar row a swatch of the collection's colour) or, on the showcase's
  stage, nothing; while a dialog that draws models of its own is open (the test draw, the machine's
  screen) the screen behind it draws none.
- **Rows.** Choosing a sidebar row plays the tab sound. A setting is a `UiFoldRow` or a
  `UiToggleRow` whose explanation is its label's tooltip, never a second line.
- **Saving.** The editor's status slot says how the saving stands in a few words, its full sentence
  in the tooltip: saved, saving soon, uploading, files waiting for the operator to move on, changes
  that cannot be saved, a refusal, the space running low, or a published collection's changes not
  published yet. A click on it does what the state offers, such as going to a change that cannot be
  saved or retrying. A passing message takes the slot for a toast's lifetime, but never covers an
  urgent state (changes that cannot be saved, no connection, a failed save). The tab rows repeat it
  as dots: red where something cannot be saved, amber where a published collection's changes wait.

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
next one, back to the figure's own after the last, with a snap. The chip turns gold
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

## Sounds

The mod plays sounds of its own, never vanilla's: `ModSounds` registers them and
`assets/blockpops/sounds.json` names their files. The screens play theirs through `UiSounds`,
heard the same wherever the player stands.

- **Buttons.** Every button class of the mod overrides `playDownSound`. A `StyledButton` clicks
  unless `sound(...)` gives it another, as the token buttons drop a coin; a `TabButton` flips a
  switch, and so does choosing another collection. The creator's buttons click too.
- **The menu** sounds once as its opening transition starts, and a figure's next version snaps in.
- **A draw.** Each step of the light ticks at 0.55 volume, its pitch rising from 1 to 2 over the
  path; the stop dings at 0.9; then a capsule pops for a new figure or a plop sounds for one the
  player had, with an 8-bit sparkle over either when it comes out holographic.
- **In the world**, a figure going into its box or out of it, a box cut open and a plush turning
  into another come from the block, so their files are mono and have subtitles.

The files were generated with ElevenLabs, trimmed to where the sound starts and ends and levelled
against each other; how loud each plays is set where it is played.

## Versions

New widgets override the hook of each era themselves, because the build's rename table
(`gradle/build-conventions.gradle`) lists only existing files: `renderWidget` before 26.1,
`extractContents` for a `Button` and `extractWidgetRenderState` for a slider or other
`AbstractWidget` from 26.1, behind `//? if >=26 {` blocks. The graphics parameter is named
`graphics` so the build's `GuiGraphics` method renames apply.
