package com.theplumteam.figure;

import com.google.gson.JsonObject;
import com.theplumteam.figure.CollectionFamilies.Family;
import com.theplumteam.network.SyncDynamicCollectionsPacket;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.PlayerAssetIds;
import com.theplumteam.worldcontent.WorldCollectionMaterializer;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each family of collections has its own way to a client. The shared sync, which a client of
 * any version registers unchecked, carries built-in collections and nothing else.
 */
class CollectionFamiliesTest {
    private static final String PLAYER = "p_k3v7q2m5x4ab";
    private static final String SKIN = JdkSha256.of("skin".getBytes(StandardCharsets.UTF_8));
    private static final Map<String, BlobInfo> BLOBS = Map.of(SKIN, BlobInfo.png(1_000, 64, 64));

    private static WorldCollectionRecord record() {
        return new WorldCollectionRecord("w-col", "Col", "Me", 10, 20, 30, null, "red", null, false, 3,
                List.of(new WorldCollectionRecord.Figure("zombi", "Zombi", WorldCollectionRecord.Kind.SKIN, SKIN, null,
                        null, 1.0f, true)));
    }

    @Test
    void anIdBelongsToOneFamily() {
        assertEquals(Family.WORLD, CollectionFamilies.family("w-col"));
        assertEquals(Family.PLAYER, CollectionFamilies.family(PLAYER));
        // The whole reserved space is the player family's, valid id or not
        assertEquals(Family.PLAYER, CollectionFamilies.family("p_not_a_real_one"));
        assertEquals(Family.PREVIEW, CollectionFamilies.family("creator_preview.1"));
        for (String builtIn : new String[] {"default", "world_players", "sonic", "pokemon", "w_col", "wcol", null}) {
            assertEquals(Family.BUILT_IN, CollectionFamilies.family(builtIn), String.valueOf(builtIn));
        }
        assertTrue(CollectionFamilies.isMaterialized("w-col"));
        assertTrue(CollectionFamilies.isMaterialized(PLAYER));
        assertFalse(CollectionFamilies.isMaterialized("p_not_a_real_one"));
        assertFalse(CollectionFamilies.isMaterialized("sonic"));
        assertFalse(CollectionFamilies.isMaterialized(null));

        assertTrue(CollectionFamilies.genericSyncAllows("sonic"));
        assertTrue(CollectionFamilies.genericSyncAllows("world_players"));
        for (String kept : new String[] {"w-col", PLAYER, "p_not_a_real_one", "creator_preview.1"}) {
            assertFalse(CollectionFamilies.genericSyncAllows(kept), kept);
        }
    }

    @Test
    void theSharedSyncCarriesOnlyBuiltInCollections() throws WorldContentException {
        FigureCollection world = WorldCollectionMaterializer.materialize(record(), BLOBS);
        WorldCollectionRecord published = WorldCollectionRecord.parsePlayer(record().toPlayerUtf8(), BLOBS, PLAYER,
                "Steve", 1);
        FigureCollection player = WorldCollectionMaterializer.materialize(published, BLOBS, new PlayerAssetIds(PLAYER));
        JsonObject json = world.toJson();
        json.addProperty("id", "sonic");
        FigureCollection builtIn = FigureCollection.fromJson(json);
        assertEquals("sonic", builtIn.getId());

        SyncDynamicCollectionsPacket packet = new SyncDynamicCollectionsPacket(List.of(world, builtIn, player));
        assertEquals(1, packet.getCollectionsJson().size());
        assertTrue(packet.getCollectionsJson().get(0).contains("\"sonic\""));
        for (String sent : packet.getCollectionsJson()) {
            assertFalse(sent.contains(PLAYER), sent);
            assertFalse(sent.contains("textures/player/"), sent);
            assertFalse(sent.contains("w-col"), sent);
        }
        assertTrue(new SyncDynamicCollectionsPacket(List.of(player, world)).getCollectionsJson().isEmpty());
    }
}
