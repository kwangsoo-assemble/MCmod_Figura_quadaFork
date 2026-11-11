package org.figuramc.figura.mixin.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import org.figuramc.figura.gui.FiguraHudLayers;
import org.figuramc.figura.model.HudLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD 부모 파츠 층 CHAT_FOCUSED — 채팅 창이 열리면 바닐라는 HUD 채팅(`Gui.renderChat`)을 건너뛰고
 * `ChatScreen.render` 안에서 포커스된 채팅을 그린다(입력칸 배경 → 채팅 글 → 입력칸 · 명령 제안) — 1.21.8 바이트코드
 * 채팅 글 직후에 넣어 «채팅 글 위 · 입력칸 · 명령 제안 · 툴팁 밑» 이 된다 · 하네스 `projects/mc_repositories/design/HUD_GUI_층/plan.md` D16
 */
@Mixin(ChatScreen.class)
public class ChatScreenHudLevelMixin {

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V", shift = At.Shift.AFTER))
    private void figura$afterFocusedChat(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.CHAT_FOCUSED); }
}
