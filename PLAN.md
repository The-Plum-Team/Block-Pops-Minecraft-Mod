Of course. This is an excellent feature that adds a lot of value and collectibility without cluttering the game with dozens of separate items. Here is a very detailed, step-by-step plan to implement the alternative figure system.

### **High-Level Goal**

Transform the current system where alternative skins are separate figures into a unified system where a single figure can have multiple, switchable appearances. The player will interact with placed figures (in boxes or standalone) using a Stick to cycle through these alternatives.

---

### **Phase 1: Redesigning the Data Structure**

The foundation of this system is how you define and load the alternative skins. This requires changes to your JSON collection format and the Java classes that represent them.

#### **1.1. Modify the Collection JSON Format**

Currently, your `jojos.json` has entries like `jotaro_part3` and `jotaro_part3_alt`. We will merge these. The "main" figure will now contain an array of its alternatives.

**Before (Example from `jojos.json`):**

```json
// ...
{
  "id": "jotaro_part3",
  "name": "Jotaro Kujo (Part 3)",
  "model": "blockpops:geo/figure/box_figure_default.geo.json",
  "texture": "blockpops:textures/figure/jojos/jotaro_part3.png",
  "animation": "blockpops:animations/figure/box_figure_default.animation.json"
},
// ...
{
  "id": "jotaro_part3_alt",
  "name": "Jotaro Kujo (Part 3 Alt)",
  "model": "blockpops:geo/figure/box_figure_default.geo.json",
  "texture": "blockpops:textures/figure/jojos/jotaro_part3_alt.png",
  "animation": "blockpops:animations/figure/box_figure_default.animation.json"
}
// ...
```

**After (Proposed new structure):**

```json
// ...
{
  "id": "jotaro_part3",
  "name": "Jotaro Kujo (Part 3)",
  "model": "blockpops:geo/figure/box_figure_default.geo.json",
  "texture": "blockpops:textures/figure/jojos/jotaro_part3.png",
  "animation": "blockpops:animations/figure/box_figure_default.animation.json",
  "alternatives": [
    {
      "name": "Jotaro Kujo (Part 3 Alt)",
      "texture": "blockpops:textures/figure/jojos/jotaro_part3_alt.png"
    }
    // You could add more alternatives here
  ]
}
// ... (The separate "jotaro_part3_alt" figure is now removed)
```

**Why this structure?**
*   **Scalable:** You can add any number of alternatives without creating new top-level figure definitions.
*   **Efficient:** Alternatives often share the same model and animation, so we only need to specify what changes (the texture and display name).
*   **Clear:** It logically groups variants under a single primary figure.

#### **1.2. Update the Java Data Classes**

1.  **Create an `AlternativeSkin` class/record:** This will represent a single alternative.
    ```java
    // In figure package, maybe as a nested class or standalone file.
    // A record is perfect for this immutable data structure.
    public record AlternativeSkin(String name, ResourceLocation texture) {
        public static AlternativeSkin fromJson(JsonObject json) {
            String name = json.get("name").getAsString();
            ResourceLocation texture = new ResourceLocation(json.get("texture").getAsString());
            return new AlternativeSkin(name, texture);
        }
    }
    ```

2.  **Modify `FigureDefinition.java`:** Add a list to hold the alternatives.
    *   Add a new field: `private final List<AlternativeSkin> alternatives;`
    *   Update the constructor to accept this list and initialize it.
    *   Add a getter: `public List<AlternativeSkin> getAlternatives()`.
    *   Add a helper method: `public boolean hasAlternatives()`.

3.  **Modify `FigureCollection.java`:** Update the `fromJson` parsing logic.
    *   Inside the `FigureDefinition.fromJson` method (or wherever you parse individual figures), check if the JSON object has an `"alternatives"` array.
    *   If it does, loop through the array, create `AlternativeSkin` objects using `AlternativeSkin.fromJson()`, and add them to a list.
    *   Pass this list to the `FigureDefinition` constructor.

---

### **Phase 2: Block Entity State Management**

The `BlockEntity` for both the box and the figure needs to store which alternative is currently active.

1.  **Add State Field:**
    *   In `BoxBlockEntity.java` and `FigureBlockEntity.java`, add a new field:
        ```java
        private int alternativeSkinIndex = 0; // 0 is default, 1+ are from the alternatives list
        ```

2.  **Persist State (NBT):**
    *   In both `BlockEntity` classes, update the NBT methods to save and load this index.
    *   **`saveAdditional(CompoundTag tag)`:** `tag.putInt("AlternativeSkinIndex", this.alternativeSkinIndex);`
    *   **`load(CompoundTag tag)`:** `this.alternativeSkinIndex = tag.getInt("AlternativeSkinIndex");`
    *   **Crucially, also update `getUpdateTag()` and `handleUpdateTag()`** to ensure this data is sent to the client on chunk load and block updates. The current implementation where `getUpdateTag` calls `saveAdditional` is perfect and will handle this automatically.

3.  **Create Cycling Logic:**
    *   In both `BlockEntity` classes, add a new public method to cycle through the skins.
    ```java
    public void cycleAlternativeSkin() {
        FigureDefinition def = getFigureDefinition();
        if (def == null || !def.hasAlternatives()) {
            return; // No figure or no alternatives to cycle.
        }

        int totalSkins = 1 + def.getAlternatives().size(); // 1 for the default skin
        this.alternativeSkinIndex = (this.alternativeSkinIndex + 1) % totalSkins;

        // Mark for saving and send an update to the client.
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }
    ```
