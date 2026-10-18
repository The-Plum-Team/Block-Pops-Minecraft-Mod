package com.theplumteam.client.renderer;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
//? if <1.21.5 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
//? }
//? if <1.21.2 {
import net.minecraft.client.renderer.ShaderInstance;
//? } elif <1.21.5 {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderProgram;
*///? } else {
/*import net.minecraft.client.renderer.RenderPipelines;
import software.bernie.geckolib.constant.dataticket.DataTicket;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
*///? }
//? if >=26.3 {
/*import com.mojang.renderpearl.api.pipeline.RenderPipeline;
*///? } elif >=1.21.5 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
*///? }

/**
 * The render type of a holographic figure or plush: the entity cutout a figure is always drawn
 * with, under a shader that adds a rainbow foil and sparkles. Until the shader is ready, or if
 * it fails, a holographic figure is drawn as any other.
 *
 * Each era of Minecraft gets there differently. 1.20.1 and 1.21.1 swap a shader instance, which
 * each loader registers and hands to {@link #setShader}, into the render type's setup. 1.21.4
 * swaps a shader program in the same way. From 1.21.5 a render type draws with a pipeline,
 * which is vanilla's entity cutout pipeline with the holographic shaders in place of its own.
 * Vanilla keeps the factory that makes such a render type to itself, so it is reached by
 * reflection, found by its parameters since Fabric runs on other names than Mojang's.
 */
public final class HolographicRenderTypes {
    /** The shader instance 1.20.1 and 1.21.1 load, under shaders/core/rendertype_holographic.json. */
    public static final ResourceLocation SHADER = ResourceLocations.of(BlockPopsMod.MOD_ID, "rendertype_holographic");

    private static final Map<ResourceLocation, RenderType> BY_TEXTURE = new HashMap<>();

    private HolographicRenderTypes() {
    }

    //? if <1.21.2 {
    @Nullable
    private static ShaderInstance shader;

    public static void setShader(ShaderInstance loaded) {
        shader = loaded;
    }

    /** The holographic version of entityCutoutNoCull for a texture. */
    public static RenderType entity(ResourceLocation texture) {
        RenderType base = RenderType.entityCutoutNoCull(texture);
        if (shader == null) {
            return base;
        }
        return BY_TEXTURE.computeIfAbsent(texture, key -> new RenderType("blockpops_holographic",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, false,
                () -> {
                    base.setupRenderState();
                    RenderSystem.setShader(() -> shader);
                },
                base::clearRenderState) {
        });
    }
    //? }

    //? if >=1.21.2 && <1.21.5 {
    /*// 1.21.2 compiles a program the first time it is asked for, from the configuration at
    // shaders/core/rendertype_holographic_program.json
    private static final ShaderProgram PROGRAM = new ShaderProgram(
            ResourceLocations.of(BlockPopsMod.MOD_ID, "core/rendertype_holographic_program"),
            DefaultVertexFormat.NEW_ENTITY, ShaderDefines.EMPTY);

    // The holographic version of entityCutoutNoCull for a texture
    public static RenderType entity(ResourceLocation texture) {
        RenderType base = RenderType.entityCutoutNoCull(texture);
        return BY_TEXTURE.computeIfAbsent(texture, key -> new RenderType("blockpops_holographic",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, false,
                () -> {
                    base.setupRenderState();
                    // A program that failed to compile leaves the figure drawn as any other
                    CompiledShaderProgram program = Minecraft.getInstance().getShaderManager().getProgram(PROGRAM);
                    if (program != null) {
                        RenderSystem.setShader(program);
                    }
                },
                base::clearRenderState) {
        });
    }
    *///? }

    //? if >=1.21.5 {
    /*// GeckoLib 5 draws from a render state, which carries whether the figure is holographic
    public static final DataTicket<Boolean> STATE = DataTicket.create("blockpops:holographic", Boolean.class);

    @Nullable
    private static RenderPipeline pipeline;
    @Nullable
    private static Method factory;
    // Before 26.1, the field of vanilla's render type that holds its texture, lightmap and outline state
    @Nullable
    private static Field state;
    private static boolean failed;

    // The holographic version of the entity cutout figures are drawn with, for a texture
    public static RenderType entity(ResourceLocation texture) {
        RenderType cached = BY_TEXTURE.get(texture);
        if (cached != null) {
            return cached;
        }
        //? if >=26 {
        /^RenderType base = net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(texture);
        ^///? } else {
        RenderType base = RenderType.entityCutoutNoCull(texture);
        //? }
        if (failed) {
            return base;
        }
        try {
            if (pipeline == null) {
                pipeline = buildPipeline();
                factory = findFactory();
            }
            //? if >=26 {
            /^net.minecraft.client.renderer.rendertype.RenderSetup setup =
                    net.minecraft.client.renderer.rendertype.RenderSetup.builder(pipeline)
                            .withTexture("Sampler0", texture)
                            .useLightmap()
                            .useOverlay()
                            .affectsCrumbling()
                            .setOutline(net.minecraft.client.renderer.rendertype.RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
                            .createRenderSetup();
            RenderType holographic = (RenderType) factory.invoke(null, "blockpops_holographic", setup);
            ^///? } else {
            RenderType holographic = (RenderType) factory.invoke(null, "blockpops_holographic",
                    base.bufferSize(), pipeline, state.get(base));
            //? }
            BY_TEXTURE.put(texture, holographic);
            return holographic;
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            BlockPopsMod.LOGGER.warn("Holographic figures are drawn without their shader: {}", e.toString());
            return base;
        }
    }
    *///? }

