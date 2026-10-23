package org.figuramc.figura.mixin.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.math.matrix.FiguraMat3;
import org.figuramc.figura.mixin.render.PoseStackAccessor;
import org.figuramc.figura.utils.RenderUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixinFabric {
    @Shadow @Final private EntityRenderDispatcher entityRenderDispatcher;
    @Shadow @Final private SubmitNodeStorage submitNodeStorage;

    // 1인칭 월드 파츠 · 1인칭 행렬 — 레벨 프레임 안(카메라 회전이 걸린 뒤 · prepareFrame 바로 앞)에서 레벨 저장소에 함께 낸다.
    // ⚠ render RETURN 에서 따로 그리지 마라 — renderAllFeatures 는 OIT 단계를 안 그리고(OIT 켬이면 반투명이 사라진다),
    //   그때는 render 가 모델뷰 스택을 이미 되돌려 카메라 회전도 빠져 있다
    @Inject(method = "submitFeatures", at = @At("RETURN"))
    private void submitFirstPerson(LevelRenderState levelRenderState, SubmitNodeCollector collector, boolean bl, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.mainCamera();
        DeltaTracker dt = minecraft.getDeltaTracker();
        if (camera.isDetached())
            return;

        float tickDelta = dt.getGameTimeDeltaPartialTick(false);
        Entity e = camera.entity();
        Avatar avatar = AvatarManager.getAvatar(e);

        if (avatar == null || !(e instanceof LivingEntity livingEntity))
            return;

        PoseStack stack = new PoseStack();

        if (RenderUtils.vanillaModelAndScript(avatar)) {
            FiguraMat3 normal = avatar.luaRuntime.renderer.cameraNormal;
            if (normal != null)
                stack.last().normal().set(normal.toMatrix3f());
        }

        @SuppressWarnings("unchecked")
        EntityRenderer<LivingEntity, LivingEntityRenderState> entityRenderer =
                (EntityRenderer<LivingEntity, LivingEntityRenderState>) this.entityRenderDispatcher.getRenderer(livingEntity);

        LivingEntityRenderState state = entityRenderer.createRenderState(livingEntity,
                dt.getGameTimeDeltaPartialTick(minecraft.level.tickRateManager().isEntityFrozen(e)));

        avatar.firstPersonWorldRender(e, stack, this.submitNodeStorage, camera, tickDelta);

        if (!Configs.FIRST_PERSON_MATRICES.value)
            return;

        // Avatar.firstPerson 은 제출 순간에 읽힌다(LivingEntityRendererMixin 이 붙잡는다) — 제출 동안만 켠다
        Avatar.firstPerson = true;
        try {
            int lastIndex = ((PoseStackAccessor) stack).getLastIndex();
            stack.pushPose();
            Vec3 offset = entityRenderer.getRenderOffset(state);
            Vec3 cam = camera.position();
            stack.translate(
                    Mth.lerp(tickDelta, livingEntity.xOld, livingEntity.getX()) - cam.x() + offset.x(),
                    Mth.lerp(tickDelta, livingEntity.yOld, livingEntity.getY()) - cam.y() + offset.y(),
                    Mth.lerp(tickDelta, livingEntity.zOld, livingEntity.getZ()) - cam.z() + offset.z()
            );

            entityRenderer.submit(state, stack, this.submitNodeStorage, levelRenderState.cameraRenderState);
            do {
                stack.popPose();
            } while (((PoseStackAccessor) stack).getLastIndex() > lastIndex);
        } finally {
            Avatar.firstPerson = false;
        }
    }
}
