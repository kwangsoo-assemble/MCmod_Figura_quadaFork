package org.figuramc.figura.mixin.render;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.math.matrix.FiguraMat3;
import org.figuramc.figura.math.vector.FiguraVec4;
import org.figuramc.figura.model.rendering.EntityRenderMode;
import org.figuramc.figura.utils.ColorUtils;
import org.figuramc.figura.utils.RenderUtils;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow @Final private EntityRenderDispatcher entityRenderDispatcher;
    @Shadow @Final private RenderBuffers renderBuffers;
    @Shadow @Final private Minecraft minecraft;

    /**
     * 파츠 발광이 켜져 있으면 아웃라인 후처리 패스를 강제로 켠다.
     *
     * <p>{@code renderLevel} 은 이 메서드의 반환값으로 {@code entity_outline} PostChain 을
     * 프레임그래프에 넣을지 정한다(그 값은 {@code addMainPass} 에도 넘어가 아웃라인 타겟의
     * read/write 선언까지 좌우한다). 발광 버프를 받은 엔티티가 하나도 없으면 false 라서,
     * 아웃라인 타겟에 그려도 <b>테두리가 아니라 불투명 실루엣</b>이 화면을 덮는다.
     *
     * <p>⚠⚠ {@code Minecraft.shouldEntityAppearGlowing} / {@code Entity.isCurrentlyGlowing} 은
     * <b>절대 건드리지 마라.</b> 그러면 그 엔티티가 {@code OutlineBufferSource} 로 감싸이고,
     * {@code EntityOutlineGenerator} 가 정점 색을 팀 색으로 덮어써 파츠별 색이 전부 하나로 뭉개진다.
     * 그 경로의 {@code setColor} 는 레코드 단위 no-op 이라 우회할 수 없다.
     * 여기서는 <b>프레임 단위 게이트만</b> 열고 엔티티 발광 플래그는 그대로 둔다.
     */
    @Inject(method = "collectVisibleEntities", at = @At("RETURN"), cancellable = true)
    private void figura$forcePartGlowOutlinePass(Camera camera, Frustum frustum, List<Entity> output, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && AvatarManager.anyLoadedAvatarHasPartGlow())
            cir.setReturnValue(true);
    }

    @ModifyArg(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"))
    private Entity renderLevelRenderEntity(Entity entity) {
        Avatar avatar = AvatarManager.getAvatar(entity);
        if (avatar != null)
            avatar.renderMode = EntityRenderMode.RENDER;
        return entity;
    }

    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void renderEntity(Entity entity, double cameraX, double cameraY, double cameraZ, float tickDelta, PoseStack matrices, MultiBufferSource bufferSource, CallbackInfo ci) {
        Avatar avatar = AvatarManager.getAvatar(entity);
        if (avatar == null)
            return;

        if (bufferSource instanceof OutlineBufferSource outline && RenderUtils.vanillaModelAndScript(avatar) && avatar.luaRuntime.renderer.outlineColor != null) {
            int i = ColorUtils.rgbToInt(avatar.luaRuntime.renderer.outlineColor);
            outline.setColor(
                    i >> 16 & 0xFF,
                    i >> 8 & 0xFF,
                    i & 0xFF,
                    0xFF // does nothing :(
            );
        }

        FiguraMod.pushProfiler(FiguraMod.MOD_ID);
        FiguraMod.pushProfiler(avatar);
        FiguraMod.pushProfiler("worldRender");

        avatar.worldRender(entity, cameraX, cameraY, cameraZ, matrices, bufferSource, entityRenderDispatcher.getPackedLightCoords(entity, tickDelta), tickDelta, EntityRenderMode.WORLD);

        FiguraMod.popProfiler(3);
    }

    // TODO: Neo does not boot, complains method must be static but it won't compile if it is, the hell?
    // method_62214 for Fabric, lambda$addMainPass$2 for Neo and lambda$addMainPass$1 for Lex

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderLevel(GraphicsResourceAllocator graphicsResourceAllocator, DeltaTracker deltaTracker, boolean bl, Camera camera, Matrix4f matrix4f, Matrix4f matrix4f2, GpuBufferSlice gpuBufferSlice, Vector4f vector4f, boolean bl2, CallbackInfo ci) {
        AvatarManager.executeAll("worldRender", avatar -> avatar.render(deltaTracker.getGameTimeDeltaPartialTick(false)));
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void afterRenderLevel(GraphicsResourceAllocator graphicsResourceAllocator, DeltaTracker deltaTracker, boolean bl, Camera camera, Matrix4f matrix4f, Matrix4f matrix4f2, GpuBufferSlice gpuBufferSlice, Vector4f vector4f, boolean bl2, CallbackInfo ci) {
        AvatarManager.executeAll("postWorldRender", avatar -> avatar.postWorldRenderEvent(deltaTracker.getGameTimeDeltaPartialTick(false)));
    }

    @ModifyArg(method = "renderHitOutline", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShapeRenderer;renderShape(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/phys/shapes/VoxelShape;DDDI)V"), index = 6)
    private int renderHitOutline(int colorInt) {
        Avatar avatar = AvatarManager.getAvatar(this.minecraft.getCameraEntity());
        FiguraVec4 color;

        if (avatar == null || avatar.luaRuntime == null || (color = avatar.luaRuntime.renderer.blockOutlineColor) == null)
            return colorInt;

        // colorFromFloat 는 (alpha, red, green, blue) 순서다 — blockOutlineColor 는 (r, g, b, a) 라 w 가 먼저 온다.
        // 원본 1.21.8 브랜치도 이 순서다. 0.1.6-dev(1.21.4) 판을 이식하면서 (x, y, z, w) 가 되살아났던 것을 2026-09-25 되돌림.
        return ARGB.colorFromFloat((float) color.w, (float) color.x, (float) color.y, (float) color.z);
    }
}