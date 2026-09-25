package org.figuramc.figura.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.gui.FiguraToast;
import org.figuramc.figura.utils.FiguraClientCommandSource;
import org.figuramc.figura.utils.FiguraText;

/**
 * <pre>
 *   /figura reload       내 아바타만 다시 불러온다
 *   /figura reload all   모두 다시 불러온다 (자체 추가, 2026-09-22)
 * </pre>
 *
 * <p>{@code all} 은 권한 화면(Permissions)의 「Reload All」 버튼과 <b>같은 동작</b>이다
 * ({@code PermissionsScreen} 의 {@code reloadAll} 버튼). 업스트림에는 버튼만 있고 명령이 없어서
 * <b>서버가 시킬 수 없었다</b> — 아바타 쪽에서 {@code host:sendChatCommand} 로 치려면 명령이어야 한다.
 * 번역 키 {@code figura.toast.reload_all} 은 원래 있던 것을 그대로 쓴다.
 *
 * <p>⚠ netlock 이 켜져 있을 때 {@code all} 을 치면 네트워크 아바타를 지운 뒤 <b>다시 받지 않는다</b>
 * ({@link NetlockCommand} 주석 참조). 리플레이 촬영 중에는 치지 말 것.
 */
class ReloadCommand {

    public static LiteralArgumentBuilder<FiguraClientCommandSource> getCommand() {
        LiteralArgumentBuilder<FiguraClientCommandSource> cmd = LiteralArgumentBuilder.literal("reload");
        cmd.executes(context -> {
            AvatarManager.reloadAvatar(FiguraMod.getLocalPlayerUUID());
            FiguraToast.sendToast(FiguraText.of("toast.reload"));
            return 1;
        });

        // all — 모두 다시 불러오기
        cmd.then(LiteralArgumentBuilder.<FiguraClientCommandSource>literal("all").executes(context -> {
            AvatarManager.clearAllAvatars();
            FiguraToast.sendToast(FiguraText.of("toast.reload_all"));
            return 1;
        }));

        return cmd;
    }
}
