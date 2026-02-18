# Minecraft 1.21.6 GUI Item Rendering Clipping Issue - Need Help

## Problem Description

I'm developing a Minecraft 1.21.6 Fabric mod that needs to display 16 colored box blocks in a GUI screen (4x4 grid). Each box is a GeckoLib animated 3D block entity model that should render scaled and rotated in GUI buttons. **The boxes render at tiny size (appearing clipped/cut off) regardless of the scale value applied.**

## Environment

- **Minecraft Version**: 1.21.6
- **Mod Loader**: Fabric
- **GeckoLib Version**: 5.2.0
- **Architectury**: 17.0.6

## Current Implementation

### Item Model JSON (`box_block_red.json`)
```json
{
  "parent": "builtin/entity",
  "gui_light": "front",
  "oversized_in_gui": true
}
```

All 16 color variants (black, blue, red, yellow, etc.) use the same JSON structure with `oversized_in_gui: true` enabled.

### Widget Rendering (`ColorSelectionButton.java`)

```java
@Override
public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    // ... background and border rendering ...

    // Render item directly - oversized_in_gui: true should allow rendering beyond 16x16
    int itemX = getX() + (width - 16) / 2;
    int itemY = getY() + (height - 16) / 2;
    graphics.renderItem(boxItem, itemX, itemY);
}

private void rebuildBoxItem() {
    this.boxItem = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color).get());

    CompoundTag blockEntityTag = new CompoundTag();
    blockEntityTag.putBoolean("HideLogo", true);
    blockEntityTag.putString("Color", color.name());
    // ... figure configuration ...

    // Store custom transformations
    blockEntityTag.putFloat("CustomRotationX", rotationX);
    blockEntityTag.putFloat("CustomRotationY", rotationY);
    blockEntityTag.putFloat("CustomRotationZ", rotationZ);
    blockEntityTag.putFloat("CustomScale", scale);  // Currently scale = 30.0f
    blockEntityTag.putFloat("CustomOffsetX", offsetX);
    blockEntityTag.putFloat("CustomOffsetY", offsetY);
    blockEntityTag.putFloat("CustomOffsetZ", offsetZ);

    this.boxItem.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(blockEntityTag));
}
```

### Special Model Renderer (`BoxBlockItemRenderer.java`)

```java
public class BoxBlockItemRenderer implements SpecialModelRenderer<BoxBlockItemRenderer.RenderData> {
    private final BoxBlockRenderer renderer;
    private BoxBlockEntity renderEntity;

    @Override
    public void render(@Nullable RenderData data, ItemDisplayContext displayContext, PoseStack poseStack,
                      MultiBufferSource bufferSource, int packedLight, int packedOverlay, boolean hasGlint) {
        // ... entity setup ...

        float customRotX = 0;
        float customRotY = 180;
        float customRotZ = 0;
        float customScale = 1.0f;
        float customOffsetX = 0, customOffsetY = 0, customOffsetZ = 0;

        if (data != null && data.blockEntityData() != null) {
            CompoundTag nbt = data.blockEntityData();
            if (nbt.contains("CustomRotationX")) {
                customRotX = nbt.getFloatOr("CustomRotationX", 0);
                customRotY = nbt.getFloatOr("CustomRotationY", 180);
                customRotZ = nbt.getFloatOr("CustomRotationZ", 0);
                customScale = nbt.getFloatOr("CustomScale", 1.0f);
                customOffsetX = nbt.getFloatOr("CustomOffsetX", 0);
                customOffsetY = nbt.getFloatOr("CustomOffsetY", 0);
                customOffsetZ = nbt.getFloatOr("CustomOffsetZ", 0);
            }
        }

        if (displayContext == ItemDisplayContext.GUI) {
            poseStack.translate(0.5, 0.5, 0.5);

            // Apply custom offsets (before rotation)
            if (customOffsetX != 0 || customOffsetY != 0 || customOffsetZ != 0) {
                poseStack.translate(customOffsetX, customOffsetY, customOffsetZ);
            }

            // Apply custom rotations
            poseStack.mulPose(Axis.YP.rotationDegrees(customRotY));
            poseStack.mulPose(Axis.XP.rotationDegrees(customRotX));
            poseStack.mulPose(Axis.ZP.rotationDegrees(customRotZ));

            // Apply custom scale - should work with oversized_in_gui: true
            if (customScale != 1.0f) {
                poseStack.scale(customScale, customScale, customScale);
            }

            poseStack.translate(-0.5, -0.4375F, -0.5);
        }

        // Render using GeckoLib BoxBlockRenderer
        this.renderer.render(renderEntity, partialTick, poseStack, bufferSource,
                           packedLight, packedOverlay, Vec3.ZERO);
    }

    @Override
    public void getExtents(Set<Vector3f> extents) {
        extents.add(new Vector3f(0, 0, 0));
        extents.add(new Vector3f(1, 1, 1));
    }
}
```

## What We Observe

**Screenshot shows**: 16 boxes render with correct colors, but all appear at **very small size** (roughly 16x16 pixels or slightly larger) in the button centers, regardless of the `CustomScale` value being set to 30.0.

