package org.figuramc.figura.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.world.entity.Entity;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.mixin.gui.GuiGraphicsAccessor;
import org.figuramc.figura.model.HudLevel;
import org.joml.Matrix3x2f;

/**
 * HUD 부모 파츠 층 넣기 — 바닐라 GUI 묶음을 넣는 호출 «끝 직후» 마다 그 층 그림(화면 전체)을 GUI 상태에 넣는다
 * 하네스 `projects/mc_repositories/design/HUD_GUI_층/plan.md` D5 · D10 · D11
 *
 * ★ 화면 전체 크기라 바닐라 겹침 규칙이 «앞에 넣은 모든 칸(그림 · 아이템 · 글) 맨 위 + 1» 에 놓는다 —
 *   하트처럼 칸이 몇 단 쌓였든, 채팅 글이든 그 위 · 뒤에 넣는 바닐라 요소는 모두 그 위
 * ★ 자리마다 «자기 층 + 이번 프레임에 아직 못 넣은 더 낮은 층» 을 함께 넣는다 —
 *   F1 · 관전 · 화면 없음 · 스크립트가 바닐라 HUD 를 끈 때처럼 바닐라가 호출을 건너뛰어도 파츠가 사라지지 않는다
 */
public final class FiguraHudLayers {

    private static Avatar avatar;
    private static Entity entity;
    private static float tickDelta;
    private static int used, submitted;
    private static boolean active;

    private FiguraHudLayers() {}

    /** Gui.render 첫머리 — 이번 프레임에 쓰이는 층을 계산하고 BOTTOM 을 넣는다 */
    public static void begin(GuiGraphics graphics, float delta) {
        active = false;
        submitted = 0;
        used = 0;
        avatar = null;
        entity = Minecraft.getInstance().getCameraEntity();
        if (AvatarManager.panic || entity == null)
            return;
        avatar = AvatarManager.getAvatar(entity);
        if (avatar == null || avatar.renderer == null || !avatar.loaded)
            return;
        tickDelta = delta;
        used = avatar.renderer.prepareHudLevels();
        active = used != 0;
        upTo(graphics, HudLevel.BOTTOM);
    }

    public static void upTo(GuiGraphics graphics, HudLevel level) {
        if (!active)
            return;
        upTo(((GuiGraphicsAccessor) graphics).figura$getRenderState(), graphics.pose(), graphics.guiWidth(), graphics.guiHeight(), level);
    }

    public static void upTo(GuiRenderState renderState, Matrix3x2f pose, int width, int height, HudLevel level) {
        if (!active)
            return;
        for (HudLevel l : HudLevel.values()) {
            if (l.ordinal() > level.ordinal())
                break;
            int bit = 1 << l.ordinal();
            if ((used & bit) == 0 || (submitted & bit) != 0)
                continue;
            submitted |= bit;
            renderState.submitPicturesInPictureState(new FiguraHudRenderState(avatar, entity, tickDelta, l, pose, width, height));
        }
    }

    /** GuiRenderer.render 직전 — 남은 층(TOP 포함)을 다 넣고 이번 프레임을 닫는다 */
    public static void finish(GuiRenderState renderState) {
        if (active) {
            var window = Minecraft.getInstance().getWindow();
            upTo(renderState, new Matrix3x2f(), window.getGuiScaledWidth(), window.getGuiScaledHeight(), HudLevel.TOP);
        }
        active = false;
        avatar = null;
        entity = null;
    }
}
