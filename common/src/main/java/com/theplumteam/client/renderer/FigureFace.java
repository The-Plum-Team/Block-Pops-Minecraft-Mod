package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.util.GeoAssets;
import net.minecraft.resources.ResourceLocation;

/**
 * The figure's face drawn on the front of the box.
 *
 * <p>The box draws it with two bones that read a player skin's head front, at (8, 8),
 * and its hat layer, at (40, 8), each 8 pixels square on a 64 pixel texture. A figure
 * with its own model has a texture laid out differently, so it names where its face is
 * ({@code box_face_uv}) and the head bone reads that region instead; the hat bone is
 * not drawn. A figure with its own model that names no face shows none.
 */
public final class FigureFace {
    private static final ResourceLocation DEFAULT_MODEL = GeoAssets.model(BlockPopsMod.MOD_ID, "figure/box_figure_default");
    // The head front the face bone reads on the box model's 64 pixel texture.
    private static final float BONE_UV = 8.0f;
    private static final float BONE_UV_SIZE = 8.0f;
    private static final float BONE_TEXTURE_SIZE = 64.0f;

    private FigureFace() {
    }

    /** Whether the box draws this face bone for the figure; {@code hatLayer} is the 3D overlay bone. */
    public static boolean shows(FigureDefinition figure, boolean hatLayer) {
        if (figure.getRenderOptions().boxFaceUv() != null) {
            return !hatLayer;
        }
        return DEFAULT_MODEL.equals(figure.getModelPath());
    }

    /** The buffer the face bone draws into: remapped to the figure's own face region when it names one. */
    public static VertexConsumer buffer(VertexConsumer buffer, FigureDefinition figure) {
        float[] uv = figure.getRenderOptions().boxFaceUv();
        return uv != null ? new Remap(buffer, uv) : buffer;
    }

    /** Moves the face bone's texture coordinates from the skin's head front to another region. */
    private static final class Remap implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float u0;
        private final float v0;
        private final float uScale;
        private final float vScale;

        private Remap(VertexConsumer delegate, float[] uv) {
            this.delegate = delegate;
            // {u, v, width, height, textureWidth, textureHeight}, in texels
            this.u0 = uv[0] / uv[4];
            this.v0 = uv[1] / uv[5];
            this.uScale = uv[2] / uv[4] * BONE_TEXTURE_SIZE / BONE_UV_SIZE;
            this.vScale = uv[3] / uv[5] * BONE_TEXTURE_SIZE / BONE_UV_SIZE;
        }

        private float u(float u) {
            return u0 + (u - BONE_UV / BONE_TEXTURE_SIZE) * uScale;
        }

        private float v(float v) {
            return v0 + (v - BONE_UV / BONE_TEXTURE_SIZE) * vScale;
        }

        //? if >=1.21 {
        /*@Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u(u), v(v));
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int packedOverlay,
                              int packedLight, float normalX, float normalY, float normalZ) {
            delegate.addVertex(x, y, z, color, u(u), v(v), packedOverlay, packedLight, normalX, normalY, normalZ);
        }
        *///? } else {
        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            delegate.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            delegate.uv(u(u), v(v));
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            delegate.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            delegate.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            delegate.endVertex();
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
            delegate.defaultColor(red, green, blue, alpha);
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }

        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha,
                           float u, float v, int packedOverlay, int packedLight,
                           float normalX, float normalY, float normalZ) {
            delegate.vertex(x, y, z, red, green, blue, alpha, u(u), v(v), packedOverlay, packedLight,
                    normalX, normalY, normalZ);
        }
        //? }

        //? if >=26 {
        /*@Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }
        *///? }

        //? if >=26.3 {
        /*@Override
        public VertexConsumer setUv3(float u, float v) {
            delegate.setUv3(u, v);
            return this;
        }
        *///? }
    }
}
