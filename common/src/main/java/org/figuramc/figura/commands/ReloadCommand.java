package org.figuramc.figura.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.gui.FiguraToast;
import org.figuramc.figura.utils.FiguraClientCommandSource;
import org.figuramc.figura.utils.FiguraText;

/**
 * <pre>
 *   /figura reload       reloads only your own avatar
 *   /figura reload all   reloads everyone (added in this fork, 2026-09-22)
 * </pre>
 *
 * <p>{@code all} <b>does the same thing</b> as the "Reload All" button on the Permissions screen
 * (the {@code reloadAll} button in {@code PermissionsScreen}). Upstream has only the button and no command, so
 * <b>a server could not trigger it</b>; to run it from an avatar via {@code host:sendChatCommand}, it has to be
 * a command. The existing translation key {@code figura.toast.reload_all} is reused as is.
 *
 * <p>Warning: running {@code all} while netlock is on clears network avatars and <b>does not fetch them again</b>
 * (see the {@link NetlockCommand} docs). Do not run it while recording a replay.
 */
class ReloadCommand {

    public static LiteralArgumentBuilder<FiguraClientCommandSource> getCommand() {
        LiteralArgumentBuilder<FiguraClientCommandSource> cmd = LiteralArgumentBuilder.literal("reload");
        cmd.executes(context -> {
            AvatarManager.reloadAvatar(FiguraMod.getLocalPlayerUUID());
            FiguraToast.sendToast(FiguraText.of("toast.reload"));
            return 1;
        });

        // all: reload everyone
        cmd.then(LiteralArgumentBuilder.<FiguraClientCommandSource>literal("all").executes(context -> {
            AvatarManager.clearAllAvatars();
            FiguraToast.sendToast(FiguraText.of("toast.reload_all"));
            return 1;
        }));

        return cmd;
    }
}
