package org.figuramc.figura.model.rendering.texture;

import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.figuramc.figura.utils.FiguraIdentifier;

import java.util.function.BiFunction;
import java.util.function.Function;

public enum FiguraRenderTypes {
    NONE(null),

    CUTOUT(RenderTypes::entityCutout),
    CUTOUT_CULL(RenderTypes::entityCutoutCull),
    CUTOUT_EMISSIVE_SOLID(resourceLocation -> FiguraRenderType.CUTOUT_EMISSIVE_SOLID.apply(resourceLocation, true)),

    TRANSLUCENT(RenderTypes::entityTranslucent),
    TRANSLUCENT_CULL(RenderTypes::entityTranslucentCull),

    EMISSIVE(RenderTypes::eyes),
    EMISSIVE_SOLID(resourceLocation -> RenderTypes.beaconBeam(resourceLocation, false)),
    EYES(RenderTypes::eyes),

    END_PORTAL(t -> RenderTypes.endPortal(), false),
    END_GATEWAY(t -> RenderTypes.endGateway(), false),
    TEXTURED_PORTAL(FiguraRenderType.TEXTURED_PORTAL),

    GLINT(t -> FiguraRenderType.SHIELD_GLINT, false, false),
    GLINT2(t -> FiguraRenderType.GLINT, false, false),
    TEXTURED_GLINT(FiguraRenderType.TEXTURED_GLINT, true, false),

    LINES(t -> RenderTypes.lines(), false),
    LINES_STRIP(t -> RenderTypes.lines(), false),
    SOLID(t -> FiguraRenderType.SOLID, false),

    BLURRY(FiguraRenderType.BLURRY);

    private final Function<Identifier, RenderType> func;
    private final boolean texture, offset;

    FiguraRenderTypes(Function<Identifier, RenderType> func) {
        this(func, true);
    }

    FiguraRenderTypes(Function<Identifier, RenderType> func, boolean texture) {
        this(func, texture, true);
    }

    FiguraRenderTypes(Function<Identifier, RenderType> func, boolean texture, boolean offset) {
        this.func = func;
        this.texture = texture;
        this.offset = offset;
    }

    public boolean isOffset() {
        return offset;
    }

    public RenderType get(Identifier id) {
        if (!texture)
            return func.apply(id);

        return id == null || func == null ? null : func.apply(id);
    }

    private static class FiguraRenderType {
        private static final BiFunction<Identifier, Boolean, RenderType> CUTOUT_EMISSIVE_SOLID = Util.memoize(
                (texture, affectsOutline) ->
                        FiguraRenderTypeFactory.create("figura_cutout_emissive_solid",
                                RenderSetup.builder(RenderPipelines.BEACON_BEAM_TRANSLUCENT)
                                        // OIT 짝 — 바닐라 반투명 beacon_beam 과 같다. 없으면 OIT 를 켤 때 FiguraSubmitUtils 가 solid 로 보내 섞는 순서를 잃는다
                                        .setOitPipelines(RenderPipelines.OIT_BEACON_BEAM)
                                        .withTexture("Sampler0", texture)
                                        .affectsCrumbling()
                                        .sortOnUpload()
                                        .useOverlay()
                                        .setOutline(affectsOutline ? RenderSetup.OutlineProperty.AFFECTS_OUTLINE : RenderSetup.OutlineProperty.NONE)
                                        .createRenderSetup()
                        )
        );


        public static final Function<Identifier, RenderType> TEXTURED_PORTAL = Util.memoize(
                texture -> FiguraRenderTypeFactory.create(
                        "figura_textured_portal",
                        RenderSetup.builder(RenderPipelines.END_GATEWAY)
                                .withTexture("Sampler0", texture)
                                .withTexture("Sampler1", texture)
                                .setOutline(RenderSetup.OutlineProperty.NONE)
                                .createRenderSetup()
                )
        );

        public static final Function<Identifier, RenderType> BLURRY = Util.memoize(
                texture -> FiguraRenderTypeFactory.create(
                        "figura_blurry",
                        RenderSetup.builder(RenderPipelines.ENTITY_TRANSLUCENT)
                                // OIT 짝 — 바닐라 entity_translucent 와 같다(다른 것은 흐린 샘플러 하나)
                                .setOitPipelines(RenderPipelines.OIT_ENTITY)
                                .affectsCrumbling()
                                .sortOnUpload()
                                .useLightmap()
                                .useOverlay()
                                .withTexture("Sampler0", texture, () -> {
                                    GpuDevice device = RenderSystem.getDevice();
                                    AbstractTexture abstractTexture = Minecraft.getInstance().getTextureManager().getTexture(texture);
                                    // basically copy it the sampler the texture set to linear to blur it
                                    return device.createSampler(abstractTexture.getSampler().getAddressModeU(), abstractTexture.getSampler().getAddressModeV(),
                                            FilterMode.LINEAR, FilterMode.LINEAR, abstractTexture.getSampler().getMaxAnisotropy(), abstractTexture.getSampler().getMaxLod());
                                })
                                .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
                                .createRenderSetup()
                )
        );

        // 1.21.8 GLINT2 = RenderType.glint() — 26.3 에는 같은 타입이 없다.
        // ⚠ trimmedArmorGlint 로 대신하지 마라 — 깊이를 밀어(VIEW_OFFSET_Z_LAYERING) 반짝임의 «깊이 같음» 검사에서 다 떨어진다
        public static final RenderType GLINT = FiguraRenderTypeFactory.create(
                "figura_glint",
                RenderSetup.builder(RenderPipelines.GLINT)
                        .setOitPipelines(FiguraRenderPipelines.GLINT_OIT)
                        .withTexture("Sampler0", ItemFeatureRenderer.ENCHANTED_GLINT_ITEM)
                        .setTextureTransform(TextureTransform.GLINT_TEXTURING)
                        .withForcedSolidModelPhase()
                        .createRenderSetup()
        );

