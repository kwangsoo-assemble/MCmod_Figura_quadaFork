package org.figuramc.figura.mixin.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.figuramc.figura.gui.FiguraHudLayers;
import org.figuramc.figura.model.HudLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** HUD 부모 파츠 층 — 상자 화면은 칸 아이템 뒤 · 마우스로 든 아이템(새 층) 앞에 SCREEN 을 넣는다 */
@Mixin(AbstractContainerScreen.class)
public class ContainerScreenHudLevelMixin {

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderCarriedItem(Lnet/minecraft/client/gui/GuiGraphics;II)V"))
    private void figura$beforeCarried(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.SCREEN); }
}
