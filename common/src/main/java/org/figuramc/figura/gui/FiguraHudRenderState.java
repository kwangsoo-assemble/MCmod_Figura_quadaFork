package org.figuramc.figura.gui;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.world.entity.Entity;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.model.HudLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;

/**
 * HUD 부모 파츠 한 층 — 화면 전체 크기 그림 속 그림(FiguraHudRenderer 가 층마다 따로 그린다)
 * pose = 넣을 때의 GUI 2D 행렬 — 옛 HUD 처럼 GUI 행렬(예: SillyPlugin setGuiMatrix)을 그림째 따른다
 */
public record FiguraHudRenderState(
        Avatar avatar,
        Entity entity,
        float tickDelta,
        HudLevel level,
        Matrix3x2f pose,
        int x0,
        int y0,
        int x1,
        int y1,
        float scale,
        @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {

    public FiguraHudRenderState(Avatar avatar, Entity entity, float tickDelta, HudLevel level, Matrix3x2f pose, int width, int height) {
        this(avatar, entity, tickDelta, level, new Matrix3x2f(pose), 0, 0, width, height, 1f, null,
                PictureInPictureRenderState.getBounds(0, 0, width, height, null));
    }
}
