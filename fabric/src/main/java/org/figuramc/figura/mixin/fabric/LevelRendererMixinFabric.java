package org.figuramc.figura.mixin.fabric;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.math.Axis;
import org.figuramc.figura.model.ParentType;
import org.figuramc.figura.model.rendering.EntityRenderMode;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.math.matrix.FiguraMat3;
import org.figuramc.figura.mixin.render.PoseStackAccessor;
import org.figuramc.figura.utils.RenderUtils;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixinFabric {
    @Shadow @Final private EntityRenderDispatcher entityRenderDispatcher;

    @Shadow @Final private RenderBuffers renderBuffers;

    @Shadow @Final private Minecraft minecraft;

    /**
     * 레벨 메인 패스의 진입/이탈을 표시한다. 이 구간에서만 아웃라인 타겟이 유효하다.
     *
     * <p>★ {@code renderMode} 로 대신하면 안 된다 — Iris 가 {@code renderEntities} 를 대체하면
     * 그 값이 OTHER 로 남는다(인게임 실측). 여기서 직접 재는 것이 유일하게 믿을 수 있다.
     */
    @Inject(method = {"method_62214"}, at = @At("HEAD"))
    private void figura$levelPassStart(CallbackInfo ci) {
        RenderUtils.figura$inLevelPass = true;
    }

    @Inject(method = {"method_62214"}, at = @At("RETURN"))
    private void figura$levelPassEnd(CallbackInfo ci) {
        RenderUtils.figura$inLevelPass = false;
    }

    @Inject(method = {"method_62214"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;checkPoseStack(Lcom/mojang/blaze3d/vertex/PoseStack;)V", ordinal = 0))
    private void renderLevelFirstPerson(GpuBufferSlice gpuBufferSlice, DeltaTracker deltaTracker, Camera camera, ProfilerFiller profiler, Matrix4f matrix4f, ResourceHandle resourceHandle, ResourceHandle resourceHandle2, boolean bl, Frustum frustum, ResourceHandle resourceHandle3, ResourceHandle resourceHandle4, CallbackInfo ci, @Local PoseStack stack) {
        if (camera.isDetached())
            return;

        float tickDelta = deltaTracker.getGameTimeDeltaPartialTick(false);
        Entity e = camera.getEntity();
        Avatar avatar = AvatarManager.getAvatar(e);

        if (avatar == null || !(e instanceof LivingEntity livingEntity))
            return;

        EntityRenderer<LivingEntity, LivingEntityRenderState> entityRenderer = (EntityRenderer<LivingEntity, LivingEntityRenderState>) this.entityRenderDispatcher.getRenderer(livingEntity);

        LivingEntityRenderState state = entityRenderer.createRenderState(livingEntity, deltaTracker.getGameTimeDeltaPartialTick(Minecraft.getInstance().level.tickRateManager().isEntityFrozen(e)));
        // first person world parts
        MultiBufferSource.BufferSource bufferSource = this.renderBuffers.bufferSource();

        // ⚠ 매 프레임 여기서 지운다. 월드 패스가 곧 피벗 큐를 새로 채우므로, 소비 결과도 여기서 초기화해야
        //   손 렌더(나중 시점)가 지난 프레임 값을 보지 않는다.
        avatar.fpPivotItemLeft = avatar.fpPivotItemRight = false;

        avatar.firstPersonWorldRender(e, bufferSource, stack, camera, tickDelta);

        // ★ 1인칭에서 손 아이템을 **아이템 피벗 자리에** 그린다.
        //   1인칭은 엔티티 레이어 루프가 통째로 취소되므로 3인칭의 ItemInHandLayer 가 안 돈다.
        //   그런데 위 월드 패스가 이미 World 하위 피벗의 변환을 큐에 저장해 뒀고,
        //   **같은 패스 안이라 좌표계가 그대로 맞다.** 여기서 그 큐를 소비한다.
        if (state instanceof ArmedEntityRenderState armed) {
            int itemLight = this.entityRenderDispatcher.getPackedLightCoords(e, tickDelta);

            // ★ 가드가 이제 레벨 패스 플래그를 보므로 renderMode 를 손댈 필요가 없다.
            avatar.fpPivotItemRight = figura$renderFirstPersonPivotItem(
                    avatar, ParentType.RightItemPivot, armed.rightHandItem, bufferSource, itemLight);
            avatar.fpPivotItemLeft = figura$renderFirstPersonPivotItem(
                    avatar, ParentType.LeftItemPivot, armed.leftHandItem, bufferSource, itemLight);
        }

        // first person matrices
        if (!Configs.FIRST_PERSON_MATRICES.value)
            return;

        Avatar.firstPerson = true;

        int lastIndex = ((PoseStackAccessor)stack).getLastIndex();
        stack.pushPose();

        Vec3 offset = entityRenderer.getRenderOffset(state);
        Vec3 cam = camera.getPosition();

        stack.translate(
                Mth.lerp(tickDelta, livingEntity.xOld, livingEntity.getX()) - cam.x() + offset.x(),
                Mth.lerp(tickDelta, livingEntity.yOld, livingEntity.getY()) - cam.y() + offset.y(),
                Mth.lerp(tickDelta, livingEntity.zOld, livingEntity.getZ()) - cam.z() + offset.z()
        );


        entityRenderer.render(state, stack, bufferSource, LightTexture.FULL_BRIGHT);

        do {
            stack.popPose();
        } while(((PoseStackAccessor)stack).getLastIndex() > lastIndex);

        Avatar.firstPerson = false;
    }


    /**
     * 손 아이템을 아이템 피벗 자리에 그린다. 실제로 그렸으면 true.
     *
     * <p>변환(16배 스케일 + X축 -90도)은 3인칭 {@code ItemInHandLayerMixin} 과 **똑같이** 맞춘다 —
     * 다르면 1인칭과 3인칭에서 아이템이 다른 자세로 보인다.
     */
    @Unique
    private boolean figura$renderFirstPersonPivotItem(Avatar avatar, ParentType pivot, ItemStackRenderState item,
                                                      MultiBufferSource bufferSource, int light) {
        if (item == null || item.isEmpty())
            return false;
        return avatar.pivotPartRender(pivot, poseStack -> {
            final float s = 16f;
            poseStack.scale(s, s, s);
            poseStack.mulPose(Axis.XP.rotationDegrees(-90f));
            // 피벗에 발광이 걸려 있으면 이 아이템도 같이 발광한다
            item.render(poseStack, RenderUtils.pivotGlowBuffer(avatar, bufferSource), light, OverlayTexture.NO_OVERLAY);
        });
    }

    @Inject(method =  {"method_62214"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderBuffers;bufferSource()Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"))
    public void applyFiguraNormals(GpuBufferSlice gpuBufferSlice, DeltaTracker tracker, Camera camera, ProfilerFiller profiler, Matrix4f matrix4f, ResourceHandle resourceHandle, ResourceHandle resourceHandle2, boolean bl, Frustum frustum, ResourceHandle resourceHandle3, ResourceHandle resourceHandle4, CallbackInfo ci, @Local PoseStack poseStack) {
        Avatar avatar = AvatarManager.getAvatar(this.minecraft.getCameraEntity() == null ? this.minecraft.player : this.minecraft.getCameraEntity());
        if (!RenderUtils.vanillaModelAndScript(avatar)) return;

        FiguraMat3 normal = avatar.luaRuntime.renderer.cameraNormal;
        if (normal != null)
            poseStack.last().normal().set(normal.toMatrix3f());
    }
}
