package org.figuramc.figura.mixin.render;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 엔티티 발광(발광 포션)이 감싼 버퍼의 안쪽 — 윤곽선을 안 만드는 진짜 버퍼.
 * {@code setGlow(false)} 파츠를 이 버퍼로 그려 엔티티 발광 윤곽선에서 뺀다({@code ImmediateAvatarRenderer.VertexBuffer}).
 */
@Mixin(OutlineBufferSource.class)
public interface OutlineBufferSourceAccessor {
    @Accessor("bufferSource")
    MultiBufferSource.BufferSource figura$getBufferSource();
}
