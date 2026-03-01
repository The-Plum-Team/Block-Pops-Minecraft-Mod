package com.theplumteam.client.particle;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import java.util.function.Function;

public class BlockParticleHelper {

    private static final ResourceLocation MISSING_CHECK_SPRITE = ResourceLocation.fromNamespaceAndPath("blockpops", "_missing_particle_");

    private static class CustomSpriteParticle extends TerrainParticle {
        public CustomSpriteParticle(ClientLevel level, double x, double y, double z,
                                    double xd, double yd, double zd, BlockState state, BlockPos pos) {
            super(level, x, y, z, xd, yd, zd, state, pos);
        }

        public void setCustomSprite(TextureAtlasSprite sprite) {
            this.setSprite(sprite);
        }
    }

    public static boolean spawnBoxDestroyParticles(Level level, BlockPos pos, BoxBlockEntity boxBE) {
        if (!(level instanceof ClientLevel clientLevel)) return false;

        // Check color override first (for default collection colored boxes)
        PopBlockColor color = boxBE.getColor();
        if (color != null) {
            ResourceLocation colorTexture = ResourceLocation.fromNamespaceAndPath("blockpops",
                    "textures/block/box/" + color.getTextureName().toLowerCase(Locale.ROOT) + ".png");
            TextureAtlasSprite sprite = getSpriteOrNull(convertTextureToSpriteId(colorTexture));
            if (sprite != null) {
                spawnParticlesWithSprite(clientLevel, pos, sprite);
                return true;
            }
        }

        // Then check collection box texture
        String collectionId = boxBE.getCollectionId();
        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
        if (collection == null) return false;

        ResourceLocation boxTexture = collection.getBoxTexture();
        if (boxTexture == null) return false;

        TextureAtlasSprite sprite = getSpriteOrNull(convertTextureToSpriteId(boxTexture));
        if (sprite == null) return false;

        spawnParticlesWithSprite(clientLevel, pos, sprite);
        return true;
    }

    public static boolean spawnFigureDestroyParticles(Level level, BlockPos pos, FigureBlockEntity figureBE) {
        if (!(level instanceof ClientLevel clientLevel)) return false;

        FigureDefinition figure = figureBE.getFigureDefinition();
        if (figure != null && figure.getTexturePath() != null) {
            TextureAtlasSprite sprite = getSpriteOrNull(convertTextureToSpriteId(figure.getTexturePath()));
            if (sprite != null) {
                spawnParticlesWithSprite(clientLevel, pos, sprite);
                return true;
            }
        }

        String collectionId = figureBE.getCollectionId();
        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
        if (collection != null && collection.getBoxTexture() != null) {
            TextureAtlasSprite sprite = getSpriteOrNull(convertTextureToSpriteId(collection.getBoxTexture()));
            if (sprite != null) {
                spawnParticlesWithSprite(clientLevel, pos, sprite);
                return true;
            }
        }

        return false;
    }

    private static TextureAtlasSprite getSpriteOrNull(ResourceLocation spriteId) {
        Function<ResourceLocation, TextureAtlasSprite> atlas = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite sprite = atlas.apply(spriteId);
        TextureAtlasSprite missing = atlas.apply(MISSING_CHECK_SPRITE);
        return (sprite == missing) ? null : sprite;
    }

    private static ResourceLocation convertTextureToSpriteId(ResourceLocation texturePath) {
        String path = texturePath.getPath();
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - ".png".length());
        }
        return ResourceLocation.fromNamespaceAndPath(texturePath.getNamespace(), path);
    }

    private static void spawnParticlesWithSprite(ClientLevel level, BlockPos pos, TextureAtlasSprite sprite) {
        BlockState dummyState = Blocks.STONE.defaultBlockState();

        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    double px = pos.getX() + (x + 0.5) / 4.0;
                    double py = pos.getY() + (y + 0.5) / 4.0;
                    double pz = pos.getZ() + (z + 0.5) / 4.0;

                    CustomSpriteParticle particle = new CustomSpriteParticle(
                            level, px, py, pz,
                            px - pos.getX() - 0.5,
                            py - pos.getY() - 0.5,
                            pz - pos.getZ() - 0.5,
                            dummyState, pos
                    );
                    particle.setCustomSprite(sprite);

                    Minecraft.getInstance().particleEngine.add(particle);
                }
            }
        }
    }
}
