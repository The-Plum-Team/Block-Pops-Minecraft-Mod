# Scissor Clipping Issue in Color Selection Screen (Minecraft 1.21.6)

## Problem Description

The color selection screen in the BlockPops mod displays 16 colored boxes in a 4x4 grid. Each box is rendered as a Minecraft item using `graphics.renderItem()`. The boxes render correctly with proper colors, but they appear **clipped/cut off** - as if a small rectangular mask is being applied that only shows a portion of each box.

## Current Implementation

**File**: `common/src/v1_21_6/java/com/theplumteam/client/gui/widget/ColorSelectionButton.java`

Each button in the grid:
1. Creates an `ItemStack` with box block item (different color per button)
2. Stores transformation data in NBT: `CustomRotationX`, `CustomRotationY`, `CustomRotationZ`, `CustomScale`
3. Renders the item at button center using `graphics.renderItem(boxItem, itemX, itemY)`

**File**: `common/src/v1_21_6/java/com/theplumteam/client/renderer/BoxBlockItemRenderer.java`

The item renderer:
1. Reads custom transformations from NBT
2. Applies rotations and scale in `ItemDisplayContext.GUI` context
3. Renders the box using `BoxBlockRenderer` (GeckoLib)

## Symptoms

- ✅ All 16 colors display correctly (no color conflicts)
- ✅ Debug sliders work perfectly and update transformations in real-time
- ❌ **Boxes appear clipped** - only a small portion is visible
- ❌ Increasing scale makes clipping worse (more of the box is cut off)
- The visible portion seems to be limited to approximately **16x16 pixels** regardless of scale

## What We've Tried

1. **Removed `graphics.enableScissor()`** - No effect, clipping persists
2. **Applied transformations via `GuiGraphics.pose()`** - Can't use 3D transforms (Matrix3x2fStack is 2D only)
3. **Recreate ItemStack on every transform change** - Works, but clipping remains
4. **Custom scissor area calculations** - Already removed, didn't help

## Suspected Root Cause

Minecraft's `graphics.renderItem()` method in 1.21.6 may be applying its own internal scissor/viewport that limits rendering to the default item size (16x16), ignoring custom scales applied by the SpecialModelRenderer.

## Code References

### ColorSelectionButton.renderWidget() - Lines ~168-173
```java
// Calculate item position in button center
int itemX = getX() + (width - 16) / 2;
int itemY = getY() + (height - 16) / 2;

// Render the item - BoxBlockItemRenderer reads CustomRotationX/Y/Z and CustomScale from NBT
graphics.renderItem(boxItem, itemX, itemY);
```

### BoxBlockItemRenderer.render() - Lines ~111-123
```java
if (displayContext == ItemDisplayContext.GUI) {
    poseStack.translate(0.5, 0.5, 0.5);

    // Apply custom rotations if present
    poseStack.mulPose(Axis.YP.rotationDegrees(customRotY));
    poseStack.mulPose(Axis.XP.rotationDegrees(customRotX));
    poseStack.mulPose(Axis.ZP.rotationDegrees(customRotZ));

    // Apply custom scale if present
    if (customScale != 1.0f) {
        poseStack.scale(customScale, customScale, customScale);
    }

    poseStack.translate(-0.5, -0.4375F, -0.5);
}
```

## Question

How can we render scaled 3D block entity models in GUI without them being clipped to the default 16x16 item rendering area? Is there a way to:

1. Disable the internal scissor/viewport that `graphics.renderItem()` applies?
2. Render the item with a custom rendering context that respects our scale transforms?
3. Or should we bypass `graphics.renderItem()` entirely and render the GeckoLib model directly with a custom PoseStack?

## Working Version (1.21.5)

In Minecraft 1.21.5, this exact same code worked perfectly with the PiP (Picture-in-Picture) system, but we had to abandon PiP in 1.21.6 because it couldn't handle rendering 16 different colors simultaneously (all boxes showed the same color due to GPU texture reuse in the shared PictureInPictureRenderer).

## Environment

- Minecraft: 1.21.6
- Mod Loader: Fabric
- GeckoLib: 5.2.0
- Architectury: 17.0.6
