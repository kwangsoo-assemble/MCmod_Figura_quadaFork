package org.figuramc.figura.mixin.render.feature;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.GameRenderer;
import org.figuramc.figura.utils.RenderUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class RenderTypeFeatureRendererGroupMixin {
    // 바닐라는 반투명 사각형을 «게임 전체 OIT 가 꺼졌을 때만» 깊이순으로 정렬한다(OIT 가 맡는다고 본다).
    // ⚠ HUD 그림 속 그림 · 1인칭 손 저장소는 OIT 를 안 쓴다(SubmitNodeStorage 의 OIT 칸 기본 false) — OIT 를 켜면 그 반투명은 정렬도 OIT 도 없이 그려진다.
    //   그 저장소를 준비하는 동안은 OIT 가 꺼진 것처럼 답해 정렬을 살린다(RenderUtils.preparingNonOitPass)
    @WrapOperation(method = "getOrAddDraw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;useImprovedTransparency()Z"))
    private boolean sortInNonOitPass(GameRenderer instance, Operation<Boolean> original) {
        return !RenderUtils.preparingNonOitPass && original.call(instance);
    }
}
