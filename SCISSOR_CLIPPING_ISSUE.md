# Color Selection Screen Rendering Solution (Minecraft 1.21.6)

## Problem Summary

The color selection screen needs to display 16 colored boxes in a 4x4 grid with custom rotations and scaling. This presents several challenges in Minecraft 1.21.6's rendering pipeline.

## Attempted Solutions

### 1. ❌ Direct `graphics.renderItem()` with Internal Scale
**Issue**: Items render to a fixed 16x16 pixel viewport. Any scale applied inside `BoxBlockItemRenderer` gets clipped to that area - only a small portion of the scaled model is visible.

### 2. ❌ Expanded `getExtents()`
**Issue**: Changing the extents doesn't affect the viewport/clipping - it just normalizes the model differently, making the model appear smaller.

### 3. ❌ Direct PoseStack + BoxBlockRenderer Rendering
**Issue**: Rendering with a fresh `PoseStack` outside the item pipeline doesn't work with 1.21.6's UBO (Uniform Buffer Object) transform system. The models either don't appear or render incorrectly.

### 4. ❌ PictureInPictureRenderer (PiP)
**Issue**: Provides crisp native-resolution rendering, but uses a single shared GPU texture per renderer. When 16 colors render sequentially to the same texture, all boxes end up showing the last color rendered (GPU texture reuse conflict).

**Code Evidence**: From `SCISSOR_CLIPPING_ISSUE.md` line 83:
> "we had to abandon PiP in 1.21.6 because it couldn't handle rendering 16 different colors simultaneously (all boxes showed the same color due to GPU texture reuse in the shared PictureInPictureRenderer)."

### 5. ✅ 2D Pose Scale Around `graphics.renderItem()` (Current Solution)

**Implementation**: Apply `graphics.pose().scale()` OUTSIDE the `renderItem()` call to scale the entire rendering area.

**File**: `ColorSelectionButton.java:148-153`
```java
graphics.pose().pushMatrix();
graphics.pose().translate(centerX, centerY);
graphics.pose().scale(scale, scale);
graphics.renderItem(boxItem, -8, -8);
graphics.pose().popMatrix();
```

**Advantages**:
- ✅ All 16 colors render correctly
- ✅ No 16x16 clipping - viewport scales with the 2D transform
- ✅ Custom rotations work (applied in `BoxBlockItemRenderer`)

**Disadvantages**:
- ⚠️ Pixelation from texture stretching (16x16 internal render gets scaled up)

## Current Status

The color selection screen uses the 2D pose scale approach with the following features:
- 16 unique colors displayed correctly in a 4x4 grid
- Adjustable via debug sliders: Rot X/Y/Z, Scale, Offset X/Y/Z, Cam RotX
- Players can toggle figure visibility inside boxes
- Transformations update in real-time

## Trade-offs Analysis

| Approach | Colors | Clipping | Pixelation | Notes |
|----------|--------|----------|------------|-------|
| `renderItem()` only | ✅ | ❌ | ❌ | Clipped to 16x16 |
| PiP | ❌ | ✅ | ✅ | GPU texture conflicts |
| 2D Pose Scale | ✅ | ✅ | ⚠️ | **Current solution** |

## Technical Details

### Why PiP Doesn't Work for Multiple Colors

The PiP system in 1.21.6 renders to off-screen GPU textures. Multiple renderers can exist, but:
1. Each `PictureInPictureRenderer` instance manages one texture
2. The texture is reused across all render calls to that renderer
3. When 16 buttons submit PiP states in the same frame, they all point to the same renderer instance
4. The last color rendered overwrites the texture, so all 16 buttons display that color

### Why Direct PoseStack Rendering Doesn't Work

Minecraft 1.21.6 uses a UBO-based transform pipeline where:
1. Block/entity renderers expect to be called within an established rendering context
2. The transform matrices are managed by the engine's UBO system
3. Creating a fresh `PoseStack` outside this context doesn't properly integrate with the matrix upload pipeline
4. Models either don't appear, or render with incorrect transforms/lighting

### Why 2D Pose Scale Works

The 2D `graphics.pose()` operates at the GUI layer:
1. It's a 2D affine transform (Matrix3x2fStack) that modifies the viewport
2. When `renderItem()` is called, the viewport is already scaled
3. The 16x16 internal render fits the scaled viewport
4. Each button's `ItemStack` is unique, so colors don't conflict

## Future Improvements

Potential approaches to reduce pixelation while maintaining correct colors:

1. **Mipmap/LOD Configuration**: Investigate if item rendering can use higher-resolution mipmaps
2. **Per-Color PiP Renderers**: Create 16 separate `PictureInPictureRenderer` instances (one per color) - would require significant architecture changes
3. **Custom GUI Rendering Pipeline**: Implement a custom deferred rendering system outside Minecraft's standard pipelines
4. **Shader-Based Upscaling**: Apply post-processing shaders to smooth the stretched textures

## Files Modified

- `ColorSelectionButton.java` - Uses 2D pose scale approach
- `BoxBlockItemRenderer.java` - Applies rotations (scale handled externally)
- `FavoriteColorSelectionScreen.java` - Debug sliders for tuning
- `BoxWidgetRenderer.java` - Per-color entity cache (unused in current solution)
- `ItemPipRenderer.java` - PiP renderer (unused due to color conflicts)

## Conclusion

For Minecraft 1.21.6, the 2D pose scale approach is the best available solution that correctly displays all 16 colors without clipping. The pixelation is an acceptable trade-off given the constraints of the rendering pipeline.
