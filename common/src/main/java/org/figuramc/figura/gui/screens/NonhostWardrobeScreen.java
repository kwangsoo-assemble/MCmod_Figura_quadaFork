package org.figuramc.figura.gui.screens;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.avatar.local.LocalAvatarFetcher;
import org.figuramc.figura.gui.widgets.Button;
import org.figuramc.figura.gui.widgets.EntityPreview;
import org.figuramc.figura.gui.widgets.Label;
import org.figuramc.figura.gui.widgets.lists.AvatarList;
import org.figuramc.figura.utils.FiguraText;
import org.figuramc.figura.utils.TextUtils;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 남 세팅용 옷장 (2026-10-23 · 하네스 mc_repositories M42) — 다른 플레이어에게 로컬 아바타를 입히고 고정한다({@code /figura nonhost_load} 의 화면판).
 * 디버그 막대기로 플레이어를 우클릭하면 열린다(리플레이 · 싱글 · 멀티 · FSB 멀티).
 * 위쪽 탭 막대 · 업로드 · 삭제 · 버전 같은 내 아바타용 부품은 없다. 바뀐 아바타는 <b>내 화면에서만</b> 보인다.
 * 선택 해제는 고정을 푼다 — 리플레이면 애드온이 녹화 당시 아바타를 다시 걸고, 그 밖이면 네트워크 아바타로 돌아간다.
 */
public class NonhostWardrobeScreen extends AbstractPanelScreen {

    private final Player target;
    private final UUID targetId;
    private Label pinState;

    public NonhostWardrobeScreen(Screen parentScreen, Player target) {
        super(parentScreen, FiguraText.of("gui.nonhost_wardrobe.title", target.getName()));
        this.target = target;
        this.targetId = target.getUUID();
    }

    @Override
    public Class<? extends Screen> getSelectedPanel() {
        return WardrobeScreen.class;
    }

    @Override
    protected void init() {
        super.init();

        // 위쪽 탭 막대는 없다 — 패널 화면 틀(배경 · 겹침 메뉴 · 툴팁)만 빌린다
        removeWidget(panels);

        int middle = width / 2;
        int panels = Math.min(width / 3, 256) - 8;
        int modelBgSize = Math.min(width - panels * 2 - 16, height - 96);
        panels = Math.max((width - modelBgSize) / 2 - 8, panels);

        // -- left -- //

        addRenderableWidget(new AvatarList(4, 28, panels, height - 32, this, new TargetPicker()));

        // -- middle -- //

        int entitySize = 11 * modelBgSize / 29;
        int entityX = middle - modelBgSize / 2;
        int entityY = this.height / 2 - modelBgSize / 2;
        addRenderableWidget(new EntityPreview(entityX, entityY, modelBgSize, modelBgSize, entitySize, -15f, 30f, target, this));

        addRenderableWidget(new Label(getTitle(), middle, 8, TextUtils.Alignment.CENTER));
        addRenderableWidget(new Label(FiguraText.of("gui.nonhost_wardrobe.local_only").withStyle(ChatFormatting.GRAY), middle, 20, TextUtils.Alignment.CENTER));
        addRenderableWidget(pinState = new Label(Component.empty(), middle, entityY + modelBgSize + 6, TextUtils.Alignment.CENTER));

        // -- bottom -- //

        int rightSide = Math.min(panels, 134);
        addRenderableWidget(new Button(width - rightSide - 4, height - 24, rightSide, 20, FiguraText.of("gui.done"), null, bx -> onClose()));
    }

    @Override
    public void tick() {
        super.tick();

        // 지금 대상에게 걸린 것 — 손(이 옷장 · nonhost_load) · 리플레이 · 네트워크
        String state = switch (AvatarManager.getPinSource(targetId)) {
            case PATH -> "gui.nonhost_wardrobe.pinned_path";
            case DATA -> "gui.nonhost_wardrobe.pinned_data";
            case NONE -> "gui.nonhost_wardrobe.unpinned";
        };
        if (pinState != null)
            pinState.setText(FiguraText.of(state).withStyle(ChatFormatting.GRAY));
    }

    private class TargetPicker implements AvatarList.Picker {
        @Override
        public boolean isSelected(LocalAvatarFetcher.AvatarPath avatar) {
            Path pinned = AvatarManager.getPinnedPath(targetId);
            return pinned != null && (pinned.equals(avatar.getPath()) || pinned.equals(avatar.getTheActualPathForThis()));
        }

        @Override
        public void pick(LocalAvatarFetcher.AvatarPath avatar) {
            AvatarManager.loadLocalAvatarFor(targetId, avatar.getPath());
        }

        @Override
        public void unselect() {
            AvatarManager.unpinAvatar(targetId);
        }
    }
}
