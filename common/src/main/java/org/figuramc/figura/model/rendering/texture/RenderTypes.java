package org.figuramc.figura.model.rendering.texture;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.TriState;
import org.figuramc.figura.lua.api.ClientAPI;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.utils.FiguraIdentifier;
import org.joml.Matrix4f;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BiFunction;
import java.util.function.Function;

public enum RenderTypes {
    NONE(null),

    CUTOUT(RenderType::entityCutoutNoCull),
    CUTOUT_CULL(RenderType::entityCutout),
    CUTOUT_EMISSIVE_SOLID(resourceLocation -> FiguraRenderType.CUTOUT_EMISSIVE_SOLID.apply(resourceLocation, true)),

    TRANSLUCENT(RenderType::entityTranslucent),
    TRANSLUCENT_CULL(RenderType::itemEntityTranslucentCull),

    EMISSIVE(RenderType::eyes),
    EMISSIVE_SOLID(resourceLocation -> RenderType.beaconBeam(resourceLocation, false)),
    EYES(RenderType::eyes),

    END_PORTAL(t -> RenderType.endPortal(), false),
    END_GATEWAY(t -> RenderType.endGateway(), false),
    TEXTURED_PORTAL(FiguraRenderType.TEXTURED_PORTAL),

    GLINT(t -> RenderType.entityGlint(), false, false),
    GLINT2(t -> RenderType.glint(), false, false),
    TEXTURED_GLINT(FiguraRenderType.TEXTURED_GLINT, true, false),

    LINES(t -> RenderType.lines(), false),
    LINES_STRIP(t -> RenderType.lineStrip(), false),
    SOLID(t -> FiguraRenderType.SOLID, false),

    BLURRY(FiguraRenderType.BLURRY);

    private final Function<ResourceLocation, RenderType> func;
    private final boolean texture, offset;

    RenderTypes(Function<ResourceLocation, RenderType> func) {
        this(func, true);
    }

    RenderTypes(Function<ResourceLocation, RenderType> func, boolean texture) {
        this(func, texture, true);
    }

    RenderTypes(Function<ResourceLocation, RenderType> func, boolean texture, boolean offset) {
        this.func = func;
        this.texture = texture;
        this.offset = offset;
    }

    public boolean isOffset() {
        return offset;
    }

    public RenderType get(ResourceLocation id) {
        if (!texture)
            return func.apply(id);

        return id == null || func == null ? null : func.apply(id);
    }

    /**
     * Returns the <b>front-render marker</b> layer (1-3, or 0 if the color is not a marker), for a shader pack
     * that treats these vertex colors specially.
     *
     * <p>The shader pack checks this vertex color in {@code gbuffers_entities.vsh} and compresses
     * {@code gl_Position.z} toward the near plane, so first-person props are drawn in front of the terrain.
     * Within a pair, the odd value keeps shading and the even value flattens normals; <b>both values of a pair
     * get the same depth.</b>
     *
     * <p>The test and its tolerance match the shader's {@code testColor} <b>exactly</b>.
     * A tolerance of 0.001 is below 1/255, so this is effectively an exact byte match.
     */
    public static int frontLayerOf(FiguraVec3 color) {
        if (color == null)
            return 0;
        if (Math.abs(color.x - 1d) > MARKER_TOLERANCE || Math.abs(color.y - 1d) > MARKER_TOLERANCE)
            return 0;
        for (int i = 0; i < MARKER_BLUES.length; i++)
            if (Math.abs(color.z - MARKER_BLUES[i] / 255d) <= MARKER_TOLERANCE)
                return i / 2 + 1;   // 251,250 -> 1, 249,248 -> 2, 247,246 -> 3
        return 0;
    }

