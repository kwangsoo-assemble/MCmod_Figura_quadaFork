package org.figuramc.figura.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.utils.EntityUtils;
import org.figuramc.figura.utils.FiguraClientCommandSource;
import org.figuramc.figura.utils.FiguraText;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 네트워크 아바타 로딩 잠금 — 이 클라이언트가 <b>공식 클라우드·FSB 에서 아바타를 더 받지 않게</b> 한다.
 *
 * <pre>
 *   /figura netlock          현재 상태 + pin 목록
 *   /figura netlock on|off
 *   /figura netlock status
 * </pre>
 *
 * <p>켜져 있는 동안: 새 사용자의 userdata 요청을 보내지 않고, 이미 나간 요청의 응답과 웹소켓 event ·
 * FSB S2CConnected/S2CNotify 가 요구하는 삭제·리로드를 전부 무시한다. 이미 로드된 아바타는 그대로 남는다.
 * 로컬 로드({@code /figura load} · {@code nonhost_load})는 영향 없다.
 * 월드(리플레이 포함)를 나가면 자동으로 풀린다.
 *
 * <p>⚠ 잠금 중에 {@code /figura reload}(all/nonhost) 로 지운 네트워크 아바타는 되살아나지 않는다 — 잠금이
 * 그 뜻이다. pin 된 아바타는 되살아난다.
 */
class NetlockCommand {

    public static LiteralArgumentBuilder<FiguraClientCommandSource> getCommand() {
        LiteralArgumentBuilder<FiguraClientCommandSource> cmd = LiteralArgumentBuilder.literal("netlock");
        cmd.executes(ctx -> status(ctx.getSource()));
        cmd.then(LiteralArgumentBuilder.<FiguraClientCommandSource>literal("on").executes(ctx -> set(ctx.getSource(), true)));
        cmd.then(LiteralArgumentBuilder.<FiguraClientCommandSource>literal("off").executes(ctx -> set(ctx.getSource(), false)));
        cmd.then(LiteralArgumentBuilder.<FiguraClientCommandSource>literal("status").executes(ctx -> status(ctx.getSource())));
        return cmd;
    }

    private static int set(FiguraClientCommandSource source, boolean locked) {
        AvatarManager.setNetworkLocked(locked);
        source.figura$sendFeedback(FiguraText.of(locked ? "command.netlock.on" : "command.netlock.off"));
        return 1;
    }

    private static int status(FiguraClientCommandSource source) {
        Map<UUID, Path> pinned = AvatarManager.getPinnedAvatars();
        String list;
        if (pinned.isEmpty()) {
            list = "none";
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<UUID, Path> entry : pinned.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                String name = EntityUtils.getNameForUUID(entry.getKey());
                sb.append(name != null ? name : entry.getKey().toString());
                sb.append(" <- ").append(String.valueOf(entry.getValue().getFileName()));
            }
            list = sb.toString();
        }
        boolean locked = AvatarManager.isNetworkLocked();
        source.figura$sendFeedback(FiguraText.of("command.netlock.status", locked ? "ON" : "OFF", list));
        return locked ? 1 : 0;
    }
}
