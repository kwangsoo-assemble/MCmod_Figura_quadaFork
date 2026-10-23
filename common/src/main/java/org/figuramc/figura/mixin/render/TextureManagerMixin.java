package org.figuramc.figura.mixin.render;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.figuramc.figura.model.rendering.texture.FiguraTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Map;

@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {
    @Shadow @Final private Map<Identifier, AbstractTexture> byPath;

    // 게임을 끌 때 바닐라는 byPath 를 forEach 로 돌며 닫는데, FiguraTexture.close() 는 자기를 byPath 에서 지운다
    // → ConcurrentModificationException(«Game shutdown» 크래시). Figura 텍스처를 사본 목록으로 먼저 닫아 맵에서 빼 둔다
    @Inject(method = "close", at = @At("HEAD"))
    private void closeFiguraTexturesFirst(CallbackInfo ci) {
        for (AbstractTexture texture : new ArrayList<>(byPath.values())) {
            if (texture instanceof FiguraTexture figuraTexture)
                figuraTexture.close();
        }
    }
}
