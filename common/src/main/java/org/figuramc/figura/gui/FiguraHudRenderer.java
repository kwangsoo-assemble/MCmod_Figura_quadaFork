package org.figuramc.figura.gui;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.utils.RenderUtils;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.OptionalDouble;

public class FiguraHudRenderer extends PictureInPictureRenderer<FiguraHudRenderState> {

    private final Projection hudProjection = new Projection();
    private final ProjectionMatrixBuffer hudProjectionMatrixBuffer = new ProjectionMatrixBuffer("Hud-PIP");
    private final SubmitNodeStorage submitNodeStorage = new SubmitNodeStorage();

    private GpuTexture texture, depthTexture;
    private GpuTextureView textureView, depthTextureView;
    private GpuSampler sampler;

    @Override
    public @NonNull Class<FiguraHudRenderState> getRenderStateClass() {
        return FiguraHudRenderState.class;
    }

    @Override
    protected void renderToTexture(FiguraHudRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector) {
        if (state.avatar() == null)
            return;

        // ⚠ 16 배 · z 뒤집기는 Avatar.hudRender 가 한다 — 여기서 또 곱하면 HUD 가 256 배로 화면 밖에 그려진다(1.21.8 도 hudRender 한 번뿐)
        state.avatar().hudRender(poseStack, submitNodeCollector, state.entity(), state.tickDelta());
    }

    @Override
    public void prepare(FiguraHudRenderState state, GuiRenderState guiRenderState, FeatureRenderDispatcher featureRenderDispatcher, int guiScale) {
        if (state.avatar() == null) {
            super.prepare(state, guiRenderState, featureRenderDispatcher, guiScale);
            return;
        }

        int width = (state.x1() - state.x0()) * guiScale;
        int height = (state.y1() - state.y0()) * guiScale;

        prepareTexturesAndProjection(width, height);

        // HUD 원점은 화면 왼쪽 위 · 단위는 GUI 픽셀(1.21.8 의 guiGraphics.pose() 그대로) — 텍스처가 물리 픽셀이라 GUI 배율을 곱한다
        PoseStack poseStack = new PoseStack();
        poseStack.scale(guiScale, guiScale, 1.0F);

        this.renderToTexture(state, poseStack, this.submitNodeStorage);

        // HUD 저장소는 OIT 를 안 쓴다 — 준비하는 동안 반투명 정렬을 살린다(RenderTypeFeatureRendererGroupMixin)
        RenderUtils.preparingNonOitPass = true;
        try (
                FeatureRenderDispatcher.PreparedFrame frame = featureRenderDispatcher.prepareFrame(this.submitNodeStorage);
                RenderPass pass = RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(() -> "FiguraHud", textureView, Optional.empty(), depthTextureView, OptionalDouble.empty())
        ) {
            RenderSystem.bindDefaultUniforms(pass);
            FeatureRenderDispatcher.renderAllFeatures(pass, frame);
        } finally {
            RenderUtils.preparingNonOitPass = false;
        }

        renderPivotItems(state.avatar(), featureRenderDispatcher);

        this.blitTexture(state, guiRenderState);
    }

    // HUD 손 아이템 피벗 — 파츠 뒤에 같은 텍스처 · 깊이로 조명별 한 번씩(인벤토리처럼 블록 = ITEMS_3D · 평면 = ITEMS_FLAT)
    // ⚠ 한 번 그리기 안에서는 조명이 하나다(그릴 때 걸린 값) — 아이템을 파츠와 같은 제출에 섞지 마라
    private void renderPivotItems(Avatar avatar, FeatureRenderDispatcher featureRenderDispatcher) {
        if (avatar.hudPivotItems.isEmpty())
            return;

        for (boolean blockLight : new boolean[] {true, false}) {
            boolean any = false;
            for (Avatar.HudPivotItem pivotItem : avatar.hudPivotItems) {
                if (pivotItem.item().usesBlockLight() != blockLight)
                    continue;
                PoseStack poseStack = new PoseStack();
                poseStack.last().set(pivotItem.pose());
                pivotItem.item().submit(poseStack, this.submitNodeStorage, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                any = true;
            }
            if (!any)
                continue;

            Minecraft.getInstance().gameRenderer.lighting().setupFor(blockLight ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
            RenderUtils.preparingNonOitPass = true;
            try (
                    FeatureRenderDispatcher.PreparedFrame frame = featureRenderDispatcher.prepareFrame(this.submitNodeStorage);
                    RenderPass pass = RenderSystem.getDevice()
                            .createCommandEncoder()
                            .createRenderPass(() -> "FiguraHudItems", textureView, Optional.empty(), depthTextureView, OptionalDouble.empty())
            ) {
                RenderSystem.bindDefaultUniforms(pass);
                FeatureRenderDispatcher.renderAllFeatures(pass, frame);
            } finally {
                RenderUtils.preparingNonOitPass = false;
            }
        }

        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
        avatar.hudPivotItems.clear();
    }

    private void prepareTexturesAndProjection(int width, int height) {
        boolean needsRecreate = texture == null || texture.getWidth(0) != width || texture.getHeight(0) != height;
        if (needsRecreate) {
            if (texture != null) {
                texture.close(); textureView.close();
                depthTexture.close(); depthTextureView.close();
                if (sampler != null) sampler.close();
            }
            GpuDevice gpuDevice = RenderSystem.getDevice();
            texture = gpuDevice.createTexture(() -> "UI figura-hud texture", 13, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            textureView = gpuDevice.createTextureView(texture);
            depthTexture = gpuDevice.createTexture(() -> "UI figura-hud depth texture", 9, GpuFormat.D32_FLOAT, width, height, 1, 1);
            depthTextureView = gpuDevice.createTextureView(depthTexture);
            sampler = gpuDevice.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        }

        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(texture, GuiRenderer.CLEAR_COLOR, depthTexture, 0.0);
        // ⚠ 투영은 바닐라 Projection 으로 만든다(바닐라 그림 속 그림과 같은 값) — 기기의 깊이 범위(isZZeroToOne)를 따른다.
        //   JOML setOrtho 를 날것으로 쓰면 깊이가 -1 ~ 1 로 나와 0 ~ 1 기기에서 z 가 0 보다 뒤인 것(글 배경 · 그림자 · 파츠 뒤쪽)이 잘린다
        hudProjection.setupOrtho(-1000.0F, 1000.0F, width, height, true);
        RenderSystem.setProjectionMatrix(hudProjectionMatrixBuffer.getBuffer(hudProjection), ProjectionType.ORTHOGRAPHIC);
    }

    @Override
    protected void blitTexture(FiguraHudRenderState state, GuiRenderState guiRenderState) {
        guiRenderState.addBlitToCurrentLayer(
                new BlitRenderState(
                        RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                        TextureSetup.singleTexture(textureView, sampler),
                        state.pose(),
                        state.x0(), state.y0(), state.x1(), state.y1(),
                        0.0F, 1.0F, 1.0F, 0.0F,
                        -1,
                        state.scissorArea(),
                        null
                )
        );
    }

    @Override
    protected String getTextureLabel() {
        return "figura-hud";
    }

    @Override
    public void close() {
        super.close();
        if (texture != null) {
            texture.close(); textureView.close();
            depthTexture.close(); depthTextureView.close();
        }
        if (sampler != null) sampler.close();
        hudProjectionMatrixBuffer.close();
    }
}