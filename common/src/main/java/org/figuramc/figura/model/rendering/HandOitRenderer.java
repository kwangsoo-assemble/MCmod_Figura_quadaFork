package org.figuramc.figura.model.rendering;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.lua.api.ClientAPI;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.Optional;
import java.util.OptionalDouble;

// ★ 1인칭 손 회차 OIT (하네스 M45) — 바닐라 레벨 OIT(LevelRenderer.executeOit · executeDepthBoundsCull · 합성)와
//   같은 단계 · 대상 · 샘플러로 손 패스의 반투명을 순서와 무관하게 섞는다.
//   바닐라 손 저장소는 늘 OIT 꺼짐이라 서로 파고든 반투명(1인칭 파츠 · 손에 든 색유리)이 면 정렬로만 그려졌다.
//   흐름(GameRendererMixin): renderItemInHand 머리 begin → prepareFrame 직전 notePrepare → 바닐라 손 패스가 열리기 직전 render
//   → 바닐라 renderAllFeatures 대신 renderRest → 끝 end.
//   ⚠ 화면 효과(불 · 물 · 블록 안)는 OIT 짝이 없다 — 같은 저장소의 다른 회차라 손 회차에만 OIT 꼴을 켠다(켜 둔 채 넘기면 멈춘다).
//   ⚠ OIT 꼴 묶음에선 반투명 단계가 모두 oitTranslucent 하나다 — executeTranslucent 를 부르면 같은 것을 한 번 더 그린다(레벨도 안 부른다).
public final class HandOitRenderer {

    // 레벨 대상과 같은 꼴 — 같은 꼴이면 프레임 사이 풀이 레벨이 돌려준 텍스처를 다시 준다(메모리를 더 들이지 않는다)
    private static final Vector4fc DEPTH_BOUNDS_CLEAR = new Vector4f(-Float.MAX_VALUE, 0f, 0f, 0f);
    private static final Vector4fc ZERO_CLEAR = new Vector4f(0f);
    private static final int TRANSMITTANCE_COUNT = LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT;

    private static boolean active;     // 이번 손 회차가 OIT 꼴인가
    private static boolean hasOit;     // 이번 손 회차에 OIT 단계 제출물이 있는가
    private static boolean solidDone;  // 불투명을 이미 그렸나(바닐라 패스에서 다시 그리지 않는다)
    private static boolean warned;

    private HandOitRenderer() {}

    // 조건: «개선된 투명도» 켬 · 셰이더팩 없음 — 저장소는 빈 때만 꼴이 바뀐다(앞 회차를 prepareFrame 이 비웠다)
    public static void begin(SubmitNodeStorage storage, GameRenderer gameRenderer) {
        active = hasOit = solidDone = false;
        if (!gameRenderer.useImprovedTransparency() || ClientAPI.hasShaderPack())
            return;
        try {
            storage.setUseImprovedTransparency(true);
            active = true;
        } catch (IllegalStateException e) {
            warnOnce("hand storage not empty at hand pass start", e);
        }
    }

    // ⚠ 반드시 끈다 — 켜 둔 채 화면 효과 회차가 오면 OIT 짝 없는 타입으로 멈춘다
    public static void end(SubmitNodeStorage storage) {
        if (active) {
            try {
                storage.setUseImprovedTransparency(false);
            } catch (IllegalStateException e) {
                warnOnce("hand storage not empty at hand pass end", e);
            }
        }
        active = hasOit = solidDone = false;
    }

    // prepareFrame 이 저장소를 비우기 전에 센다 — 반투명이 없으면 대상도 안 빌리고 아무것도 더 하지 않는다
    public static void notePrepare(SubmitNodeStorage storage) {
        if (!active)
            return;
        for (SubmitNodeCollection collection : storage.getSubmitsPerOrder().values()) {
            if (!collection.oitTranslucent.isEmpty()) {
                hasOit = true;
                return;
            }
        }
    }

