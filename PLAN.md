Of course! Here is a very detailed plan for implementing the token and cooldown system for the BlockPops mod. This plan outlines the necessary changes and additions, focusing on structure and logic without providing the full code.

### **Project Plan: Figure Token & Cooldown System**

---

### **Phase 1: Data Storage - Enhancing Player Capabilities**

The foundation of this system is tracking each player's tokens and cooldowns. We will extend the existing `IPlayerDiscovery` capability to store this new information.

**1. Modify `IPlayerDiscovery.java` (Interface)**
*   **Goal:** Define the methods for managing tokens and timers.
*   **File:** `forge/src/main/java/com/theplumteam/capability/IPlayerDiscovery.java`
*   **Additions:**
    *   `int getRegularTokens();`
    *   `void setRegularTokens(int count);`
    *   `long getNextRegularTokenTime();` // Will store the world time in ticks for the next token
    *   `void setNextRegularTokenTime(long worldTimeTicks);`
    *   `long getLastSpecialTokenResetTimestamp();` // `System.currentTimeMillis()` of the last daily reset
    *   `void setLastSpecialTokenResetTimestamp(long timestamp);`
    *   `boolean hasUsedTodaySpecialToken();`
    *   `void setUsedTodaySpecialToken(boolean used);`

**2. Modify `PlayerDiscovery.java` (Implementation)**
*   **Goal:** Implement the new interface methods and handle saving/loading the data.
*   **File:** `forge/src/main/java/com/theplumteam/capability/PlayerDiscovery.java`
*   **Additions:**
    *   **New Fields:**
        *   `private int regularTokens = 0;`
        *   `private long nextRegularTokenTime = 0;`
        *   `private long lastSpecialTokenResetTimestamp = 0;`
        *   `private boolean usedTodaySpecialToken = false;`
    *   **Implement New Methods:** Add the logic for the getters and setters defined in the interface.
    *   **Update `serializeNBT()`:** Save the new fields to the player's NBT data.
        *   `tag.putInt("RegularTokens", this.regularTokens);`
        *   `tag.putLong("NextRegularTokenTime", this.nextRegularTokenTime);`
        *   ...and so on for the other new fields.
    *   **Update `deserializeNBT()`:** Load the new fields from NBT.

---

### **Phase 2: Server-Side Logic - Granting Tokens**

This logic will run on the server to track time and award tokens to players automatically.

**1. Create a New Class: `ServerTickHandler.java`**
*   **Goal:** Create a centralized place to manage time-based game mechanics for all players.
*   **Location:** `forge/src/main/java/com/theplumteam/server/` (new package)
*   **Structure:**
    *   This class will subscribe to the Forge `ServerTickEvent`.
    *   It will contain constants for cooldowns (e.g., `REGULAR_TOKEN_COOLDOWN_TICKS = 3 * 60 * 60 * 20;`).
    *   It will define the daily reset time (e.g., `RESET_HOUR_UTC = 18;` for 6 PM).

**2. Implement Token Generation Logic in `ServerTickHandler`**
*   **Inside the `onServerTick` event handler:**
    *   Iterate through all online `ServerPlayer`s.
    *   For each player, get their `IPlayerDiscovery` capability.
    *   **Regular Token Logic:**
        1.  Get the current `server.overworld().getGameTime()`.
        2.  If `gameTime >= capability.getNextRegularTokenTime()` and `capability.getRegularTokens() < 3`:
            *   Increment the player's regular token count.
            *   Set the next token time: `capability.setNextRegularTokenTime(gameTime + REGULAR_TOKEN_COOLDOWN_TICKS);`.
            *   Send a sync packet (see Phase 3) to the client to update their UI.
    *   **Special Token Logic:**
        1.  Use `java.time.ZonedDateTime` to check the current UTC time against the `RESET_HOUR_UTC`.
        2.  Get `capability.getLastSpecialTokenResetTimestamp()`.
        3.  If the current day is different from the day of the last reset *and* the player has used their token (`hasUsedTodaySpecialToken() == true`):
            *   Reset the flag: `capability.setUsedTodaySpecialToken(false);`.
            *   Update the timestamp: `capability.setLastSpecialTokenResetTimestamp(System.currentTimeMillis());`.
            *   Send a sync packet to the client.

---

### **Phase 3: Networking - Syncing Data to the Client**

The client needs to be aware of its token status to display it in the UI.

**1. Create a New Packet: `SyncTokenDataPacket.java`**
*   **Goal:** A server-to-client packet to sync all token and cooldown information.
*   **Location:** `forge/src/main/java/com/theplumteam/network/`
*   **Fields:**
    *   `int regularTokens;`
    *   `long ticksUntilNextRegular;`
    *   `boolean hasSpecialToken;`
    *   `long millisUntilNextSpecialReset;`
*   **Handler Logic (Client-Side):** The packet handler will call a new client-side manager to store this data.