    //? if >=1.21.5 && <1.21.6 {
    /*// Vanilla's entity cutout pipeline, read through its getters, with the holographic shaders
    private static RenderPipeline buildPipeline() {
        RenderPipeline base = RenderPipelines.ENTITY_CUTOUT_NO_CULL;
        RenderPipeline.Builder builder = copyOf(base, "core/rendertype_holographic");
        for (RenderPipeline.UniformDescription uniform : base.getUniforms()) {
            builder.withUniform(uniform.name(), uniform.type());
        }
        return builder.build();
    }
    *///? }

    //? if >=1.21.6 && <26 {
    /*// Vanilla's entity cutout pipeline, read through its getters, with the holographic shaders
    private static RenderPipeline buildPipeline() {
        RenderPipeline base = RenderPipelines.ENTITY_CUTOUT_NO_CULL;
        RenderPipeline.Builder builder = copyOf(base, "core/rendertype_holographic_ubo");
        for (RenderPipeline.UniformDescription uniform : base.getUniforms()) {
            if (uniform.textureFormat() != null) {
                builder.withUniform(uniform.name(), uniform.type(), uniform.textureFormat());
            } else {
                builder.withUniform(uniform.name(), uniform.type());
            }
        }
        return builder.build();
    }
    *///? }

    //? if >=1.21.5 && <26 {
    /*// Everything of a pipeline but its location, shaders, defines and uniforms
    private static RenderPipeline.Builder copyOf(RenderPipeline base, String shader) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(ResourceLocations.of(BlockPopsMod.MOD_ID, "pipeline/holographic"))
                .withVertexShader(ResourceLocations.of(BlockPopsMod.MOD_ID, shader))
                .withFragmentShader(ResourceLocations.of(BlockPopsMod.MOD_ID, shader))
                .withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode())
                .withCull(base.isCull())
                .withDepthTestFunction(base.getDepthTestFunction())
                .withPolygonMode(base.getPolygonMode())
                .withColorLogic(base.getColorLogic())
                .withColorWrite(base.isWriteColor(), base.isWriteAlpha())
                .withDepthWrite(base.isWriteDepth())
                .withDepthBias(base.getDepthBiasScaleFactor(), base.getDepthBiasConstant());
        if (base.getBlendFunction().isPresent()) {
            builder.withBlend(base.getBlendFunction().get());
        } else {
            builder.withoutBlend();
        }
        base.getSamplers().forEach(builder::withSampler);
        return builder;
    }

    // RenderType.create(String, int, RenderPipeline, CompositeState), and the field of a composite
    // render type that holds that state, found by the type of the last parameter
    private static Method findFactory() throws NoSuchMethodException, NoSuchFieldException {
        for (Method method : RenderType.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && parameters.length == 4 && parameters[0] == String.class
                    && parameters[1] == int.class && parameters[2] == RenderPipeline.class
                    && RenderType.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                state = findField(RenderType.entityCutoutNoCull(SHADER).getClass(), parameters[3]);
                return method;
            }
        }
        throw new NoSuchMethodException("RenderType.create(String, int, RenderPipeline, CompositeState)");
    }

    private static Field findField(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == type && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException("RenderType state");
    }
    *///? }

    //? if >=26 {
    /*// Vanilla's entity snippet, which its entity cutout pipeline starts from, with the
    // holographic shaders and what that pipeline adds to the snippet
    private static RenderPipeline buildPipeline() throws ReflectiveOperationException {
        //? if >=26.3 {
        /^String shader = "core/rendertype_holographic_layout";
        ^///? } else {
        String shader = "core/rendertype_holographic_330";
        //? }
        Field snippet = RenderPipelines.class.getDeclaredField("ENTITY_SNIPPET");
        snippet.setAccessible(true);
        RenderPipeline.Builder builder = RenderPipeline.builder((RenderPipeline.Snippet) snippet.get(null))
                .withLocation(ResourceLocations.of(BlockPopsMod.MOD_ID, "pipeline/holographic"))
                .withVertexShader(ResourceLocations.of(BlockPopsMod.MOD_ID, shader))
                .withFragmentShader(ResourceLocations.of(BlockPopsMod.MOD_ID, shader));
        //? if >=26.2 {
        /^builder.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1);
        ^///? } else {
        builder.withSampler("Sampler1");
        //? }
        builder.withCull(false);
        //? if >=26.3 {
        /^builder.withColorTargetState(com.mojang.renderpearl.api.pipeline.ColorTargetState.DEFAULT);
        ^///? }
        return builder.build();
    }

    // RenderType.create(String, RenderSetup)
    private static Method findFactory() throws NoSuchMethodException {
        for (Method method : RenderType.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && parameters.length == 2 && parameters[0] == String.class
                    && parameters[1] == net.minecraft.client.renderer.rendertype.RenderSetup.class) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("RenderType.create(String, RenderSetup)");
    }
    *///? }
}