**Expected**: Boxes should scale up proportionally with the `CustomScale` value and render at native resolution (crisp, not pixelated) thanks to `oversized_in_gui: true`.

## What We've Tried (All Failed)

### Attempt 1: Direct `renderItem()` with Internal Scale
- Applied scale inside `BoxBlockItemRenderer` via `poseStack.scale()`
- Result: Boxes clipped to 16x16 pixels (only small portion visible)

### Attempt 2: 2D Pose Scale Around `renderItem()`
```java
graphics.pose().pushMatrix();
graphics.pose().translate(centerX, centerY);
graphics.pose().scale(scale, scale);
graphics.renderItem(boxItem, -8, -8);
graphics.pose().popMatrix();
```
- Result: Boxes scaled correctly but **extremely pixelated** (16x16 texture stretched)

### Attempt 3: PictureInPictureRenderer (PiP)
- Created per-color off-screen GPU texture renderers
- Result: All 16 boxes showed the **same color** (last rendered) due to GPU texture reuse in the shared PiP renderer

### Attempt 4: Direct `PoseStack` + GeckoLib Rendering
```java
PoseStack poseStack = new PoseStack();
poseStack.translate(centerX, centerY, 100.0);
float pixelScale = 16.0f * scale;
poseStack.scale(pixelScale, -pixelScale, pixelScale);
// ... rotations ...
sharedRenderer.render(renderEntity, partialTick, poseStack, bufferSource, ...);
bufferSource.endBatch();
```
- Result: Boxes **didn't render at all** (blank) - likely incompatible with 1.21.6's UBO transform system

### Attempt 5: Expanded `getExtents()`
```java
extents.add(new Vector3f(-3, -3, -3));
extents.add(new Vector3f(4, 4, 4));
```
- Result: Made boxes **smaller** (normalized differently), didn't fix clipping

### Attempt 6: Added `oversized_in_gui: true` (Current)
- Added the property to all item model JSONs
- Applied scale internally in `BoxBlockItemRenderer`
- Result: Boxes render **crisp** (not pixelated) but still at **tiny size** (~16x16 pixels), scale value seems ignored

## Key Questions

1. **Does `oversized_in_gui: true` actually work with `SpecialModelRenderer` in 1.21.6?** The documentation says it allows items to render beyond 16x16, but our scale transforms still seem to be ignored or clipped.

2. **Is there a viewport/scissor being applied somewhere else?** The rendering appears limited to ~16x16 pixels even with `oversized_in_gui: true`.

3. **Does the scale need to be applied differently?** Maybe the scale transform order, or a different coordinate space?

4. **Is there a missing configuration in the item model JSON?** Perhaps an additional property needed for scaled special models?

## Expected Behavior (from 1.21.5)

In Minecraft 1.21.5, the exact same code worked perfectly using a PiP system. Boxes rendered at full size with correct colors. We had to migrate away from PiP in 1.21.6 because of the GPU texture reuse issue.

## Current Debug Capabilities

The screen has live sliders for:
- Rotation X/Y/Z (0-360°) - **These work**
- Scale (0.1-50.0) - **Seems to do nothing, boxes stay tiny**
- Offset X/Y/Z (-2.0 to 5.0) - **Need to verify if these work**
- Camera Rotation X (0-90°) - **Not tested yet**

Rotations update in real-time and work correctly.

## Additional Context

### GeckoLib Block Renderer
The `BoxBlockRenderer` extends `GeoBlockRenderer<BoxBlockEntity>` and renders:
- The box model (GeckoLib animated)
- A figure face texture on specific bones
- A collection logo
- A 3D figure model inside the box (optional)

### Item Registration
Items are registered as `BoxBlockItem` instances (extends `GeoBlockItem`) with:
```java
return new BlockItem.Properties().component(
    DataComponents.SPECIAL_MODEL_RENDERER,
    new BoxBlockItemRenderer.Unbaked()
);
```

## What We Need

A solution that achieves:
- ✅ All 16 colors render correctly (unique colors, no GPU texture conflicts)
- ✅ Boxes scale properly with the `CustomScale` value (not clipped to 16x16)
- ✅ Native resolution rendering (crisp, not pixelated)
- ✅ Custom rotations work (already working)
- ✅ Custom offsets work (X/Y/Z translation)

## Files Reference

Project structure:
```
BlockPops/
├── common/src/v1_21_6/java/com/theplumteam/
│   ├── client/gui/widget/ColorSelectionButton.java
│   ├── client/gui/FavoriteColorSelectionScreen.java
│   ├── client/renderer/BoxBlockItemRenderer.java
│   ├── client/renderer/BoxBlockRenderer.java
│   └── blockentity/BoxBlockEntity.java
├── common/src/main/resources/assets/blockpops/models/item/
│   ├── box_block_red.json
│   ├── box_block_blue.json
│   └── ... (30 total box block item models)
```

## Question

**How can we make `graphics.renderItem()` with a `SpecialModelRenderer` respect the internal `poseStack.scale()` transform in Minecraft 1.21.6, given that `oversized_in_gui: true` is set in the item model JSON?**

The scale transform is being applied (visible in debugger), rotations work, but the final rendered output appears clamped to ~16x16 pixels regardless of the scale value.