**2. Create a New Manager: `ClientTokenManager.java`**
*   **Goal:** A static class to cache the player's token status on the client.
*   **Location:** `forge/src/main/java/com/theplumteam/client/` (or a sub-package)
*   **Structure:**
    *   Static fields to hold the token data received from the sync packet.
    *   A static `update(packet)` method to refresh the data.
    *   Getter methods for the UI to access (e.g., `getRegularTokens()`).

**3. Modify `BlockPopsModForge.java`**
*   **Goal:** Register the new packet and send it at the appropriate times.
*   **In `registerNetworkPackets()`:** Register `SyncTokenDataPacket`.
*   **In `PlayerEvent.PLAYER_JOIN`:** After syncing discovery data, also send an initial `SyncTokenDataPacket` to the joining player.

---

### **Phase 4: UI Overhaul - Claw Machine Screen**

The `CollectionSelectionScreen` will be updated to display token information and provide new interaction buttons.

**1. Modify `CollectionSelectionScreen.java`**
*   **Goal:** Rework the UI to support the new token system.
*   **File:** `forge/src/main/java/com/theplumteam/client/gui/CollectionSelectionScreen.java`
*   **Changes in `init()`:**
    *   Remove the old `dropBoxButton`.
    *   Create two new `Button` widgets: `useRegularButton` and `useSpecialButton`.
    *   Position these buttons where the old one was, or side-by-side.
    *   **Button Logic:** The `onPress` action for each button will create a `DropBoxPacket` with a new parameter indicating the token type being used (see Phase 5).
*   **Changes in `render()`:**
    *   In a dedicated area (e.g., below the title or above the buttons), draw new text strings.
    *   Fetch data from `ClientTokenManager` to display:
        *   `"Regular Tokens: " + ClientTokenManager.getRegularTokens() + "/3"`
        *   Format and display the time remaining for the next regular token.
        *   `"Guaranteed Token: " + (ClientTokenManager.hasSpecialToken() ? "Available" : "Used")`
        *   Format and display the time until the next daily reset.
*   **Add a new `tick()` method:**
    *   This method will be called every client tick. Use it to update the internal state of the buttons.
    *   `useRegularButton.active = ClientTokenManager.getRegularTokens() > 0 && selectedCollectionId != null;`
    *   `useSpecialButton.active = ClientTokenManager.hasSpecialToken() && selectedCollectionId != null && !isCollectionComplete();`
    *   The `isCollectionComplete()` check is a new helper method you'll create. It will iterate through the selected collection's figures and check `ClientDiscoveryManager.isDiscovered()` for each one.

---

### **Phase 5: Gameplay Logic - Using Tokens**

The final step is to update the server-side logic that handles the box drop request to respect the token system.

**1. Create Enum `TokenType.java`**
*   **Goal:** A simple enum to differentiate between token types in packets.
*   **Location:** `forge/src/main/java/com/theplumteam/network/`
*   **Values:** `REGULAR`, `GUARANTEED`

**2. Modify `DropBoxPacket.java`**
*   **Goal:** Add context for which token is being used.
*   **File:** `forge/src/main/java/com/theplumteam/network/DropBoxPacket.java`
*   **Additions:**
    *   Add a new field: `private final TokenType tokenType;`
    *   Update the constructor, `encode`, and `decode` methods to include this new field.

**3. Rework `DropBoxPacket.handle()` Logic**
*   **Goal:** Process the drop request based on the token type.
*   **File:** `forge/src/main/java/com/theplumteam/network/DropBoxPacket.java`
*   **New Logic Flow:**
    1.  Get the player and their `IPlayerDiscovery` capability.
    2.  **Verify Token:**
        *   If `packet.getTokenType() == REGULAR`, check if `capability.getRegularTokens() > 0`.
        *   If `packet.getTokenType() == GUARANTEED`, check if `!capability.hasUsedTodaySpecialToken()`.
        *   If the check fails, `return` early to prevent cheating.
    3.  **Consume Token:**
        *   If `REGULAR`, decrement the token count.
        *   If `GUARANTEED`, set `capability.setUsedTodaySpecialToken(true)`.
    4.  **Select Figure:**
        *   If `REGULAR`, use the existing logic: pick any random figure from the collection.
        *   If `GUARANTEED`:
            a. Get all figures for the `collectionId`.
            b. Get the player's `getDiscoveredSet()`.
            c. Create a new list of `undiscoveredFigures` by filtering the first list.
            d. If `undiscoveredFigures` is not empty, pick a random figure from it.
            e. If it's empty (collection is complete), give a random duplicate as a fallback.
    5.  **Spawn Box:** The rest of the logic for creating the `ItemStack` with NBT and spawning the `ItemEntity` remains the same.
    6.  **Sync Back:** After consuming the token, immediately create and send a `SyncTokenDataPacket` back to the player so their UI updates in real-time.