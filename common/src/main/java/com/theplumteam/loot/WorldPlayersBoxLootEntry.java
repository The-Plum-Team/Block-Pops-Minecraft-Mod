package com.theplumteam.loot;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.item.BlockEntityItemData;
import com.theplumteam.registry.ModItems;
import com.theplumteam.registry.ModLoot;
import com.theplumteam.util.AuthlibProfiles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

//? if >=1.21 {
/*import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
*///? } else {
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
//? }
//? if >=26.3 {
/*import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.loot.entries.SingleEntryContainerBase;
import java.util.Optional;
*///? } else {
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
//? }
//? if <26 {
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryType;
//? }

/**
 * Loot pool entry {@code blockpops:world_players_box}: rolls a box holding a random figure of the
 * World Players collection. Unlike a claw machine grant it has no recipient, so it spends no token
 * and unlocks no discovery.
 */
public final class WorldPlayersBoxLootEntry
        //? if >=26.3 {
        /*extends SingleEntryContainerBase {
        *///? } else {
        extends LootPoolSingletonContainer {
        //? }
    private static final String COLLECTION_ID = PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID;

    //? if >=26.3 {
    /*// 26.3 replaced the condition and function lists with one optional holder each.
    public static final MapCodec<WorldPlayersBoxLootEntry> CODEC = RecordCodecBuilder.mapCodec(
            instance -> uniformFields(instance).apply(instance, WorldPlayersBoxLootEntry::new));

    private WorldPlayersBoxLootEntry(int weight, int quality,
            Optional<Holder<LootItemCondition>> condition, Optional<Holder<LootItemFunction>> modifier) {
        super(weight, quality, condition, modifier);
    }
    *///? } elif >=1.21 {
    /*public static final MapCodec<WorldPlayersBoxLootEntry> CODEC = RecordCodecBuilder.mapCodec(
            instance -> singletonFields(instance).apply(instance, WorldPlayersBoxLootEntry::new));

    private WorldPlayersBoxLootEntry(int weight, int quality,
            List<LootItemCondition> conditions, List<LootItemFunction> functions) {
        super(weight, quality, conditions, functions);
    }
    *///? } else {
    private WorldPlayersBoxLootEntry(int weight, int quality,
            LootItemCondition[] conditions, LootItemFunction[] functions) {
        super(weight, quality, conditions, functions);
    }
    //? }

    @Override
    //? if >=26 {
    /*public MapCodec<WorldPlayersBoxLootEntry> codec() {
        return CODEC;
    }
    *///? } else {
    public LootPoolEntryType getType() {
        return ModLoot.WORLD_PLAYERS_BOX.get();
    }
    //? }

    @Override
    public void createItemStack(Consumer<ItemStack> output, LootContext context) {
        // An absent collection, or one without an enabled player, rolls nothing
        List<FigureDefinition> figures = CollectionRegistry.getCollection(COLLECTION_ID)
                .map(FigureCollection::getEnabledFigures).orElse(List.of());
        if (figures.isEmpty()) return;

        FigureDefinition selectedFigure = figures.get(context.getRandom().nextInt(figures.size()));
        PopBlockColor color = selectedFigure.getFavoriteColor();
        if (color == null) color = PopBlockColor.ORIGINAL;
        ItemStack boxItem = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color).get());

        // Same item data as the claw machine grant in DropBoxPacket
        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.putString("FigureId", selectedFigure.getId());
        blockEntityTag.putString("CollectionId", COLLECTION_ID);
        blockEntityTag.putString("Color", color.name());

        UUID playerId = selectedFigure.getPlayerUUID();
        if (playerId != null) {
            String skinSnapshot = getOnlineSkinSnapshot(context, playerId);
            if (skinSnapshot != null && !skinSnapshot.isEmpty()) {
                blockEntityTag.putString("SkinSnapshot", skinSnapshot);
            }

            String qsId = getQuickSkinIdFromServer(playerId);
            if (qsId != null && !qsId.isEmpty()) {
                blockEntityTag.putString("QuickSkinId", qsId);
            }
        }

        BlockEntityItemData.write(boxItem, blockEntityTag, "blockpops:box_block");
        output.accept(boxItem);
    }

    /** The skin an online player already carries; a roll must not wait on the session service. */
    @Nullable
    private static String getOnlineSkinSnapshot(LootContext context, UUID playerId) {
        ServerPlayer player = context.getLevel().getServer().getPlayerList().getPlayer(playerId);
        if (player == null) return null;
        var textures = AuthlibProfiles.properties(player.getGameProfile()).get("textures");
        return textures.isEmpty() ? null : AuthlibProfiles.value(textures.iterator().next());
    }

    @Nullable
    private static String getQuickSkinIdFromServer(UUID playerId) {
        try {
            Class<?> repoClass = Class.forName("com.quickskin.mod.server.data.ServerPlayerAppearanceRepository");
            java.lang.reflect.Method getInstanceMethod = repoClass.getMethod("getInstance");
            Object repoInstance = getInstanceMethod.invoke(null);

            java.lang.reflect.Method getAppearanceMethod = repoClass.getMethod("getAppearance", UUID.class);
            Object appearance = getAppearanceMethod.invoke(repoInstance, playerId);

            if (appearance != null) {
                Class<?> appearanceClass = appearance.getClass();
                java.lang.reflect.Method getSkinIdMethod = appearanceClass.getMethod("getSkinId");
                return (String) getSkinIdMethod.invoke(appearance);
            }
        } catch (Exception e) {
            // Quick Skin not installed or error accessing
        }
        return null;
    }

    //? if <1.21 {
    public static final class Serializer extends LootPoolSingletonContainer.Serializer<WorldPlayersBoxLootEntry> {
        @Override
        protected WorldPlayersBoxLootEntry deserialize(JsonObject json, JsonDeserializationContext context,
                int weight, int quality, LootItemCondition[] conditions, LootItemFunction[] functions) {
            return new WorldPlayersBoxLootEntry(weight, quality, conditions, functions);
        }
    }
    //? }
}
