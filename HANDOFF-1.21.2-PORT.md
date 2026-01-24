# BlockPops 1.21.4 Port - Handoff Document

## Current Status

**Target version changed from 1.21.2 to 1.21.4** because:
- GeckoLib skipped 1.21.2 and 1.21.3 entirely
- GeckoLib 4.8 (Fabric) / 4.8.2 (NeoForge) officially supports 1.21.4

**Incremental approach (1.21.3 first) is NOT possible** because GeckoLib has no support for 1.21.3.

## Dependencies (Configured)

| Dependency | Version | Status |
|------------|---------|--------|
| **GeckoLib** | 4.8 (Fabric) / 4.8.2 (NeoForge) | Official 1.21.4 support |
| **NeoForge** | 21.4.156 | Latest stable |
| **Fabric API** | 0.111.0+1.21.4 | Available |
| **Architectury API** | 15.0.3 | Supports 1.21.4 |
| **Fabric Loader** | 0.17.0 | Version-independent |

## Source Sets Created
- `common/src/v1_21_4/java/` (77 files, copied from v1_21_1)
- `fabric/src/v1_21_4/java/` (7 files, copied from v1_21_1)

## API Changes Summary

### 1. Block API Changes (FIXED)

| Change | Files Affected | Status |
|--------|----------------|--------|
| `DirectionProperty` → `EnumProperty<Direction>` | BoxBlock, ClawMachineBlock, FigureBlock | ✅ Fixed |
| `ItemInteractionResult` → `InteractionResult` | BoxBlock | ✅ Fixed |
| `InteractionResult.sidedSuccess()` → `SUCCESS` | BoxBlock, ClawMachineBlock, FigureBlock | ✅ Fixed |
| `Level.getMaxBuildHeight()` → `Level.getMaxY()` | ClawMachineBlock | ✅ Fixed |
| `RenderShape.ENTITYBLOCK_ANIMATED` removed | BoxBlock, ClawMachineBlock, FigureBlock | ✅ Fixed |
| `getCloneItemStack()` new boolean parameter | BoxBlock, FigureBlock | ✅ Fixed |

### 2. Item Rendering System (FIXED)

| Change | Status |
|--------|--------|
| `BlockEntityWithoutLevelRenderer` removed | ✅ Fixed |
| `BuiltinItemRendererRegistry` removed (Fabric) | ✅ Fixed |
| New `SpecialModelRenderer` pattern implemented | ✅ Fixed |

**Fixed files:**
- `BoxBlockItemRenderer.java` - Now implements `NoDataSpecialModelRenderer`
- `ClawMachineBlockItemRenderer.java` - Now implements `NoDataSpecialModelRenderer`
- `FigureBlockItemRenderer.java` - Now implements `NoDataSpecialModelRenderer`

### 3. GeckoLib API Changes (NOT FIXED)

**Major change:** GeckoLib 4.8 uses `GeoRenderState` instead of passing animatable objects.

| Old Method | New Method | Files Affected |
|------------|------------|----------------|
| `getModelResource(T animatable)` | `getModelResource(GeoRenderState)` | All GeoModel classes |
| `getTextureResource(T animatable)` | `getTextureResource(GeoRenderState)` | All GeoModel classes |
| `getAnimationResource(T animatable)` | `getAnimationResource(GeoRenderState)` | All GeoModel classes |

**Affected files:**
- `BoxBlockModel.java`
- `ClawMachineBlockModel.java`
- `FigureBlockModel.java`
- `FigureModel.java`
- Potentially other model classes

### 4. GUI/Rendering API Changes (NOT FIXED)

| Change | Files Affected |
|--------|----------------|
| `GuiGraphics.blit()` - New signature with `Function<ResourceLocation,RenderType>` | LinkButton, CollectionEntry |
| `GameRenderer::getPositionTexShader` removed | CollectionSelectionScreen, FavoriteColorSelectionScreen |
| `mouseScrolled()` signature changed | CollectionSelectionScreen, CollectionListWidget, FigureListWidget, FavoriteColorSelectionScreen, SettingsScreen |
| `getScrollAmount()` removed | CollectionListWidget, FigureListWidget |
| `RenderSystem.clear()` - Boolean parameter removed | SettingsScreen |
| `TextureManager.register()` - Different parameter type | StarPatternCache |

### 5. Fabric Client Initialization (NOT FIXED)

The `BlockPopsFabricClient.java` still uses `BuiltinItemRendererRegistry` which was removed. Need to update to use the new `SpecialModelTypes.ID_MAPPER` registration.

## Remaining Work

### Priority 1: GeckoLib Model Updates
1. Update all `GeoModel` subclasses to use `GeoRenderState` pattern
2. Research GeckoLib 4.8 documentation for exact method signatures
3. Update model resource, texture, and animation resource methods

### Priority 2: GUI/Rendering Updates
1. Update `blit()` calls to new signature
2. Remove `setShader(GameRenderer::getPositionTexShader)` calls
3. Update `mouseScrolled()` method signatures
4. Replace `getScrollAmount()` with new equivalent
5. Update `RenderSystem.clear()` calls
6. Fix `TextureManager.register()` call

### Priority 3: Fabric Client Registration
1. Remove `BuiltinItemRendererRegistry` usage
2. Implement `SpecialModelTypes.ID_MAPPER` registration
3. Create item JSON files at `assets/blockpops/items/*.json`
4. Create model JSON files with `"type": "minecraft:special"`

## Build Commands

```bash
# Build 1.21.4 Fabric only
./gradlew clean build -Pminecraft_version=1.21.4 -Penabled_platforms=fabric

# Compile only (faster for testing)
./gradlew compileJava -Pminecraft_version=1.21.4 -Penabled_platforms=fabric
```

## Resources

- [NeoForge 1.21.4 Migration Primer](https://docs.neoforged.net/primer/docs/1.21.4/)
- [GeckoLib Wiki - Geo Models](https://github.com/bernie-g/geckolib/wiki/Geo-Models-(Geckolib4))
- [GeckoLib 4 Changes](https://github.com/bernie-g/geckolib/wiki/Geckolib-4-Changes)
- [Fabric for Minecraft 1.21.4](https://fabricmc.net/2024/12/02/1214.html)

## GeckoLib Version Matrix

| Minecraft | GeckoLib Version | Notes |
|-----------|------------------|-------|
| 1.20.1 | 4.7.x | Stable |
| 1.21.1 | 4.6-4.8.x | Stable |
| 1.21.2 | ❌ None | Skipped |
| 1.21.3 | ❌ None | Skipped |
| 1.21.4 | 4.8 / 4.8.2 | GeoRenderState pattern |
| 1.21.5+ | 5.x | Different API |

## Error Count Summary

| Category | Initial Errors | Fixed | Remaining |
|----------|----------------|-------|-----------|
| Block API | 7 | 7 | 0 |
| Item Renderers | 6 | 6 | 0 |
| GeckoLib Models | ~10 | 0 | ~10 |
| GUI/Rendering | ~15 | 0 | ~15 |
| **Total** | ~38 | 13 | ~25 |

## Notes

1. The 1.21.4 port is more extensive than expected due to Mojang's rendering system overhaul
2. GeckoLib's shift to `GeoRenderState` requires careful study of the new API
3. GUI code changes are extensive but mostly mechanical (method signatures)
4. Consider whether partial 1.21.4 support is acceptable while GUI features are being updated
