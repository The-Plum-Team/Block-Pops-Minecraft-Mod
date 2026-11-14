package com.theplumteam.worldcontent;

import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.WorldCollectionRecord.LogoLayout;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static com.theplumteam.worldcontent.WorldCollectionRecordTest.BLOBS;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.LOGO;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.SKIN;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One record builds a World collection or a player collection, which differ only in how the
 * family names its collection and its assets, so neither is ever taken for the other.
 */
class ContentFamilyTest {
    private static final String ID = "p_k3v7q2m5x4ab";
    private static final String OTHER = "p_aaaaaaaaaaaa";

    private static WorldCollectionRecord world() {
        return plain().withExtras("https://example.com/me", new LogoLayout(120, 4, -2), false, null);
    }

    private static WorldCollectionRecord player() throws WorldContentException {
        return WorldCollectionRecord.parsePlayer(world().toPlayerUtf8(), BLOBS, ID, "Steve", 5);
    }

    @Test
    void aWorldCollectionIsBuiltAsItAlwaysWas() {
        WorldCollectionRecord record = world();
        FigureCollection collection = WorldCollectionMaterializer.materialize(record, BLOBS);
        assertEquals(collection.toJson(),
                WorldCollectionMaterializer.materialize(record, BLOBS, ContentIdStrategy.WORLD).toJson());
        FigureDefinition figure = collection.getFigures().get(0);
        assertEquals(WorldAssetIds.texture(SKIN), figure.getTexturePath());
        assertEquals("blockpops:textures/world_content/" + SKIN + ".png", figure.getTexturePath().toString());
        assertEquals(WorldAssetIds.texture(LOGO), collection.getLogoConfig().getTexture());
        assertNull(WorldCollectionCheck.problem(collection, record));
        assertNull(WorldCollectionCheck.problem(collection, record, ContentIdStrategy.WORLD));
        assertTrue(ContentIdStrategy.WORLD.names("w-col"));
        assertFalse(ContentIdStrategy.WORLD.names(ID));
        assertFalse(ContentIdStrategy.WORLD.names("e2e_fixture"));
    }

    @Test
    void aPlayerCollectionsAssetsAreNamedUnderItsOwnId() throws WorldContentException {
        WorldCollectionRecord record = player();
        PlayerAssetIds ids = new PlayerAssetIds(ID);
        FigureCollection collection = WorldCollectionMaterializer.materialize(record, BLOBS, ids);
        assertEquals(ID, collection.getId());
        assertEquals("Steve", collection.getAuthor());
        assertNull(collection.getAuthorUrl());
        ResourceLocation texture = collection.getFigures().get(0).getTexturePath();
        assertEquals("blockpops:textures/player/" + ID + "/" + SKIN + ".png", texture.toString());
        assertEquals(ids.texture(LOGO), collection.getLogoConfig().getTexture());
        assertNull(WorldCollectionCheck.problem(collection, record, ids));

        // The same record under another family, or another collection of the same family
        assertEquals("declares id " + ID, WorldCollectionCheck.problem(collection, record));
        assertEquals("declares id " + ID, WorldCollectionCheck.problem(collection, record, new PlayerAssetIds(OTHER)));
        assertThrows(IllegalArgumentException.class, () -> WorldCollectionMaterializer.materialize(record, BLOBS));
        assertThrows(IllegalArgumentException.class,
                () -> WorldCollectionMaterializer.materialize(record, BLOBS, new PlayerAssetIds(OTHER)));
        assertThrows(IllegalArgumentException.class,
                () -> WorldCollectionMaterializer.materialize(world(), BLOBS, ids));
        // A collection that names a World asset is not this record's
        FigureCollection mixed = WorldCollectionMaterializer.materialize(
                new WorldCollectionRecord("w-col", record.name(), record.author(), record.red(), record.green(),
                        record.blue(), record.boxTexture(), record.boxColor(), record.logo(), false, 5,
                        record.figures(), false, 0L, null, record.logoLayout(), false, null, true), BLOBS);
        assertNotEquals(null, WorldCollectionCheck.problem(mixed, record, ids));
        assertThrows(IllegalArgumentException.class, () -> new PlayerAssetIds("w-col"));
        assertThrows(IllegalArgumentException.class, () -> ids.texture("not a sha"));
    }

    @Test
    void anAssetBelongsToOneFamilyAndOneCollection() {
        PlayerAssetIds ids = new PlayerAssetIds(ID);
        PlayerAssetIds other = new PlayerAssetIds(OTHER);
        for (ResourceLocation asset : new ResourceLocation[] {ids.texture(SKIN), ids.model(SKIN), ids.animation(SKIN)}) {
            assertTrue(ids.owns(asset), asset.toString());
            assertTrue(PlayerAssetIds.ownsAny(asset), asset.toString());
            assertFalse(other.owns(asset), asset.toString());
            assertFalse(WorldAssetIds.owns(asset), asset.toString());
        }
        for (ResourceLocation asset : new ResourceLocation[] {WorldAssetIds.texture(SKIN), WorldAssetIds.model(SKIN),
            WorldAssetIds.animation(SKIN), WorldCollectionMaterializer.FIGURE_ANIMATION,
            WorldCollectionMaterializer.boxColorTexture("red"), FigureDefinition.DEFAULT_MODEL}) {
            assertFalse(PlayerAssetIds.ownsAny(asset), asset.toString());
            assertFalse(ids.owns(asset), asset.toString());
        }
        assertFalse(PlayerAssetIds.ownsAny(null));
        assertNotEquals(ids.texture(SKIN), other.texture(SKIN));
        assertNotEquals(ids.model(SKIN), ids.animation(LOGO));
    }
}
