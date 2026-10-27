package org.figuramc.figura.mixin.render.renderers;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.ducks.FiguraEntityRenderStateExtension;
import org.figuramc.figura.ducks.FiguraSubmitCallBackExtension;
import org.figuramc.figura.ducks.LivingEntityRendererAccessor;
import org.figuramc.figura.gui.PopupMenu;
import org.figuramc.figura.lua.api.vanilla_model.VanillaPart;
import org.figuramc.figura.math.matrix.FiguraMat4;
import org.figuramc.figura.model.rendering.PartFilterScheme;
import org.figuramc.figura.permissions.Permissions;
import org.figuramc.figura.utils.ColorUtils;
import org.figuramc.figura.utils.RenderUtils;
import org.figuramc.figura.utils.ui.UIHelper;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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

    @Shadow
    public abstract @NotNull M getModel();

    @Unique
    private Avatar currentAvatar;
    @Unique
    private Matrix4f lastPose;
    // 몹(CEM) 아바타가 vanilla_model.ALL 을 숨겼나 — 이번 submit 한 번 동안만.
    // 파츠 숨기기는 사람형 모델의 파츠만 알아서, 비사람형 몹(주민 등)의 몸과 자기 모델을 가진 레이어(드라운드 · 스트레이 겉옷)가 남는다
    // ⇒ 참이면 바닐라 몸(submitModel)과 모든 레이어(RenderLayer.submit)를 건너뛴다. 그림자 · 이름표 · 아바타 파츠는 그대로 · 플레이어는 안 바꾼다
    @Unique
    private boolean figura$hideVanilla;

    @Inject(at = @At("HEAD"), method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
    private void onRender(S livingEntityRenderState, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState, CallbackInfo ci) {
        figura$hideVanilla = false;
        currentAvatar = AvatarManager.getAvatar(livingEntityRenderState);
        if (currentAvatar == null)
            return;

        figura$hideVanilla = !(livingEntityRenderState instanceof AvatarRenderState)
                && currentAvatar.luaRuntime != null
                && Boolean.FALSE.equals(currentAvatar.luaRuntime.vanilla_model.ALL.getVisible());
        lastPose = poseStack.last().pose();
    }

    // 바닐라 몸 — 몹 아바타가 바닐라를 숨겼으면 건너뛴다 · 아니면 오버레이 덮어쓰기(customOverlay)를 얹어 그대로
    @WrapOperation(
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"
            ),
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
    )
    private void figura$submitVanillaBody(SubmitNodeCollector collector, Model<?> model, Object state, PoseStack poseStack, RenderType renderType,
                                          int light, int overlay, int tint, UvMapping uvMapping, int outlineColor, Operation<Void> original) {
        if (figura$hideVanilla)
            return;
        original.call(collector, model, state, poseStack, renderType, light, LivingEntityRendererAccessor.overrideOverlay.orElse(overlay), tint, uvMapping, outlineColor);
    }

    // 바닐라 레이어(갑옷 · 손에 든 것 · 겉옷 · 머리 위 블록 · 직업 옷 …) — 몹 아바타가 바닐라를 숨겼으면 레이어마다 건너뛴다
    // ⚠ shouldRenderLayers 를 거짓으로 돌리지 마라 — 26.3 은 EntityModel.setupAnim 을 그 if 안에서 부르고,
    //   Figura 그리기(submitFiguraModel)가 그 setupAnim 뒤에 걸려 있어 아바타 파츠까지 통째로 사라진다
    @WrapOperation(
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V"
            ),
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
    )
    private void figura$submitVanillaLayer(RenderLayer<?, ?> layer, PoseStack poseStack, SubmitNodeCollector collector, int light, EntityRenderState state, float yRot, float xRot, Operation<Void> original) {
        if (figura$hideVanilla)
            return;
        original.call(layer, poseStack, collector, light, state, yRot, xRot);
    }

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V", shift = At.Shift.BEFORE), method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", cancellable = true)
    private void setFiguraCallbacks(S livingEntityRenderState, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState, CallbackInfo ci) {
        // ⚠ 몸을 건너뛸 때는 콜백도 붙이지 않는다 — 그려지지 않은 모델에 붙은 콜백은 소비되지 않고 남는다
        if (currentAvatar == null || figura$hideVanilla)
            return;

        Avatar localAvatar = currentAvatar;
        M model = getModel();

        // capture whether this submit is the first person special submit;
        // Avatar.firstPerson stays true until renderAllFeatures finishes, so it can't be read at render time
        boolean firstPerson = Avatar.firstPerson;

        // so basically set the figura callbacks up before the model is submitted
        FiguraSubmitCallBackExtension submitCallBackExtension = (FiguraSubmitCallBackExtension) model;
        submitCallBackExtension.figura$addPreRenderingCallback((bufferSource, pose) -> {
            // transform parts so render layers see figura changes
            // do pos/rot/scale, then visibility
            figura$transformParts(localAvatar, model);
            figura$doPosTransform(localAvatar, model);

            // hide the vanilla body in the first person special submit so it doesn't block the view
            if (firstPerson)
                figura$hideVanillaParts(model);

            return true;
        });

        submitCallBackExtension.figura$addPostRenderingCallback(() -> {
            // Restore transform
            if (firstPerson)
                figura$restoreVanillaParts(model);
            if (localAvatar.luaRuntime != null)
                localAvatar.luaRuntime.vanilla_model.PLAYER.restore(getModel());
        });
    }

    @Unique
    private ModelPart[] figura$getVanillaParts(M model) {
        if (model instanceof PlayerModel playerModel) {
            return new ModelPart[] {
                    playerModel.head,
                    playerModel.hat,
                    playerModel.body,
                    playerModel.rightArm,
                    playerModel.leftArm,
                    playerModel.rightLeg,
                    playerModel.leftLeg,
                    playerModel.leftSleeve,
                    playerModel.rightSleeve,
                    playerModel.leftPants,
                    playerModel.rightPants,
                    playerModel.jacket
            };
        }
        if (model instanceof HumanoidModel<?> humanoidModel) {
            return new ModelPart[] {
                    humanoidModel.head,
                    humanoidModel.hat,
                    humanoidModel.body,
                    humanoidModel.rightArm,
                    humanoidModel.leftArm,
                    humanoidModel.rightLeg,
                    humanoidModel.leftLeg
            };
        }
        return new ModelPart[0];
    }

    @Unique
    private void figura$hideVanillaParts(M model) {
        for (ModelPart part : figura$getVanillaParts(model))
            part.visible = false;
    }

    @Unique
    private void figura$restoreVanillaParts(M model) {
        for (ModelPart part : figura$getVanillaParts(model))
            part.visible = true;
    }

    // then submit the figura model once vanilla has set posing up
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V", shift = At.Shift.AFTER), method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", cancellable = true)
    private void submitFiguraModel(S livingEntityRenderState, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState, CallbackInfo ci) {
        if (currentAvatar == null)
            return;

        Avatar localAvatar = currentAvatar;

        M model = getModel();
        
        if (Avatar.firstPerson) {
            localAvatar.updateMatrices(model, poseStack);
            currentAvatar = null;
            lastPose = null;
            poseStack.popPose();
            ci.cancel();
            return;
        }

        boolean showBody = isBodyVisible(livingEntityRenderState);
        boolean translucent = !showBody && !livingEntityRenderState.isInvisibleToPlayer;
        boolean glowing = !showBody && livingEntityRenderState.appearsGlowing();
        boolean invisible = !translucent && !showBody && !glowing;
        Integer id = livingEntityRenderState instanceof AvatarRenderState playerRenderState ? playerRenderState.id : ((FiguraEntityRenderStateExtension)livingEntityRenderState).figura$getEntityId();
        if (id == null) return;

        Integer entityIdBoxed = ((FiguraEntityRenderStateExtension)livingEntityRenderState).figura$getEntityId();
        if (entityIdBoxed == null) return;
        int entityId = entityIdBoxed;
        float tickDelta = ((FiguraEntityRenderStateExtension)livingEntityRenderState).figura$getTickDelta();

        int overlay = getOverlayCoords(livingEntityRenderState, getWhiteOverlayProgress(livingEntityRenderState));

        Entity entity = Minecraft.getInstance().level.getEntity(entityId);
        // actually do the render here

        var modelState = RenderUtils.captureModelState(model);
        RenderUtils.restoreModelPoseState(model, modelState);

        PartFilterScheme filter = invisible ? PartFilterScheme.PIVOTS : PartFilterScheme.MODEL;

        Matrix4f lastPs = new Matrix4f(lastPose);
        PoseStack poseStack2 = new PoseStack();
        poseStack2.pushPose();
        poseStack2.last().set(poseStack.last());

        FiguraMod.pushProfiler(FiguraMod.MOD_ID);
        FiguraMod.pushProfiler(localAvatar);

        FiguraMod.pushProfiler("calculateMatrix");
        Matrix4f diff = new Matrix4f(lastPs).invert().mul(poseStack2.last().pose());
        FiguraMat4 poseMatrix = new FiguraMat4().set(diff);

        FiguraMod.popPushProfiler("render");
        figura$transformParts(localAvatar, model);

        FiguraMod.popPushProfiler("renderEvent");
        localAvatar.renderEvent(tickDelta, poseMatrix);

        // 아바타 전체 바깥선 색 — 보이는 몸이어도 발광이면 넘긴다(glowing 은 «투명 + 발광» 의 렌더 타입 바꾸기 전용)
        int outlineColor = 0;
        if (livingEntityRenderState.appearsGlowing()) {
            outlineColor = (RenderUtils.vanillaModelAndScript(localAvatar) && localAvatar.luaRuntime.renderer.outlineColor != null)
                    ? ColorUtils.rgbToInt(localAvatar.luaRuntime.renderer.outlineColor)
                    : livingEntityRenderState.outlineColor;
        }

        localAvatar.render(entity, livingEntityRenderState.yRot, tickDelta, translucent ? 0.15f : 1f,
                poseStack2, submitNodeCollector, livingEntityRenderState.lightCoords, overlay, model, filter, translucent, glowing, outlineColor);


        FiguraMod.popPushProfiler("postRenderEvent");
        localAvatar.postRenderEvent(tickDelta, poseMatrix);
        FiguraMod.popProfiler(3);

        if (localAvatar.luaRuntime != null)
            localAvatar.luaRuntime.vanilla_model.PLAYER.restore(model);
    }

    @Inject(at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"), method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
    private void nullifyAvatar(S livingEntityRenderState, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState, CallbackInfo ci) {
        figura$hideVanilla = false;
        if (currentAvatar == null)
            return;

        currentAvatar = null;
        lastPose = null;
    }

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
    private void shouldShowName(T livingEntity, double d, CallbackInfoReturnable<Boolean> cir) {
        if (UIHelper.paperdoll)
            cir.setReturnValue(Configs.PREVIEW_NAMEPLATE.value);
        else if (Minecraft.getInstance().gui.hud.isHidden() || livingEntity.getUUID().equals(PopupMenu.getEntityId()))
            cir.setReturnValue(false);
        else if (!AvatarManager.panic) {
            if (Configs.SELF_NAMEPLATE.value && livingEntity == Minecraft.getInstance().player)
                cir.setReturnValue(true);
            else if (Configs.NAMEPLATE_RENDER.value == 2 || (Configs.NAMEPLATE_RENDER.value == 1 && livingEntity != FiguraMod.extendedPickEntity))
                cir.setReturnValue(false);
        }
    }

    @Unique
    void figura$transformParts(Avatar currentAvatar, M model) {
        if (currentAvatar.luaRuntime != null) {
            VanillaPart part = currentAvatar.luaRuntime.vanilla_model.PLAYER;
            part.save(model);
            if (currentAvatar.permissions.get(Permissions.VANILLA_MODEL_EDIT) == 1) {
                // this one basically does transformations such as rotation
                part.preTransform(model);
            }
        }
    }

    @Unique
    void figura$doPosTransform(Avatar currentAvatar, M model) {
        // this is responsible for visibility stuff
        if (currentAvatar.luaRuntime != null && currentAvatar.permissions.get(Permissions.VANILLA_MODEL_EDIT) == 1)
            currentAvatar.luaRuntime.vanilla_model.PLAYER.posTransform(model);
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