        // 1.21.8 SOLID — 텍스처 없이 꼭짓점 색 하나로 채운다(entitySolid 는 텍스처를 입혀 모습이 다르다)
        public static final RenderType SOLID = FiguraRenderTypeFactory.create(
                "figura_solid",
                RenderSetup.builder(FiguraRenderPipelines.FIGURA_SOLID_COLOR)
                        .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                        .createRenderSetup()
        );

        // 바닐라 patternedShieldGlint 사본 — 바닐라 렌더 타입에는 OIT 짝을 못 붙인다
        public static final RenderType SHIELD_GLINT = FiguraRenderTypeFactory.create(
                "figura_shield_glint",
                RenderSetup.builder(RenderPipelines.GLINT)
                        .setOitPipelines(FiguraRenderPipelines.GLINT_OIT)
                        .withTexture("Sampler0", ItemFeatureRenderer.ENCHANTED_GLINT_ITEM)
                        .setTextureTransform(TextureTransform.ENTITY_GLINT_TEXTURING)
                        .withForcedSolidModelPhase()
                        .createRenderSetup()
        );

        // 반짝임 셋은 GLINT_OIT 짝을 쓴다 — 짝이 없으면 OIT 를 켤 때 FiguraSubmitUtils 가 solid 로 보내,
        // «깊이 같음» 으로 찾을 반투명 바탕 깊이가 없어 안 보였다(반투명 바탕은 OIT 단계라 메인 깊이를 안 쓴다)
        public static final Function<Identifier, RenderType> TEXTURED_GLINT = Util.memoize(
                texture -> FiguraRenderTypeFactory.create(
                        "figura_textured_glint_direct",
                        RenderSetup.builder(RenderPipelines.GLINT)
                                .setOitPipelines(FiguraRenderPipelines.GLINT_OIT)
                                .withTexture("Sampler0", texture)
                                .setTextureTransform(TextureTransform.ENTITY_GLINT_TEXTURING)
                                .createRenderSetup()
                )
        );
    }

    public static class FiguraRenderPipelines {
        public static final RenderPipeline FIGURA_SOLID = buildFiguraSolid();

        // SOLID 렌더 타입 — 바닐라 debug 채우기 조각(position_color · 반투명 섞기 · 사각형)에 깊이 쓰기를 켜고 컬링을 끈다(1.21.8 SOLID 와 같은 꼴).
        // Iris 프로그램은 IrisCompat 에서 BASIC 으로 정한다(FIGURA_SOLID 의 ENTITIES 는 엔티티 형식용)
        public static final RenderPipeline FIGURA_SOLID_COLOR = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(FiguraIdentifier.of("pipeline/solid_color"))
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withCull(false)
                .build();

        // 반짝임 셋의 OIT 짝 — 바닐라 GLINT 파이프라인과 같은 조각 · 묶음 · 꼭짓점(POSITION_TEX)에 «빛만 더하는 표면»(OIT_ADDITIVE · 바닐라 OIT_ENERGY_SWIRL 과 같은 꼴).
        // 깊이 · 섞기는 넣지 않는다 — OIT 단계 조각이 정한다. 조각 셰이더만 Figura 것(assets/figura/shaders/core/glint_oit.fsh)
        // ⚠ OitPipelineSet 은 꼭짓점 꼴을 재지 않는다 — 렌더 타입의 주 파이프라인(GLINT)과 꼴을 맞춰 둔다
        public static final OitPipelineSet GLINT_OIT = OitPipelineSet.builder("figura_glint",
                RenderPipeline.builder()
                        .withBindGroupLayout(BindGroupLayouts.GLOBALS)  // = 바닐라 GLOBALS_SNIPPET(비공개)
                        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                        .withBindGroupLayout(BindGroupLayouts.FOG)
                        .withVertexShader("core/glint")
                        .withFragmentShader(FiguraIdentifier.of("core/glint_oit"))
                        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                        .withCull(false)
                        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                        .withPrimitiveTopology(PrimitiveTopology.QUADS)
                        .withShaderDefine("OIT_ADDITIVE")
        ).build();

        private static RenderPipeline buildFiguraSolid() {
            RenderPipeline base = RenderPipelines.ENTITY_SOLID;

            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(FiguraIdentifier.of("pipeline/solid"))
                    .withPolygonMode(base.getPolygonMode())
                    .withCull(base.isCull())
                    .withPushConstantSize(base.pushConstantSize())
                    .withPrimitiveTopology(base.getPrimitiveTopology());

            if (base.getDepthStencilState() != null)
                builder.withDepthStencilState(base.getDepthStencilState());

            for (var shader : base.getShaders().entrySet()) {
                if (shader.getKey().equals(ShaderType.FRAGMENT))
                    builder = builder.withFragmentShader(shader.getValue());

                if (shader.getKey().equals(ShaderType.VERTEX))
                    builder = builder.withVertexShader(shader.getValue());
            }

            var j = 0;

            for (var color : base.getColorTargetStates()) {
                builder = builder.withColorTargetState(j, color);
                j++;
            }

            for (var define : base.getShaderDefines().values().entrySet()) {
                builder = builder.withShaderDefine(define.getKey(), Integer.parseInt(define.getValue()));
            }

            for (var layoutGroup : base.getBindGroupLayouts()) {
                builder = builder.withBindGroupLayout(layoutGroup);
            }

            var bindings = base.getVertexFormatBindings();

            for (int i = 0; i < bindings.size(); i++) {
                var vertexFormat = bindings.get(i);

                if (vertexFormat != null)
                    builder.withVertexBinding(i, vertexFormat);
            }

            return builder.build();
        }
    }
}