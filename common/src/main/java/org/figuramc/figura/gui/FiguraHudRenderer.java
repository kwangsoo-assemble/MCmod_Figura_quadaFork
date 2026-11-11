package org.figuramc.figura.gui;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import org.figuramc.figura.model.HudLevel;
import org.joml.Matrix4fStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * HUD 부모 파츠 — 층(HudLevel)마다 따로 텍스처에 그려 GUI 요소 하나로 붙인다(26.3 판 FiguraHudRenderer 를 1.21.8 로)
 * 하네스 `projects/mc_repositories/design/HUD_GUI_층/plan.md` D7 · D8 · D13
 */
public class FiguraHudRenderer extends PictureInPictureRenderer<FiguraHudRenderState> {

    // ⚠ 층마다 텍스처를 따로 둔다 — 바닐라 그림 속 그림은 렌더러 하나에 텍스처 하나라, 한 프레임에 두 층을 같은 텍스처에 그리면
    //   GUI 를 실제로 그리기 전에 앞 층을 덮어써 두 자리 모두 뒤 층 그림이 나온다
    private final Map<HudLevel, TextureEntry> textures = new EnumMap<>(HudLevel.class);
    // 파츠 z −10000 ~ +10000(GUI 픽셀)이 보이는 창 — 옛 1.21.8 HUD 창(−10000 ~ 0)을 다 품는다
    private final CachedOrthoProjectionMatrixBuffer hudProjectionMatrixBuffer = new CachedOrthoProjectionMatrixBuffer(
            "Figura HUD PIP", -10000.0F, 10000.0F, true
    );

    public FiguraHudRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<FiguraHudRenderState> getRenderStateClass() {
        return FiguraHudRenderState.class;
    }

    @Override
    protected void renderToTexture(FiguraHudRenderState state, PoseStack poseStack) {
        state.avatar().hudRender(poseStack, this.bufferSource, state.entity(), state.tickDelta(), state.level());
    }

    @Override
    public void prepare(FiguraHudRenderState state, GuiRenderState guiRenderState, int guiScale) {
        if (state.avatar() == null)
            return;

        int width = (state.x1() - state.x0()) * guiScale;
        int height = (state.y1() - state.y0()) * guiScale;
        if (width <= 0 || height <= 0)
            return;

        TextureEntry entry = prepareTexture(state.level(), width, height);
        RenderSystem.setProjectionMatrix(this.hudProjectionMatrixBuffer.getBuffer(width, height), ProjectionType.ORTHOGRAPHIC);

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();

        RenderSystem.outputColorTextureOverride = entry.textureView;
        RenderSystem.outputDepthTextureOverride = entry.depthTextureView;
        try {
            // 원점 = 화면 왼쪽 위 · 단위 = GUI 픽셀(옛 HUD 와 같다) — 텍스처가 물리 픽셀이라 GUI 배율을 곱한다
            PoseStack poseStack = new PoseStack();
            poseStack.scale(guiScale, guiScale, 1.0F);
            this.renderToTexture(state, poseStack);
            this.bufferSource.endBatch();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            modelView.popMatrix();
        }

        guiRenderState.submitBlitToCurrentLayer(
                new BlitRenderState(
                        RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                        TextureSetup.singleTexture(entry.textureView),
                        state.pose(),
                        state.x0(), state.y0(), state.x1(), state.y1(),
                        0.0F, 1.0F, 1.0F, 0.0F,
                        -1,
                        state.scissorArea(),
                        null
                )
        );
    }

    private TextureEntry prepareTexture(HudLevel level, int width, int height) {
        TextureEntry entry = textures.computeIfAbsent(level, l -> new TextureEntry());
        boolean resize = entry.texture == null || entry.texture.getWidth(0) != width || entry.texture.getHeight(0) != height;
        if (entry.texture != null && resize)
            entry.close();

        GpuDevice device = RenderSystem.getDevice();
        if (entry.texture == null) {
            entry.texture = device.createTexture(() -> "UI figura-hud " + level.name() + " texture", 12, TextureFormat.RGBA8, width, height, 1, 1);
            entry.texture.setTextureFilter(FilterMode.NEAREST, false);
            entry.textureView = device.createTextureView(entry.texture);
            entry.depthTexture = device.createTexture(() -> "UI figura-hud " + level.name() + " depth texture", 8, TextureFormat.DEPTH32, width, height, 1, 1);
            entry.depthTextureView = device.createTextureView(entry.depthTexture);
        }

        device.createCommandEncoder().clearColorAndDepthTextures(entry.texture, 0, entry.depthTexture, 1.0);
        return entry;
    }

    @Override
    protected String getTextureLabel() {
        return "figura-hud";
    }

    @Override
    public void close() {
        super.close();
        for (TextureEntry entry : textures.values())
            entry.close();
        textures.clear();
        hudProjectionMatrixBuffer.close();
    }

    private static final class TextureEntry {
        private GpuTexture texture, depthTexture;
        private GpuTextureView textureView, depthTextureView;

        private void close() {
            if (texture != null) texture.close();
            if (textureView != null) textureView.close();
            if (depthTexture != null) depthTexture.close();
            if (depthTextureView != null) depthTextureView.close();
            texture = depthTexture = null;
            textureView = depthTextureView = null;
        }
    }
}
