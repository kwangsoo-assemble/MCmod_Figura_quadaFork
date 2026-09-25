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
 * Network avatar loading lock: stops this client from <b>fetching any more avatars from the official cloud or FSB</b>.
 *
 * <pre>
 *   /figura netlock          current state + list of pins
 *   /figura netlock on|off
 *   /figura netlock status
 * </pre>
 *
 * <p>While it is on: no userdata requests are sent for new users, and responses to requests already sent, as well
 * as the clears and reloads requested by the websocket event and FSB S2CConnected/S2CNotify, are all ignored.
 * Avatars that are already loaded stay. Local loads ({@code /figura load}, {@code nonhost_load}) are unaffected.
 * It is released automatically when leaving the world (replays included).
 *
 * <p>Warning: network avatars cleared by {@code /figura reload} (all/nonhost) while locked do not come back; that is
 * what the lock means. Pinned avatars do come back.
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