    /**
     * Replaces the glint of parts painted with a front-render marker with a render type whose
     * <b>depth is pulled forward along with the part</b>. Returns {@code null} when this does not apply
     * (= keep the vanilla glint).
     *
     * <p><b>Problem</b>: all three glint types use {@code RenderPipelines.GLINT}, and that pipeline uses
     * {@code EQUAL_DEPTH_TEST}, meaning "draw only on pixels whose depth exactly equals what the base layer
     * wrote". The shader pulls <b>only the base layer's depth</b> forward (by {@code z*0.001}), so on
     * front-rendered parts the depths never match and the glint is discarded entirely. (Third person has no
     * marker, so it is unaffected.)
     *
     * <p><b>Solution</b>: tell the glint program the layer as well, so it pulls the depth the same way.
     * The channel is an <b>unused cell of the glint texture matrix</b> ({@code m32} = z translation):
     * {@link FiguraRenderType#frontGlintTexturing} stores the layer there, and the shader pack's
     * {@code gbuffers_armor_glint.vsh} reads it as {@code gl_TextureMatrix[0][3][2]}.
     *
     * <p><b>Why the texture matrix and not the vertex color (failed attempt 2, 2026-09-14)</b>:
     * raising the vertex format to {@code POSITION_TEX_COLOR} to pass the layer through {@code gl_Color}
     * failed. Iris hardcodes the vertex format of {@code ShaderKey.GLINT} as <b>{@code POSITION_TEX}</b>, so
     * with a different format {@code ShaderKey.findBestMatch} falls through to <b>its last step, which only
     * matches the program id</b> (the "Found *decent* program match" warning). The program is then set up for
     * POSITION_TEX, <b>the Color attribute is not bound</b>, and {@code gl_Color} is always white, which kills
     * the branch. So <b>the pipeline stays the vanilla {@code RenderPipelines.GLINT}</b> (an exact match that
     * Iris recognizes by instance). Only the texture matrix differs.
     *
     * <p><b>Failed attempt 1 (2026-09-14, in game)</b>: do not turn the depth test off with
     * {@code NO_DEPTH_TEST}. (1) The glint has culling disabled, so <b>even back faces were blended in
     * additively</b> and covered the base layer; (2) <b>areas that should have been hidden were not hidden</b>
     * by other prop parts that were pulled forward too. Ignoring depth removes occlusion entirely (enabling
     * culling alone does not fix (2)).
     *
     * <p>Note: the replacement happens only while a shader pack is actually enabled. In vanilla nothing pulls
     * the depth forward, so the vanilla glint is already correct.
     */
    public static RenderType frontLayerGlint(RenderTypes types, FiguraVec3 color, ResourceLocation id) {
        if (types != GLINT && types != GLINT2 && types != TEXTURED_GLINT)
            return null;
        int layer = frontLayerOf(color);
        if (layer == 0 || !ClientAPI.hasShaderPack())
            return null;

        return switch (types) {
            case GLINT -> FiguraRenderType.ENTITY_GLINT_FRONT[layer - 1];
            case GLINT2 -> FiguraRenderType.GLINT_FRONT[layer - 1];
            // Without a texture, draw nothing, exactly like the vanilla path
            case TEXTURED_GLINT -> id == null ? null
                    : FiguraRenderType.TEXTURED_GLINT_FRONT.apply(id, layer);
            default -> null;
        };
    }

    /**
     * Blue channel values of the front-render markers, in pairs for layers 1-3
     * (odd = keep shading, even = flatten normals).
     */
    private static final int[] MARKER_BLUES = {251, 250, 249, 248, 247, 246};
    /** Must equal the tolerance of the shader's {@code testColor}. */
    private static final double MARKER_TOLERANCE = 0.001;

    private abstract static class FiguraRenderType extends RenderType {

        public FiguraRenderType(String name, int bufferSize, boolean hasCrumbling, boolean translucent, Runnable startAction, Runnable endAction) {
            super(name, bufferSize, hasCrumbling, translucent, startAction, endAction);
        }

        public static final RenderType SOLID = create(
                "figura_solid",
                256,
                FiguraRenderPipelines.FIGURA_SOLID,
                RenderType.CompositeState.builder()
                        .setLineState(new LineStateShard(OptionalDouble.empty()))
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .setOutputState(ITEM_ENTITY_TARGET)
                        .createCompositeState(false)
        );

