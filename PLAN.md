Of course. After a thorough review of your mod's code, I've pinpointed the exact reason why the player skin snapshot system isn't working as you expect.

### The Core Issue

The fundamental problem is that the skin snapshot is **never saved to the `FigureBlock` itself**.

Here's the current data flow:

1.  **Discovery:** When a player gets a new player figure from the claw machine, the server takes a snapshot of that player's skin and saves it to the **discovering player's capability data** (`IPlayerDiscovery`). This part is working correctly.
2.  **Extraction:** When you open a `BoxBlock` to get the `FigureBlock` item, the code in `BoxBlock.java` creates a new `ItemStack` for the figure. However, it only copies the `FigureId`, `CollectionId`, and positioning data into the item's NBT. **It does not look up and copy the skin snapshot.**
3.  **Rendering:** The client-side renderer (`FigureBlockModel.java`) tries to find a skin for the figure. It checks `ClientDiscoveryManager` (the client-side cache of your capability data) for a snapshot. If it finds one, it uses it. If not, it falls back to requesting the player's **live skin** from Mojang's servers.

Because the `FigureBlockEntity` doesn't have its own copy of the skin snapshot, it is entirely reliant on the client's cache. When you change your skin and log back in, the game's skin manager fetches your new live skin for your UUID, and since the block has no specific snapshot data to override this, the renderer displays the new one.

### How to Fix It

To make the skin permanent on the figure block, you need to store the snapshot URL directly in the `FigureBlockEntity`'s NBT data.

Here is a step-by-step guide to implement the fix:

---

#### Step 1: Add a Skin Snapshot Field to `FigureBlockEntity`

First, modify `FigureBlockEntity.java` to store the skin data.

**File:** `forge/src/main/java/com/theplumteam/blockentity/FigureBlockEntity.java`

```java
// ... imports

public class FigureBlockEntity extends BlockEntity implements GeoBlockEntity {
    // ... existing fields
    private String collectionId = "";
    private int alternativeSkinIndex = 0; // 0 is default, 1+ are from the alternatives list
    private String skinSnapshot = null; // <-- ADD THIS FIELD

    // ... positioning fields

    // ... constructor, registerControllers, getAnimatableInstanceCache

    // ... existing methods for collectionId, figureId, etc.

    /**
     * Gets the saved skin snapshot URL for this figure.
     * @return The skin snapshot URL, or null if not set.
     */
    @Nullable
    public String getSkinSnapshot() {
        return skinSnapshot;
    }

    // ... other methods

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("FigureId", figureId);
        tag.putString("CollectionId", collectionId);
        tag.putInt("AlternativeSkinIndex", alternativeSkinIndex);
        if (skinSnapshot != null) { // <-- ADD THIS
            tag.putString("SkinSnapshot", skinSnapshot);
        }
        tag.putDouble("FigureOffsetX", figureOffsetX);
        tag.putDouble("FigureOffsetY", figureOffsetY);
        tag.putDouble("FigureOffsetZ", figureOffsetZ);
        tag.putDouble("FigureScale", figureScale);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("FigureId")) {
            this.figureId = tag.getString("FigureId");
        }
        if (tag.contains("CollectionId")) {
            this.collectionId = tag.getString("CollectionId");
        }
        if (tag.contains("AlternativeSkinIndex")) {
            this.alternativeSkinIndex = tag.getInt("AlternativeSkinIndex");
        }
        if (tag.contains("SkinSnapshot", 8)) { // 8 is Tag.TAG_STRING // <-- ADD THIS
            this.skinSnapshot = tag.getString("SkinSnapshot");
        } else {
            this.skinSnapshot = null;
        }
        if (tag.contains("FigureOffsetX")) {
            // ... rest of the load method
        }
    }

    // ... rest of the class
}
```

---

#### Step 2: Save the Snapshot When Creating the `FigureBlock` Item

Now, update `BoxBlock.java` to fetch the snapshot from the player's capability and add it to the NBT of the `FigureBlock` item when it's extracted.

**File:** `forge/src/main/java/com/theplumteam/block/BoxBlock.java`

In the `use` method, locate where the `figureBlockItem` is created and add the logic to include the skin snapshot.

