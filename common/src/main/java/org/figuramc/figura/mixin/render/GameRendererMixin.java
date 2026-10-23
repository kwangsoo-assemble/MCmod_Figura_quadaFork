package org.figuramc.figura.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.ducks.GameRendererAccessor;
import org.figuramc.figura.gui.FiguraHudRenderer;
import org.figuramc.figura.gui.FiguraPortraitRenderer;
import org.figuramc.figura.lua.api.ClientAPI;
import org.figuramc.figura.model.rendering.HandOitRenderer;
import org.figuramc.figura.utils.RenderUtils;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin implements GameRendererAccessor {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private List<Identifier> requestedPostEffects;
    @Shadow @Final private GuiRenderer guiRenderer;
    @Shadow @Final private SubmitNodeStorage handAndScreenSubmitNodeStorage;
    @Shadow @Final private CrossFrameResourcePool resourcePool;

    @Unique
    private boolean avatarPostShader = false;
    @Unique
    private boolean hasShaders;

    @Inject(method = "update", at = @At("TAIL"))
    private void figura$addAvatarPostEffect(DeltaTracker deltaTracker, CallbackInfo ci) {
        Entity entity = this.minecraft.getCameraEntity();
        Avatar avatar = AvatarManager.getAvatar(entity);
        if (!RenderUtils.vanillaModelAndScript(avatar))
            return;

        Identifier resource = avatar.luaRuntime.renderer.postShader;
        if (resource != null)
            this.requestedPostEffects.add(resource);
    }

    @Inject(method = "checkEntityPostEffect", at = @At("HEAD"), cancellable = true)
    private void checkEntityPostEffect(Entity entity, CallbackInfo ci) {
        if (avatarPostShader)
            ci.cancel();
    }

    @Override @Intrinsic
    public double figura$getFov(Camera camera, float tickDelta, boolean changingFov) {
        return camera.getFov();
    }

    // bobbing fix courtesy of Iris; https://github.com/IrisShaders/Iris/blob/1.20.1/src/main/java/net/irisshaders/iris/mixin/MixinModelViewBobbing.java
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderLevel(CallbackInfo ci) {
        hasShaders = ClientAPI.hasShaderPack();
    }

    @ModifyArg(method = "<init>", index = 2,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;<init>(Lnet/minecraft/client/renderer/state/gui/GuiRenderState;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;Ljava/util/List;)V"))
    private List<PictureInPictureRenderer<?>> addPortraitRenderer(List<PictureInPictureRenderer<?>> list) {
        List<PictureInPictureRenderer<?>> newList = new ArrayList<>(list);
        newList.add(new FiguraPortraitRenderer());
        // HUD 부모 파츠 — FiguraGui 가 FiguraHudRenderState 를 넣는다. 여기 없으면 바닐라가 그 상태를 조용히 건너뛰어 HUD 가 안 보인다
        newList.add(new FiguraHudRenderer());
        return newList;
    }

    @ModifyExpressionValue(method = "renderLevel",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;screenEffectScale:F"))
    private float figura$disableConfusionOnMatrix(float screenEffectScale) {
        Avatar avatar = AvatarManager.getAvatar(this.minecraft.getCameraEntity() == null ? this.minecraft.player : this.minecraft.getCameraEntity());
        return (!RenderUtils.vanillaModelAndScript(avatar) || hasShaders) ? screenEffectScale : 0.0f;
    }

    // 1인칭 손 저장소도 OIT 를 안 쓴다 — 준비하는 동안 반투명 정렬을 살린다(RenderTypeFeatureRendererGroupMixin · HUD 와 같은 까닭)
    // 정렬 판단은 준비 단계(prepareFrame → prepareGroup)에서만 불린다
    @WrapOperation(method = "renderItemInHand",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
    private FeatureRenderDispatcher.PreparedFrame figura$sortHandPass(FeatureRenderDispatcher instance, SubmitNodeStorage storage, Operation<FeatureRenderDispatcher.PreparedFrame> original) {
        HandOitRenderer.notePrepare(storage);
        RenderUtils.preparingNonOitPass = true;
        try {
            return original.call(instance, storage);
        } finally {
            RenderUtils.preparingNonOitPass = false;
        }
    }

    // ★ 손 회차 OIT(M45 · HandOitRenderer) — 손 회차 동안만 손 저장소를 OIT 꼴로. 끝은 모든 return 에서 끈다
    //   (같은 저장소의 화면 효과 회차는 OIT 짝이 없다 — 켜 둔 채 넘기면 멈춘다)
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void figura$beginHandOit(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthView, CallbackInfo ci) {
        HandOitRenderer.begin(handAndScreenSubmitNodeStorage, (GameRenderer) (Object) this);
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void figura$endHandOit(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthView, CallbackInfo ci) {
        HandOitRenderer.end(handAndScreenSubmitNodeStorage);
    }

    // 바닐라 손 패스가 열리기 «전에» 불투명 → OIT → 합성을 Figura 패스로 그린다 — 바닐라 패스(try-with-resources)의 열고 닫음은 그대로
    @WrapOperation(method = "renderItemInHand",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
    private RenderPass figura$handOitBeforePass(CommandEncoder instance, Supplier<String> label, GpuTextureView colorView, Optional<?> clearColor, GpuTextureView depthView, OptionalDouble clearDepth,
                                                Operation<RenderPass> original, @Local FeatureRenderDispatcher.PreparedFrame frame) {
        HandOitRenderer.render(frame, colorView, depthView, resourcePool);
        return original.call(instance, label, colorView, clearColor, depthView, clearDepth);
    }

    // OIT 꼴 회차면 바닐라 패스에는 불투명(아직이면) · 비침 · 맨 위만 — 반투명 단계는 모두 oitTranslucent 라 다시 그리면 두 번 그린다
    @WrapOperation(method = "renderItemInHand",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
    private void figura$handOitRest(RenderPass pass, FeatureRenderDispatcher.PreparedFrame frame, Operation<Void> original) {
        if (!HandOitRenderer.renderRest(pass, frame))
            original.call(pass, frame);
    }

    @Override
    public GuiRenderer figura$getGuiRenderer() {
        return guiRenderer;
    }
}
