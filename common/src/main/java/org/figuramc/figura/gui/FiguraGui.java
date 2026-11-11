package org.figuramc.figura.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.model.HudLevel;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

public class FiguraGui {
    public static void onRender(GuiGraphics guiGraphics, float tickDelta, CallbackInfo ci) {
        if (AvatarManager.panic)
            return;

        FiguraMod.pushProfiler(FiguraMod.MOD_ID);

        // render popup menu below everything, as if it were in the world
        FiguraMod.pushProfiler("popupMenu");
        PopupMenu.render(guiGraphics);
        FiguraMod.popProfiler();

        // get avatar
        Entity entity = Minecraft.getInstance().getCameraEntity();
        Avatar avatar = entity == null ? null : AvatarManager.getAvatar(entity);

        // hud parent type — 층마다 그림 속 그림으로 넣는다(FiguraHudLayers) · 여기는 BOTTOM
        FiguraHudLayers.begin(guiGraphics, tickDelta);

        // hud hidden by script
        if (avatar != null && avatar.luaRuntime != null && !avatar.luaRuntime.renderer.renderHUD) {
            // 바닐라 HUD 를 건너뛰니 HUD 층은 여기서 다 넣는다(파츠는 지금처럼 보인다)
            FiguraHudLayers.upTo(guiGraphics, HudLevel.LAST_HUD);
            // render figura overlays
            renderOverlays(guiGraphics);
            // cancel this method
            ci.cancel();
        }

        FiguraMod.popProfiler();
    }

    public static void renderOverlays(GuiGraphics guiGraphics) {
        FiguraMod.pushProfiler(FiguraMod.MOD_ID);

        // render paperdoll
        FiguraMod.pushProfiler("paperdoll");
        PaperDoll.render(guiGraphics, false);

        // render wheel
        FiguraMod.popPushProfiler("actionWheel");
        ActionWheel.render(guiGraphics);

        FiguraMod.popProfiler(2);
    }
}
