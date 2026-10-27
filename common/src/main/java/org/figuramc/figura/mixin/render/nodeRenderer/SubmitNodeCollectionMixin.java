package org.figuramc.figura.mixin.render.nodeRenderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import net.minecraft.world.item.ItemDisplayContext;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.ducks.FlameSubmitExtension;
import org.figuramc.figura.ducks.FiguraSubmitCallBackExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {
    @WrapOperation(method = "submitModel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V"))
    private <S, Submit extends SubmitNode> void figura$transferTranslucent(FeatureRenderPhase<? super TranslucentSubmit> instance, Submit submit, Operation<Void> original, @Local(argsOnly = true) Model<? super S> model) {
        figura$transfer(model, submit);
        original.call(instance, submit);
    }

    @WrapOperation(method = "submitModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V"))
    private <S> void figura$onSubmitModel(SimpleFeatureRenderPhase instance, SubmitNode submit, Operation<Void> original, @Local(argsOnly = true) Model<? super S> model) {
        figura$transfer(model, submit);
        original.call(instance, submit);
    }

    // ⚠ 여기서 모델의 콜백을 비우지 마라 — 발광이면 바닐라가 같은 호출 안에서 outline 제출을 하나 더 만든다.
    // 첫 제출에서 비우면 바깥선 사본에는 숨김 · 자세 콜백이 없어 숨긴 바닐라 몸이 바깥선에 통째로 그려진다
    @Unique
    private void figura$transfer(Model<?> model, Object submitObj) {
        FiguraSubmitCallBackExtension modelExt = (FiguraSubmitCallBackExtension) model;
        FiguraSubmitCallBackExtension submitExt = (FiguraSubmitCallBackExtension) submitObj;

        for (var cb : modelExt.figura$getPreRenderingCallbacks())
            submitExt.figura$addPreRenderingCallback(cb);
        for (var cb : modelExt.figura$getPostRenderingCallbacks())
            submitExt.figura$addPostRenderingCallback(cb);

        submitExt.figura$setPreventAnimSetup(modelExt.figura$getPreventAnimSetup());
    }

    // 제출이 다 끝난 뒤에 비운다 — 본 제출 · 바깥선 제출 모두 같은 콜백을 받았다
    @Inject(method = "submitModel", at = @At("RETURN"))
    private void figura$clearModelCallbacks(CallbackInfo ci, @Local(argsOnly = true) Model<?> model) {
        FiguraSubmitCallBackExtension modelExt = (FiguraSubmitCallBackExtension) model;
        modelExt.figura$setPreventAnimSetup(false);
        modelExt.figura$getPreRenderingCallbacks().clear();
        modelExt.figura$getPostRenderingCallbacks().clear();
    }

    @WrapOperation(method = "submitItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V"))
    private void figura$onSubmitItemSimple(SimpleFeatureRenderPhase instance, SubmitNode submit, Operation<Void> original,
                                           @Local(argsOnly = true) ItemDisplayContext displayContext) {
        figura$transferItem(displayContext, submit);
        original.call(instance, submit);
    }

    @WrapOperation(method = "submitItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V"))
    private <Submit extends SubmitNode> void figura$onSubmitItemTranslucent(FeatureRenderPhase<? super TranslucentSubmit> instance, Submit submit, Operation<Void> original, @Local(argsOnly = true) ItemDisplayContext displayContext) {
        figura$transferItem(displayContext, submit);
        original.call(instance, submit);
    }

    @Unique
    private void figura$transferItem(ItemDisplayContext displayContext, Object submitObj) {
        FiguraSubmitCallBackExtension displayExt = (FiguraSubmitCallBackExtension) (Object) displayContext;
        FiguraSubmitCallBackExtension submitExt = (FiguraSubmitCallBackExtension) submitObj;

        for (var cb : displayExt.figura$getPreRenderingCallbacks())
            submitExt.figura$addPreRenderingCallback(cb);
        for (var cb : displayExt.figura$getPostRenderingCallbacks())
            submitExt.figura$addPostRenderingCallback(cb);

        submitExt.figura$setPreventAnimSetup(displayExt.figura$getPreventAnimSetup());
    }

    // 아이템도 모델과 같다 — 본 제출 뒤 outline 제출이 따로 나간다
    @Inject(method = "submitItem", at = @At("RETURN"))
    private void figura$clearItemCallbacks(CallbackInfo ci, @Local(argsOnly = true) ItemDisplayContext displayContext) {
        FiguraSubmitCallBackExtension displayExt = (FiguraSubmitCallBackExtension) (Object) displayContext;
        displayExt.figura$setPreventAnimSetup(false);
        displayExt.figura$getPreRenderingCallbacks().clear();
        displayExt.figura$getPostRenderingCallbacks().clear();
    }

    @WrapOperation(method = "submitFlame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V"))
    private void figura$onSubmitFlame(SimpleFeatureRenderPhase instance, SubmitNode submit, Operation<Void> original) {
        var flameSubmit = (FlameFeatureRenderer.Submit) submit;

        FlameSubmitExtension itemSubmissionExtension = (FlameSubmitExtension) (Object) flameSubmit;
        Avatar avatar = AvatarManager.getAvatar(flameSubmit.entityRenderState());
        itemSubmissionExtension.figura$setAvatar(avatar);

        original.call(instance, submit);
    }
}
