package org.figuramc.figura.mixin.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.figuramc.figura.gui.FiguraHudLayers;
import org.figuramc.figura.model.HudLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD 부모 파츠 층 — 화면(인벤토리 · ESC · 발전과제 · 다이얼로그 · 채팅 입력 …) 공통 자리
 * `renderWithTooltip` = 새 층 → 배경 → 새 층 → 내용 → 툴팁(새 층) — 1.21.8 바이트코드 · 하네스 `projects/mc_repositories/design/HUD_GUI_층/plan.md` D10
 */
@Mixin(Screen.class)
public class ScreenHudLevelMixin {

    @Inject(method = "renderWithTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;renderBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", shift = At.Shift.AFTER))
    private void figura$afterBackground(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.SCREEN_BACKGROUND); }

    // 상자 화면은 내용 안에서 든 아이템 앞에 SCREEN 을 넣는다(ContainerScreenHudLevelMixin) — 여기는 든 아이템까지 뒤
    @Inject(method = "renderWithTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", shift = At.Shift.AFTER))
    private void figura$afterContents(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.SCREEN_CARRIED); }

    @Inject(method = "renderWithTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;renderDeferredTooltip()V", shift = At.Shift.AFTER))
    private void figura$afterTooltip(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.TOOLTIP); }
}
