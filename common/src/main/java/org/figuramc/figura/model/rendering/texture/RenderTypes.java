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
     * 사내 BSL 포크의 <b>앞렌더 마커</b> 순위를 돌려준다 (1~3, 마커가 아니면 0).
     *
     * <p>셰이더팩이 {@code gbuffers_entities.vsh} 에서 이 정점색을 보고
     * {@code gl_Position.z} 를 근평면 쪽으로 압축해 1인칭 프롭을 지형 앞에 그린다.
     * 쌍에서 홀수는 셰이딩 유지, 짝수는 노말 평탄화 — <b>깊이는 쌍이 같다.</b>
     * (하네스 {@code knowledge/misc/shaderpack_system.md} §3.3 이 정본)
     *
     * <p>판정식·허용오차를 셰이더의 {@code testColor} 와 <b>똑같이</b> 맞춘다.
     * 허용오차 0.001 은 1/255 보다 작으므로 사실상 바이트 단위 일치다.
     */
    public static int frontLayerOf(FiguraVec3 color) {
        if (color == null)
            return 0;
        if (Math.abs(color.x - 1d) > MARKER_TOLERANCE || Math.abs(color.y - 1d) > MARKER_TOLERANCE)
            return 0;
        for (int i = 0; i < MARKER_BLUES.length; i++)
            if (Math.abs(color.z - MARKER_BLUES[i] / 255d) <= MARKER_TOLERANCE)
                return i / 2 + 1;   // 251,250 → 1 · 249,248 → 2 · 247,246 → 3
        return 0;
    }

    /**
     * ★★ 앞렌더 마커가 칠해진 파츠의 글린트를 <b>깊이가 같이 당겨지는</b> 렌더타입으로 바꿈다.
     * 해당 없으면 {@code null} (= 바닐라 글린트 그대로).
     *
     * <p><b>문제</b> — 글린트 3종은 전부 {@code RenderPipelines.GLINT} 를 쓰고 그 파이프라인은
     * {@code EQUAL_DEPTH_TEST} 다. "본체가 써 둔 깊이와 정확히 같은 픽셀에만 그린다" 는 뜻이라,
     * 셰이더가 <b>본체의 깊이만</b> {@code z*0.001} 로 당견 앞렌더 파츠에서는 영원히 불일치해
     * 글린트가 통째로 discard 된다. (3인칭은 마커가 없어 멀줦하다)
     *
     * <p><b>해법</b> — 글린트 프로그램에도 순위를 알려 같은 식으로 당기게 한다.
     * 통로는 <b>글린트 텍스처 행렬의 빈 칸</b>({@code m32} = z 이동)다 —
     * {@link FiguraRenderType#frontGlintTexturing} 이 거기에 순위를 심어 보내고,
     * 셰이더팩 {@code gbuffers_armor_glint.vsh} 가 {@code gl_TextureMatrix[0][3][2]} 로 읽는다.
     *
     * <p>★★ <b>왜 정점색이 아니라 텍스처 행렬인가 (2026-09-14 실패 기록 2)</b> —
     * 정점 포맷을 {@code POSITION_TEX_COLOR} 로 올려 {@code gl_Color} 로 보내려 했다가 실패했다.
     * Iris 는 {@code ShaderKey.GLINT} 의 정점 포맷을 <b>{@code POSITION_TEX}</b> 로 박아 두고 있어,
     * 포맷이 다르면 {@code ShaderKey.findBestMatch} 가 <b>프로그램 id 만 맞추는 마지막 단계</b>
     * ("Found *decent* program match" 경고)로 떨어진다. 그러면 프로그램은 POSITION_TEX 기준으로
     * 잡혀 <b>Color 속성이 바인딩되지 않고</b> {@code gl_Color} 가 항상 흰색이라 분기가 죽는다.
     * 그래서 <b>파이프라인은 바닐라 {@code RenderPipelines.GLINT} 그대로 둔다</b>
     * (Iris 가 인스턴스로 알아보는 완전 일치). 달라지는 것은 텍스처 행렬뿐이다.
     *
     * <p>★★ <b>실패 기록 1 (2026-09-14, 인게임)</b> — 깊이 테스트를 {@code NO_DEPTH_TEST} 로
     * 끔 방식은 사용하지 마라. ① 글린트는 컴링이 꺼져 있어 <b>뒷면까지 가산</b>돼 본체를 덮었고,
     * ② 같이 당겨진 다른 프롭 파츠에 <b>가려야 할 부분이 안 가려졌다.</b>
     * 깊이를 무시하는 순간 가림 관계가 통째로 사라진다 (컴링만 켜서는 ② 이 안 잡힌다).
     *
     * <p>⚠ 셰이더팩이 실제로 켜져 있을 때만 바꿈다. 바닐라에서는 깊이를 당기는 주체가
     * 없어 바닐라 글린트가 그대로 정답이다.
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
            // 텍스처가 없으면 바닐라 경로와 똑같이 아무것도 그리지 않는다
            case TEXTURED_GLINT -> id == null ? null
                    : FiguraRenderType.TEXTURED_GLINT_FRONT.apply(id, layer);
            default -> null;
        };
    }

    /** 앞렌더 마커의 파랑 채널. 쌍으로 1~3순위 (홀수=셰이딩 유지, 짝수=평탄화). */
    private static final int[] MARKER_BLUES = {251, 250, 249, 248, 247, 246};
    /** 셰이더 {@code testColor} 의 허용오차와 동일해야 한다. */
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
    
        // ★ 아래는 위 GLINT/GLINT2/TEXTURED_GLINT 와 **텍스처 행렬만 다르다.**
        //   파이프라인·텍스쳐·버퍼 크기·아웃라인 여부는 바닐라 그대로다 —
        //   특히 파이프라인을 바닐라 {@code RenderPipelines.GLINT} 인스턴스로 유지해야
        //   Iris 가 gbuffers_armor_glint 로 **완전 일치** 매칭한다 (이유는 frontLayerGlint 주석).

        /**
         * 글린트 텍스처 행렬의 <b>안 쓰는 칸</b>에 앞렌더 순위를 심어 보내는 셰이딩 상태.
         *
         * <p>바닐라 글린트 셀이더는 {@code (TextureMat * vec4(UV0, 0, 1)).xy} 만 쓴다.
         * 즉 <b>행 2(z 출력)는 전혀 안 쓴다</b> — {@code m32}(z 이동)에 숫자를 넣어도
         * 텍스처 좌표에 영향이 없다. {@code setupGlintTexturing} 은 {@code translation(-f,g,0)} 뒤에
         * {@code rotateZ}·{@code scale} 만 하므로 그 칸은 항상 0 이다.
         *
         * <p>⚠ 바닐라 상태를 먼저 돌리고 그 결과를 고친다 — 스크롤 속도·각도를 직접 베끼면
         * 바닐라 글린트와 모양이 달라진다.
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

        /** 쌍으로 1~3순위. 인덱스 0 이 1순위다. */
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

        /** 파츠 자신의 텍스쳐를 쓰는 판. (텍스쳐, 순위) 로 메모이즈한다. */
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