```java
// ... inside the use() method, under the // Any other item or empty hand - extract the figure comment
else if (boxBlockEntity.hasFigure() && !boxBlockEntity.isFigureExtracted()) {
    // Create a figure block item with the figure data
    ItemStack figureBlockItem = new ItemStack(ModItems.FIGURE_BLOCK_ITEM.get());
    CompoundTag blockEntityTag = new CompoundTag();
    blockEntityTag.putString("FigureId", boxBlockEntity.getFigureId());
    blockEntityTag.putString("CollectionId", boxBlockEntity.getCollectionId());
    blockEntityTag.putInt("AlternativeSkinIndex", boxBlockEntity.getAlternativeSkinIndex());
    // Copy figure positioning data
    blockEntityTag.putDouble("FigureOffsetX", boxBlockEntity.getFigureOffsetX());
    blockEntityTag.putDouble("FigureOffsetY", boxBlockEntity.getFigureOffsetY());
    blockEntityTag.putDouble("FigureOffsetZ", boxBlockEntity.getFigureOffsetZ());
    blockEntityTag.putDouble("FigureScale", boxBlockEntity.getFigureScale());

    // --- START OF FIX ---
    // If it's a player figure, find and attach the skin snapshot to the item
    com.theplumteam.figure.FigureDefinition figureDef = boxBlockEntity.getFigureDefinition();
    if (figureDef != null && figureDef.getType() == com.theplumteam.figure.FigureType.PLAYER) {
        player.getCapability(com.theplumteam.capability.PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
            String uniqueFigureId = boxBlockEntity.getCollectionId() + ":" + boxBlockEntity.getFigureId();
            String snapshot = discovery.getFigureSkin(uniqueFigureId);
            if (snapshot != null && !snapshot.isEmpty()) {
                blockEntityTag.putString("SkinSnapshot", snapshot);
            }
        });
    }
    // --- END OF FIX ---

    figureBlockItem.addTagElement("BlockEntityTag", blockEntityTag);

    // Give the player the figure block item
    // ... rest of the method
```

---

#### Step 3: Prioritize the Block's Snapshot in the Renderer

Finally, modify `FigureBlockModel.java` to prioritize the snapshot stored in the `FigureBlockEntity` over any other method. This makes the block's own data the source of truth for its skin.

**File:** `forge/src/main/java/com/theplumteam/client/model/FigureBlockModel.java`

Replace the entire `getTextureResource` method with this updated version:

```java
@Override
public ResourceLocation getTextureResource(FigureBlockEntity animatable) {
    FigureDefinition figure = animatable.getFigureDefinition();
    if (figure == null) {
        return FALLBACK_TEXTURE;
    }

    // Check for alternative skins first
    int skinIndex = animatable.getAlternativeSkinIndex();
    if (skinIndex > 0 && figure.hasAlternatives()) {
        int altListIndex = skinIndex - 1;
        if (altListIndex < figure.getAlternatives().size()) {
            return figure.getAlternatives().get(altListIndex).texture();
        }
    }

    // Check if this is a player figure
    if (figure.getType() == FigureType.PLAYER && figure.getPlayerUUID() != null) {
        // --- START OF FIX ---
        // 1. Prioritize the snapshot stored directly on the block entity. This is the most reliable way to preserve the skin.
        String blockSnapshot = animatable.getSkinSnapshot();
        if (blockSnapshot != null && !blockSnapshot.isEmpty()) {
            String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId() + "_block"; // Use unique key for block-specific profiles
            GameProfile profile = snapshotProfileCache.computeIfAbsent(uniqueFigureId, id -> {
                GameProfile newProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
                newProfile.getProperties().put("textures", new Property("textures", blockSnapshot));
                return newProfile;
            });

            snapshotRegistrationCache.computeIfAbsent(uniqueFigureId, id -> {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, texture) -> {}, true));
                return true;
            });

            return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
        }
        // --- END OF FIX ---

        // 2. Fallback for UI previews or blocks created before this fix: Check ClientDiscoveryManager
        String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId();
        String discoverySnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);
        if (discoverySnapshot != null && !discoverySnapshot.isEmpty()) {
            GameProfile profile = snapshotProfileCache.computeIfAbsent(uniqueFigureId, id -> {
                GameProfile newProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
                newProfile.getProperties().put("textures", new Property("textures", discoverySnapshot));
                return newProfile;
            });
            snapshotRegistrationCache.computeIfAbsent(uniqueFigureId, id -> {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, texture) -> {}, true));
                return true;
            });
            return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
        }

        // 3. Final fallback: Render the live skin if no snapshot is found anywhere
        GameProfile gameProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
        return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(gameProfile);
    }

    // Static figure - use the predefined texture path
    ResourceLocation texturePath = figure.getTexturePath();
    return texturePath != null ? texturePath : FALLBACK_TEXTURE;
}
```

### Summary of Changes

1.  **`FigureBlockEntity`** now has a `skinSnapshot` field to store the skin URL in its NBT data.
2.  When a `FigureBlock` is extracted from a `BoxBlock`, the code now checks the player's capability for a saved snapshot and writes it to the new `FigureBlock` item's NBT.
3.  The `FigureBlockModel`'s rendering logic is updated to **first** check for a snapshot on the `FigureBlockEntity` itself. This ensures the placed block uses its own stored skin, making it permanent and immune to live skin changes.

After applying these changes, your player figures will correctly retain their skin from the moment they were discovered, regardless of who is viewing them or if the original player changes their skin later.