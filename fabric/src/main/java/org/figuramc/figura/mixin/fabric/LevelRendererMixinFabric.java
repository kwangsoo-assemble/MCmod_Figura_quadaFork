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
     * Marks entry into and exit from the main level pass. The outline target is valid only within this span.
     *
     * <p>Note: do not use {@code renderMode} as a substitute. When Iris replaces {@code renderEntities},
     * that value stays OTHER (measured in game). Tracking the pass directly here is the only reliable way.
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

        // Warning: cleared here every frame. The world pass is about to refill the pivot queue, so the
        //   consumption result must be reset here too; otherwise the hand render (which runs later) sees
        //   the previous frame's value.
        avatar.fpPivotItemLeft = avatar.fpPivotItemRight = false;

        avatar.firstPersonWorldRender(e, bufferSource, stack, camera, tickDelta);

        // Note: in first person, held items are drawn **at the item pivot's position**.
        //   In first person the entity layer loop is cancelled entirely, so the third-person ItemInHandLayer
        //   does not run. However, the world pass above has already queued the transforms of the pivots under
        //   World, and **since this is the same pass, the coordinate system still matches.** The queue is
        //   consumed here.
        if (state instanceof ArmedEntityRenderState armed) {
            int itemLight = this.entityRenderDispatcher.getPackedLightCoords(e, tickDelta);

            // Note: the guard now checks the level-pass flag, so renderMode does not need to be touched.
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
     * Draws a held item at the item pivot's position. Returns true if it was actually drawn.
     *
     * <p>The transform (16x scale + -90 degrees around X) matches the third-person {@code ItemInHandLayerMixin}
     * <b>exactly</b>; if they differ, the item shows in a different pose in first person than in third person.
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
            // If glow is set on the pivot, this item glows along with it
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
