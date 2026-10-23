package org.figuramc.figura.mixin.render.renderers;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.ducks.FiguraEntityRenderStateExtension;
import org.figuramc.figura.ducks.LivingEntityRendererAccessor;
import org.figuramc.figura.gui.PopupMenu;
import org.figuramc.figura.lua.api.vanilla_model.VanillaPart;
import org.figuramc.figura.math.matrix.FiguraMat4;
import org.figuramc.figura.model.rendering.PartFilterScheme;
import org.figuramc.figura.permissions.Permissions;
import org.figuramc.figura.utils.RenderUtils;
import org.figuramc.figura.utils.ui.UIHelper;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<S>> extends EntityRenderer<T,S> implements RenderLayerParent<S, M> {

    protected LivingEntityRendererMixin(EntityRendererProvider.Context context) {
        super(context);
    }

    @Shadow @Final protected List<RenderLayer<S, M>> layers;


    @Shadow
    public static int getOverlayCoords(LivingEntityRenderState arg, float whiteOverlayProgress) {
        return 0;
    }

    @Shadow protected abstract float getWhiteOverlayProgress(S arg);

    @Shadow protected abstract boolean isBodyVisible(S livingEntityRenderState);

    @Shadow @Final protected ItemModelResolver itemModelResolver;
    @Unique
    private Avatar currentAvatar;
    @Unique
    private Matrix4f lastPose;
    // ★ 몹(CEM) 아바타가 vanilla_model.ALL 을 숨겼나 — 이번 render 한 번 동안만 (2026-09-28, 하네스 entity_avatar_template P4)
    //   파츠 숨기기(VanillaModelProvider)는 사람형 모델의 파츠만 알아서, 사람형이 아닌 몹(주민 등)은 몸이 통째로 남고
    //   드라운드 · 스트레이 · 보그드의 겉옷은 **자기 모델을 가진 레이어**(DrownedOuterLayer · SkeletonClothingLayer)라 안 숨는다.
    //   ⇒ 이 값이 참이면 바닐라 몸(renderToBuffer)과 **모든 레이어**(shouldRenderLayers)를 건너뛴다 — 아래 두 WrapOperation.
    //   그대로인 것: 그림자 · 이름표 · 끈(EntityRenderer.render) · 아바타 파츠(피격 붉은빛 포함). 플레이어 아바타는 안 바꾼다.
    @Unique
    private boolean figura$hideVanilla;

    @Inject(at = @At("HEAD"), method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void onRender(S livingEntityRenderState, PoseStack poseStack, MultiBufferSource vertexConsumers, int i, CallbackInfo ci) {
        figura$hideVanilla = false;
        currentAvatar = AvatarManager.getAvatar(livingEntityRenderState);
        if (currentAvatar == null)
            return;

        figura$hideVanilla = !(livingEntityRenderState instanceof PlayerRenderState)
                && currentAvatar.luaRuntime != null
                && Boolean.FALSE.equals(currentAvatar.luaRuntime.vanilla_model.ALL.getVisible());
        lastPose = poseStack.last().pose();
    }

    // 바닐라 몸 — P4 면 건너뛴다. ⓘ 원래 여기 있던 @ModifyArg(customOverlay — 오버레이 덮어쓰기)를 합쳤다:
    //   같은 호출을 @ModifyArg 와 @WrapOperation 이 같이 잡는 조합을 피하려고 (동작은 같다)
    @WrapOperation(
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"
            ),
            method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
    )
    private void figura$renderVanillaBody(EntityModel<?> model, PoseStack poseStack, VertexConsumer consumer, int light, int overlay, int color, Operation<Void> original) {
        if (figura$hideVanilla)
            return;
        original.call(model, poseStack, consumer, light, LivingEntityRendererAccessor.overrideOverlay.orElse(overlay), color);
    }

    // 바닐라 레이어(갑옷 · 손에 든 것 · 겉옷 · 머리 위 블록 · 직업 옷 …) — P4 면 전부 건너뛴다
    @WrapOperation(
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"
            ),
            method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
    )
    private boolean figura$renderVanillaLayers(LivingEntityRenderer<?, ?, ?> renderer, LivingEntityRenderState state, Operation<Boolean> original) {
        return !figura$hideVanilla && original.call(renderer, state);
    }

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)V", shift = At.Shift.AFTER), method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", cancellable = true)
    private void preRender(S livingEntityRenderState, PoseStack poseStack, MultiBufferSource bufferSource, int light, CallbackInfo ci) {
        if (currentAvatar == null)
            return;

        if (Avatar.firstPerson) {
            currentAvatar.updateMatrices((LivingEntityRenderer<?, ?, ?>) (Object) this, poseStack);
            currentAvatar = null;
            lastPose = null;
            poseStack.popPose();
            ci.cancel();
            return;
        }

        if (currentAvatar.luaRuntime != null) {
            VanillaPart part = currentAvatar.luaRuntime.vanilla_model.PLAYER;
            EntityModel<?> model = getModel();
            part.save(model);
            if (currentAvatar.permissions.get(Permissions.VANILLA_MODEL_EDIT) == 1)
                part.preTransform(model);
        }

        boolean showBody = isBodyVisible(livingEntityRenderState);
        boolean translucent = !showBody && !livingEntityRenderState.isInvisibleToPlayer;
        boolean glowing = !showBody && livingEntityRenderState.appearsGlowing;
        boolean invisible = !translucent && !showBody && !glowing;
        Integer id = livingEntityRenderState instanceof PlayerRenderState playerRenderState ? playerRenderState.id : ((FiguraEntityRenderStateExtension)livingEntityRenderState).figura$getEntityId();
        if (id == null) return;

        Entity entity = Minecraft.getInstance().level.getEntity(id);
        float tickDelta = ((FiguraEntityRenderStateExtension)livingEntityRenderState).figura$getTickDelta();

        // When viewed 3rd person, render all non-world parts.
        PartFilterScheme filter = invisible ? PartFilterScheme.PIVOTS : PartFilterScheme.MODEL;
        int overlay = getOverlayCoords(livingEntityRenderState, getWhiteOverlayProgress(livingEntityRenderState));

        FiguraMod.pushProfiler(FiguraMod.MOD_ID);
        FiguraMod.pushProfiler(currentAvatar);

        FiguraMod.pushProfiler("calculateMatrix");
        Matrix4f diff = new Matrix4f(lastPose).invert().mul(poseStack.last().pose());
        FiguraMat4 poseMatrix = new FiguraMat4().set(diff);

        FiguraMod.popPushProfiler("renderEvent");
        currentAvatar.renderEvent(tickDelta, poseMatrix);

        FiguraMod.popPushProfiler("render");
        currentAvatar.render(entity, livingEntityRenderState.yRot, tickDelta, translucent ? 0.15f : 1f, poseStack, bufferSource, light, overlay, (LivingEntityRenderer<?, ?, ?>) (Object) this, filter, translucent, glowing);

        FiguraMod.popPushProfiler("postRenderEvent");
        currentAvatar.postRenderEvent(tickDelta, poseMatrix);

        FiguraMod.popProfiler(3);

        if (currentAvatar.luaRuntime != null && currentAvatar.permissions.get(Permissions.VANILLA_MODEL_EDIT) == 1)
            currentAvatar.luaRuntime.vanilla_model.PLAYER.posTransform(getModel());
    }

    @Inject(at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"), method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void endRender(S livingEntityRenderState, PoseStack matrices, MultiBufferSource vertexConsumers, int i, CallbackInfo ci) {
        figura$hideVanilla = false;
        if (currentAvatar == null)
            return;

        // Render avatar with params
        if (currentAvatar.luaRuntime != null)
            currentAvatar.luaRuntime.vanilla_model.PLAYER.restore(getModel());

        currentAvatar = null;
        lastPose = null;
    }

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
    private void shouldShowName(T livingEntity, double d, CallbackInfoReturnable<Boolean> cir) {
        if (UIHelper.paperdoll)
            cir.setReturnValue(Configs.PREVIEW_NAMEPLATE.value);
        else if (!Minecraft.renderNames() || livingEntity.getUUID().equals(PopupMenu.getEntityId()))
            cir.setReturnValue(false);
        else if (!AvatarManager.panic) {
            if (Configs.SELF_NAMEPLATE.value && livingEntity == Minecraft.getInstance().player)
                cir.setReturnValue(true);
            else if (Configs.NAMEPLATE_RENDER.value == 2 || (Configs.NAMEPLATE_RENDER.value == 1 && livingEntity != FiguraMod.extendedPickEntity))
                cir.setReturnValue(false);
        }
    }

    // Add the skull item back in after it being cleared
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;clear()V", ordinal = 0, shift = At.Shift.AFTER))
    private void shouldShowName(T entity, S livingEntityRenderState, float f, CallbackInfo ci) {
       this.itemModelResolver.updateForLiving(livingEntityRenderState.headItem, entity.getItemBySlot(EquipmentSlot.HEAD), ItemDisplayContext.HEAD, entity);
    }

    @Inject(method = "isEntityUpsideDown", at = @At("HEAD"), cancellable = true)
    private static void isEntityUpsideDown(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        Avatar avatar = AvatarManager.getAvatar(entity);
        if (RenderUtils.vanillaModelAndScript(avatar)) {
            Boolean upsideDown = avatar.luaRuntime.renderer.upsideDown;
            if (upsideDown != null)
                cir.setReturnValue(upsideDown);
        }
    }
}