4.  **Add Getter:**
    *   Add a getter `public int getAlternativeSkinIndex()` in both `BlockEntity` classes.

---

### **Phase 3: Player Interaction Logic**

Implement the right-click-with-a-stick mechanic. This happens in the `Block` classes.

1.  **Modify `BoxBlock.java`:**
    *   In the `use` method, add a new check at the beginning.
    ```java
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack heldItem = player.getItemInHand(hand);

        // --- NEW LOGIC START ---
        if (heldItem.is(net.minecraft.world.item.Items.STICK)) {
            if (!level.isClientSide()) {
                if (level.getBlockEntity(pos) instanceof BoxBlockEntity boxBlockEntity && boxBlockEntity.hasFigure()) {
                    boxBlockEntity.cycleAlternativeSkin();
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        // --- NEW LOGIC END ---

        // Shift-right-click to open adjustment screen
        if (level.isClientSide && player.isShiftKeyDown()) {
            // ... existing code
        }
        // ... rest of the existing use method
    }
    ```

2.  **Create `FigureBlock.java`'s `use` method:**
    *   `FigureBlock` currently lacks a `use` method. You need to add it.
    ```java
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack heldItem = player.getItemInHand(hand);
        if (heldItem.is(net.minecraft.world.item.Items.STICK)) {
            if (!level.isClientSide()) {
                if (level.getBlockEntity(pos) instanceof FigureBlockEntity figureBlockEntity && figureBlockEntity.hasFigure()) {
                    figureBlockEntity.cycleAlternativeSkin();
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS; // Pass to allow other interactions if needed
    }
    ```

---

### **Phase 4: Rendering Logic**

This is where the client uses the synced `alternativeSkinIndex` to render the correct texture.

1.  **Modify `FigureModel.java`:**
    *   The `getTextureResource` method is the key. It needs to read the index from the `BoxBlockEntity`.
    ```java
    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return FALLBACK_TEXTURE;
        }

        // --- NEW LOGIC START ---
        int skinIndex = animatable.getAlternativeSkinIndex();

        if (skinIndex == 0) {
            // Default skin
        } else if (skinIndex > 0 && figure.hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < figure.getAlternatives().size()) {
                // Return the alternative texture
                return figure.getAlternatives().get(altListIndex).texture();
            }
        }
        // --- NEW LOGIC END ---

        // Check for player figure (dynamic skin) - This should come AFTER alt check
        if (figure.getType() == FigureType.PLAYER && figure.getPlayerUUID() != null) {
            // ... existing player skin logic
        }

        // Static figure - use the predefined texture path
        ResourceLocation texturePath = figure.getTexturePath();
        return texturePath != null ? texturePath : FALLBACK_TEXTURE;
    }
    ```

2.  **Modify `FigureBlockModel.java`:**
    *   Apply the exact same logic change to `getTextureResource(FigureBlockEntity animatable)`. The code will be nearly identical, just using `FigureBlockEntity` instead of `BoxBlockEntity`.

---

### **Phase 5: UI and Tooltip Enhancements (Polish)**

Inform the player that a figure has alternatives.

1.  **Modify `GeoBlockItem.java`:**
    *   In `appendHoverText`, when you have determined the `figure` within the item's NBT, check if it has alternatives.
    ```java
    // Inside appendHoverText, after getting the FigureDefinition
    if (figure != null && figure.hasAlternatives()) {
        tooltip.add(Component.translatable("tooltip.blockpops.has_alternatives")
            .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
    }
    ```
    *   Add the corresponding entry to `en_us.json`:
        ```json
        "tooltip.blockpops.has_alternatives": "Has alternative skins! (Right-click with a stick)"
        ```

2.  **Modify `CollectionSelectionScreen` (Optional but recommended):**
    *   In `FigureEntry.java`, when rendering a figure cell, you can add a small visual indicator (e.g., a "+" icon or a sparkle) in the corner if the figure has alternatives.
    *   In the `render` method of `FigureEntry`:
        ```java
        // After rendering the figure cell background
        if (figure.hasAlternatives()) {
            // Render your indicator icon at a corner of the cell
            // e.g., graphics.drawString(mc.font, "+", figureX + FIGURE_SIZE - 8, y + 2, 0xFFD700);
        }
        ```

### **Summary of Plan**

1.  **Data:** Change JSON to nest alternatives under a primary figure. Update `FigureDefinition` to store a `List<AlternativeSkin>`.
2.  **State:** Add `alternativeSkinIndex` to `BoxBlockEntity` and `FigureBlockEntity`, sync it via existing NBT mechanisms.
3.  **Interaction:** Modify the `use` method in `BoxBlock` and `FigureBlock` to detect a Stick right-click, which calls `cycleAlternativeSkin()` on the server.
4.  **Rendering:** Update `getTextureResource` in `FigureModel` and `FigureBlockModel` to select the texture based on the `alternativeSkinIndex` from the `BlockEntity`.
5.  **UX:** Add tooltips to items and visual cues in the GUI to notify players about available alternatives.

This plan provides a robust, scalable, and user-friendly system for alternative figures, neatly integrating into your existing architecture.