        private static final BiFunction<ResourceLocation, Boolean, RenderType> CUTOUT_EMISSIVE_SOLID = Util.memoize(
                (texture, affectsOutline) ->
                        create("figura_cutout_emissive_solid", 256, true, true, RenderPipelines.BEACON_BEAM_TRANSLUCENT,
                                CompositeState.builder()
                                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false))
                                        .setOverlayState(OVERLAY)
                                        .createCompositeState(affectsOutline)));


        public static final Function<ResourceLocation, RenderType> TEXTURED_PORTAL = Util.memoize(
                texture -> create(
                        "figura_textured_portal",
                        256,
                        false,
                        false,
                        RenderPipelines.END_GATEWAY,
                        CompositeState.builder()
                                .setTextureState(
                                        MultiTextureStateShard.builder()
                                                .add(texture, false)
                                                .add(texture, false)
                                                .build()
                                )
                                .createCompositeState(false)
                )
        );

        public static final Function<ResourceLocation, RenderType> BLURRY = Util.memoize(
                texture -> create(
                        "figura_blurry",
                        256,
                        true,
                        true,
                        RenderPipelines.ENTITY_TRANSLUCENT,
                        CompositeState.builder()
                                .setTextureState(new BlurryTextureStateShard(texture, TriState.TRUE, false))
                                .setLightmapState(LIGHTMAP)
                                .setOverlayState(OVERLAY)
                                .createCompositeState(true)
                )
        );

        public static final Function<ResourceLocation, RenderType> TEXTURED_GLINT = Util.memoize(
                texture -> create(
                        "figura_textured_glint_direct",
                        256,
                        false,
                        false,
                        RenderPipelines.GLINT,
                        RenderType.CompositeState.builder()
                                .setTextureState(new TextureStateShard(texture, false))
                                .setTexturingState(ENTITY_GLINT_TEXTURING)
                                .createCompositeState(false)
                )
        );
    
        // Note: the types below differ from GLINT/GLINT2/TEXTURED_GLINT above **only in the texture matrix.**
        //   Pipeline, texture, buffer size and outline flag stay as in vanilla. In particular, the pipeline
        //   must remain the vanilla {@code RenderPipelines.GLINT} instance for Iris to make an **exact match**
        //   to gbuffers_armor_glint (see the frontLayerGlint comment for why).

        /**
         * A texturing state that embeds the front-render layer in an <b>unused cell</b> of the glint
         * texture matrix.
         *
         * <p>The vanilla glint shader only uses {@code (TextureMat * vec4(UV0, 0, 1)).xy}, so
         * <b>row 2 (the z output) is never used</b>: a number in {@code m32} (z translation) does not affect
         * the texture coordinates. {@code setupGlintTexturing} only applies {@code rotateZ} and {@code scale}
         * after {@code translation(-f,g,0)}, so that cell is always 0.
         *
         * <p>Warning: run the vanilla state first and then patch its result. Copying the scroll speed and
         * angle by hand would make the glint look different from the vanilla one.
         */
        private static TexturingStateShard frontGlintTexturing(TexturingStateShard base, String name, int layer) {
            return new TexturingStateShard(name + "_front" + layer, () -> {
                base.setupRenderState();
                Matrix4f mat = new Matrix4f(RenderSystem.getTextureMatrix());
                mat.m32((float) layer);
                RenderSystem.setTextureMatrix(mat);
            }, base::clearRenderState);
        }

        private static RenderType frontGlint(String name, TexturingStateShard base, ResourceLocation texture, int layer) {
            return create(
                    name + "_front" + layer,
                    1536,
                    RenderPipelines.GLINT,
                    RenderType.CompositeState.builder()
                            .setTextureState(new TextureStateShard(texture, false))
                            .setTexturingState(frontGlintTexturing(base, name, layer))
                            .createCompositeState(false)
            );
        }

        /** Layers 1-3, one per marker pair. Index 0 is layer 1. */
        public static final RenderType[] GLINT_FRONT = {
                frontGlint("figura_glint", GLINT_TEXTURING, ItemRenderer.ENCHANTED_GLINT_ITEM, 1),
                frontGlint("figura_glint", GLINT_TEXTURING, ItemRenderer.ENCHANTED_GLINT_ITEM, 2),
                frontGlint("figura_glint", GLINT_TEXTURING, ItemRenderer.ENCHANTED_GLINT_ITEM, 3),
        };

        public static final RenderType[] ENTITY_GLINT_FRONT = {
                frontGlint("figura_entity_glint", ENTITY_GLINT_TEXTURING, ItemRenderer.ENCHANTED_GLINT_ITEM, 1),
                frontGlint("figura_entity_glint", ENTITY_GLINT_TEXTURING, ItemRenderer.ENCHANTED_GLINT_ITEM, 2),
                frontGlint("figura_entity_glint", ENTITY_GLINT_TEXTURING, ItemRenderer.ENCHANTED_GLINT_ITEM, 3),
        };

        /** Variant that uses the part's own texture. Memoized by (texture, layer). */
        public static final BiFunction<ResourceLocation, Integer, RenderType> TEXTURED_GLINT_FRONT =
                Util.memoize((texture, layer) -> create(
                        "figura_textured_glint_front" + layer,
                        256,
                        false,
                        false,
                        RenderPipelines.GLINT,
                        RenderType.CompositeState.builder()
                                .setTextureState(new TextureStateShard(texture, false))
                                .setTexturingState(frontGlintTexturing(
                                        ENTITY_GLINT_TEXTURING, "figura_textured_glint", layer))
                                .createCompositeState(false)
                ));
    }

    public static class FiguraRenderPipelines extends RenderPipelines {
        protected static RenderPipeline.Snippet FIGURA_SOLID_SNIPPET = RenderPipeline.builder(MATRICES_FOG_SNIPPET, GLOBALS_SNIPPET).withVertexShader("core/rendertype_lines").withFragmentShader("core/rendertype_lines").withColorWrite(true).withDepthWrite(true).withBlend(BlendFunction.TRANSLUCENT).withCull(false).withVertexFormat(DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.QUADS).buildSnippet();

        public static RenderPipeline FIGURA_SOLID = register(RenderPipeline.builder(FIGURA_SOLID_SNIPPET).withLocation(new FiguraIdentifier("pipeline/solid")).build());

    }

    public static class BlurryTextureStateShard extends RenderStateShard.EmptyTextureStateShard {
        private final Optional<ResourceLocation> texture;
        private final boolean mipmap;

        public BlurryTextureStateShard(ResourceLocation resourceLocation, TriState blurry, boolean bl) {
            super(() -> {
                TextureManager textureManager = Minecraft.getInstance().getTextureManager();
                AbstractTexture abstractTexture = textureManager.getTexture(resourceLocation);
                abstractTexture.setUseMipmaps(bl);
                abstractTexture.setFilter(blurry.toBoolean(true), bl);
                RenderSystem.setShaderTexture(0, abstractTexture.getTextureView());
            }, () -> {
            });
            this.texture = Optional.of(resourceLocation);
            this.mipmap = bl;
        }

        protected Optional<ResourceLocation> cutoutTexture() {
            return this.texture;
        }
    }
}