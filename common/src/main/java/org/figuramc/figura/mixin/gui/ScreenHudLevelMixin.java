package org.figuramc.figura.mixin.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
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

    // 채팅 창이 아닌 화면엔 포커스된 채팅이 없다 — CHAT_FOCUSED 를 배경 바로 위(화면 내용 밑)에 같이 넣는다
    //   (안 넣으면 다음 자리인 화면 내용 위로 밀려 «다음 묶음 밑» 이 깨진다) · 채팅 창은 ChatScreenHudLevelMixin 이 채팅 글 직후에
    @Inject(method = "renderWithTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;renderBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", shift = At.Shift.AFTER))
    private void figura$afterBackground(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        FiguraHudLayers.upTo(g, ((Object) this) instanceof ChatScreen ? HudLevel.SCREEN_BACKGROUND : HudLevel.CHAT_FOCUSED);
    }

    // 상자 화면은 내용 안에서 든 아이템 앞에 SCREEN 을 넣는다(ContainerScreenHudLevelMixin) — 여기는 든 아이템까지 뒤
    @Inject(method = "renderWithTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", shift = At.Shift.AFTER))
    private void figura$afterContents(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.SCREEN_CARRIED); }

    @Inject(method = "renderWithTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;renderDeferredTooltip()V", shift = At.Shift.AFTER))
    private void figura$afterTooltip(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.TOOLTIP); }
}