    // 바닐라 손 패스가 열리기 직전 — 불투명 → OIT 세 단계(깊이 범위 뒤 컬링) → 합성
    public static void render(FeatureRenderDispatcher.PreparedFrame frame, GpuTextureView colorView, GpuTextureView depthView, CrossFrameResourcePool pool) {
        if (!active || !hasOit)
            return;

        int width = depthView.getWidth(0);
        int height = depthView.getHeight(0);
        RenderTargetDescriptor boundsDesc = new RenderTargetDescriptor(width, height, new RenderTargetDescriptor.TextureProperties(DEPTH_BOUNDS_CLEAR, GpuFormat.RGBA32_FLOAT), null);
        RenderTargetDescriptor zeroDesc = new RenderTargetDescriptor(width, height, new RenderTargetDescriptor.TextureProperties(ZERO_CLEAR, GpuFormat.RGBA16_FLOAT), null);

        RenderTarget depthBounds = null, culled = null, accumulate = null;
        RenderTarget[] transmittance = new RenderTarget[TRANSMITTANCE_COUNT];
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try {
            // 불투명이 손 깊이를 채워야 OIT 단계가 불투명 뒤 반투명을 가린다
            try (RenderPass pass = encoder.createRenderPass(() -> "Figura hand solid", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                frame.executeSolid(pass);
            }
            solidDone = true;

            // 풀이 빌려줄 때 기술자의 지움 색으로 지운다
            depthBounds = pool.acquire(boundsDesc);
            culled = pool.acquire(boundsDesc);
            accumulate = pool.acquire(zeroDesc);
            GpuTextureView[] transmittanceViews = new GpuTextureView[TRANSMITTANCE_COUNT];
            for (int i = 0; i < TRANSMITTANCE_COUNT; i++) {
                transmittance[i] = pool.acquire(zeroDesc);
                transmittanceViews[i] = transmittance[i].getColorTextureView();
            }

            OitRenderPassProvider.Parameters params = new OitRenderPassProvider.Parameters(
                    depthBounds.getColorTextureView(), transmittanceViews, accumulate.getColorTextureView(), depthView);
            for (OitStage stage : OitStage.values()) {
                try (RenderPass pass = OitRenderPassProvider.createRenderPass(stage, () -> "Figura hand OIT " + stage, params)) {
                    frame.executeOit(stage, pass);
                }
                if (stage == OitStage.DEPTH_BOUNDS) {
                    cullDepthBounds(encoder, depthBounds, culled, depthView);
                    params.setDepthBoundsTargetView(culled.getColorTextureView());
                }
            }

            // 합성 — Sampler0 = 누적 · Coeff{i} = 투과율 · DepthBoundsSampler = 컬링 «전» 깊이 범위(레벨과 같다)
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (RenderPass pass = encoder.createRenderPass(() -> "Figura hand OIT composite", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("Sampler0", accumulate.getColorTextureView(), sampler);
                for (int i = 0; i < TRANSMITTANCE_COUNT; i++)
                    pass.setUniform("Coeff" + i, transmittanceViews[i], sampler);
                pass.setUniform("DepthBoundsSampler", depthBounds.getColorTextureView(), sampler);
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.OIT_COMPOSITE));
                pass.draw(3, 1, 0, 0);
            }
        } catch (RuntimeException e) {
            // 그 프레임만 손 반투명을 잃는다 — 렌더 실패가 게임을 멈추지 않게(26.3 시험 가지)
            warnOnce("hand OIT failed, translucent hand parts skipped this frame", e);
        } finally {
            release(pool, boundsDesc, depthBounds);
            release(pool, boundsDesc, culled);
            release(pool, zeroDesc, accumulate);
            for (RenderTarget target : transmittance)
                release(pool, zeroDesc, target);
        }
    }

    // 바닐라 renderAllFeatures 대신 — 불투명(아직이면) · 비침 · 맨 위만. OIT 꼴이 아니면 false(바닐라 그대로)
    public static boolean renderRest(RenderPass pass, FeatureRenderDispatcher.PreparedFrame frame) {
        if (!active)
            return false;
        if (!solidDone)
            frame.executeSolid(pass);
        frame.executeSeeThrough(pass);
        frame.executeAlwaysOnTop(pass);
        return true;
    }

    // LevelRenderer.executeDepthBoundsCull 과 같다 — 깊이 범위를 복사한 뒤 손 깊이로 가려진 것을 지운다
    private static void cullDepthBounds(CommandEncoder encoder, RenderTarget depthBounds, RenderTarget culled, GpuTextureView depthView) {
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "Figura hand OIT depth bounds cull", culled.getColorTextureView(), Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.BLIT_DEPTH_BOUNDS));
            pass.setUniform("InSampler", depthBounds.getColorTextureView(), sampler);
            pass.draw(3, 1, 0, 0);
            pass.setUniform("DepthBoundsSampler", depthBounds.getColorTextureView(), sampler);
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.OIT_DEPTH_BOUNDS_CULL));
            pass.draw(3, 1, 0, 0);
        }
    }

    private static void release(CrossFrameResourcePool pool, RenderTargetDescriptor desc, RenderTarget target) {
        if (target != null)
            pool.release(desc, target);
    }

    private static void warnOnce(String message, Exception e) {
        if (warned)
            return;
        warned = true;
        FiguraMod.LOGGER.warn("[Figura] " + message, e);
    }
}
