package org.figuramc.figura.mixin.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.figuramc.figura.gui.FiguraHudLayers;
import org.figuramc.figura.model.HudLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD 부모 파츠 층 — 바닐라 HUD 묶음을 넣는 호출 «끝 직후» 마다 그 층을 넣는다(FiguraHudLayers)
 * 차례는 1.21.8 `Gui.render` · `renderHotbarAndDecorations` 바이트코드 그대로 — 하네스 `projects/mc_repositories/design/HUD_GUI_층/plan.md` D10
 * ⚠ 바닐라가 건너뛰는 호출(F1 · 관전 등)은 그 자리가 안 불린다 — 다음 자리 · `render` 끝에서 메운다
 */
@Mixin(Gui.class)
public class GuiHudLevelMixin {

    private static final String GG = "Lnet/minecraft/client/gui/GuiGraphics;";
    private static final String DT = "Lnet/minecraft/client/DeltaTracker;";

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderCameraOverlays(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterCameraOverlays(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.CAMERA_OVERLAY); }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderCrosshair(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterCrosshair(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.CROSSHAIR); }

    // -- 핫바 묶음 안 -- //

    @Inject(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderItemHotbar(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterItemHotbar(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.HOTBAR); }

    @Inject(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/spectator/SpectatorGui;renderHotbar(" + GG + ")V", shift = At.Shift.AFTER))
    private void figura$afterSpectatorHotbar(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.HOTBAR); }

    @Inject(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderVehicleHealth(" + GG + ")V", shift = At.Shift.AFTER))
    private void figura$afterStatusBars(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.STATUS_BARS); }

    @Inject(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;render(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterExperience(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.EXPERIENCE); }

    @Inject(method = "renderHotbarAndDecorations", at = @At("RETURN"))
    private void figura$afterHotbarGroup(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.ITEM_NAME); }

    // -- 나머지 HUD -- //

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderEffects(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterEffects(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.EFFECTS); }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderBossOverlay(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterBossBar(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.BOSS_BAR); }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderSleepOverlay(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterSleep(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.SLEEP); }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderDebugOverlay(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterDebug(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.DEBUG); }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderScoreboardSidebar(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterScoreboard(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.SCOREBOARD); }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderOverlayMessage(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterActionbar(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.ACTIONBAR); }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderTitle(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterTitle(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.TITLE); }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderChat(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterChat(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.CHAT); }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderTabList(" + GG + DT + ")V", shift = At.Shift.AFTER))
    private void figura$afterPlayerList(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.PLAYER_LIST); }

    // 자막 뒤 · render 끝 — HUD 층을 다 메운다(F1 이면 이 사이 호출이 대부분 건너뛰어진다)
    @Inject(method = "render", at = @At("RETURN"))
    private void figura$afterHud(GuiGraphics g, DeltaTracker d, CallbackInfo ci) { FiguraHudLayers.upTo(g, HudLevel.LAST_HUD); }
}
