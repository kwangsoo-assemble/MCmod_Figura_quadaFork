package org.figuramc.figura.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.avatar.local.LocalAvatarFetcher;
import org.figuramc.figura.utils.EntityUtils;
import org.figuramc.figura.utils.FiguraClientCommandSource;
import org.figuramc.figura.utils.FiguraText;
import org.figuramc.figura.utils.selector.ClientSelector;
import org.figuramc.figura.utils.selector.SelectorSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Puts a local avatar on another player; it is visible <b>only on this client</b>. Nothing is sent to the server or
 * to other clients.
 *
 * <pre>
 *   /figura nonhost_load   &lt;target&gt; &lt;path&gt;   put the avatar at a path under avatars/ on target and pin it
 *   /figura nonhost_unload &lt;target|all&gt;     unpin -> (unless netlocked) fetch the cloud/FSB avatar again
 * </pre>
 *
 * <p>target:
 * <ul>
 *   <li>player name, UUID</li>
 *   <li>{@code @look} (the entity being looked at), {@code @nearest} (the nearest entity); specific to this mod</li>
 *   <li>vanilla-style selectors {@code @a @e @p @r @s @n} + {@code [type=, name=, team=, gamemode=, distance=, x/y/z,
 *       dx/dy/dz, x_rotation, y_rotation, limit=, sort=]}. <b>Resolved by the client, not the server</b>
 *       ({@link SelectorSpec}), so it works the same on servers, in singleplayer and in replays.
 *       {@code tag/scores/nbt/level/advancements/predicate}, which the client does not know, are errors.</li>
 * </ul>
 * Avatars attach <b>only to players</b> ({@code AvatarManager.getAvatar} reads userdata only for players).
 * Non-player entities picked by a selector are skipped, and the number skipped is reported.
 *
 * <p>Moved from SillyPlugin on 2026-09-22. The old implementation only called {@code clearAvatars()} and could not
 * restore the {@code FETCHED_USERS} mark (it is private), so the next render overwrote it with the cloud avatar.
 * Now {@link AvatarManager#loadLocalAvatarFor} handles the mark and the pin together; see the "pin / netlock"
 * section in {@code AvatarManager}.
 */
class NonhostLoadCommand {

    private static final String[] SELECTOR_HINTS = { "@a", "@e", "@p", "@r", "@s", "@n", "@look", "@nearest" };

    private static final SuggestionProvider<FiguraClientCommandSource> TARGETS = (ctx, builder) -> {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String hint : SELECTOR_HINTS)
            if (hint.startsWith(typed)) builder.suggest(hint);
        for (String name : EntityUtils.getPlayerList().keySet())
            if (name.toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(name);
        return builder.buildFuture();
    };

    private static final SuggestionProvider<FiguraClientCommandSource> PINNED_TARGETS = (ctx, builder) -> {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        if ("all".startsWith(typed)) builder.suggest("all");
        for (String hint : SELECTOR_HINTS)
            if (hint.startsWith(typed)) builder.suggest(hint);
        for (UUID uuid : AvatarManager.getPinnedAvatars().keySet()) {
            String name = EntityUtils.getNameForUUID(uuid);
            String s = name != null ? name : uuid.toString();
            if (s.toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(s);
        }
        return builder.buildFuture();
    };

    public static LiteralArgumentBuilder<FiguraClientCommandSource> getLoadCommand() {
        LiteralArgumentBuilder<FiguraClientCommandSource> load = LiteralArgumentBuilder.literal("nonhost_load");

        RequiredArgumentBuilder<FiguraClientCommandSource, String> target = RequiredArgumentBuilder.argument("target", TargetArgumentType.target());
        target.suggests(TARGETS);

        RequiredArgumentBuilder<FiguraClientCommandSource, String> path = RequiredArgumentBuilder.argument("path", StringArgumentType.greedyString());
        path.executes(NonhostLoadCommand::load);

        return load.then(target.then(path));
    }

    public static LiteralArgumentBuilder<FiguraClientCommandSource> getUnloadCommand() {
        LiteralArgumentBuilder<FiguraClientCommandSource> unload = LiteralArgumentBuilder.literal("nonhost_unload");

        RequiredArgumentBuilder<FiguraClientCommandSource, String> target = RequiredArgumentBuilder.argument("target", TargetArgumentType.target());
        target.suggests(PINNED_TARGETS);
        target.executes(NonhostLoadCommand::unload);

        return unload.then(target);
    }

    // -- load -- //

    private static int load(CommandContext<FiguraClientCommandSource> context) {
        FiguraClientCommandSource source = context.getSource();
        String targetArg = TargetArgumentType.getTarget(context, "target");
        String pathArg = StringArgumentType.getString(context, "path");

        Resolution targets = resolveTargets(targetArg, source);
        if (targets == null)
            return 0;

        Path path;
        try {
            path = LocalAvatarFetcher.getLocalAvatarDirectory().resolve(Path.of(pathArg));
        } catch (Exception e) {
            source.figura$sendError(FiguraText.of("command.load.invalid", pathArg));
            return 0;
        }

        List<String> names = new ArrayList<>();
        for (ClientSelector.Target t : targets.players) {
            try {
                AvatarManager.loadLocalAvatarFor(t.uuid(), path);
            } catch (Exception e) {
                FiguraMod.LOGGER.error("Failed to load avatar " + pathArg + " onto " + t.uuid(), e);
                source.figura$sendError(FiguraText.of("command.load.invalid", pathArg));
                return 0;
            }
            names.add(t.name());
        }

        if (targets.players.size() == 1 && !targets.selector) {
            ClientSelector.Target t = targets.players.get(0);
            if (FiguraMod.isLocal(t.uuid()))
                source.figura$sendFeedback(FiguraText.of("command.load.loading")); // for yourself this is just /figura load
            else
                source.figura$sendFeedback(FiguraText.of("command.nonhost_load.loading", pathArg, t.name()));
        } else {
            source.figura$sendFeedback(FiguraText.of("command.nonhost_load.loading_many", pathArg, names.size(), String.join(", ", names)));
        }
        targets.reportSkipped(source);
        return names.size();
    }

    // -- unload -- //

    private static int unload(CommandContext<FiguraClientCommandSource> context) {
        FiguraClientCommandSource source = context.getSource();
        String targetArg = TargetArgumentType.getTarget(context, "target");

        if (targetArg.equalsIgnoreCase("all")) {
            int count = AvatarManager.unpinAll();
            source.figura$sendFeedback(FiguraText.of("command.nonhost_unload.all", count));
            return count;
        }

        Resolution targets = resolveTargets(targetArg, source);
        if (targets == null)
            return 0;

        List<String> done = new ArrayList<>();
        for (ClientSelector.Target t : targets.players)
            if (AvatarManager.unpinAvatar(t.uuid())) done.add(t.name());

        if (targets.players.size() == 1 && !targets.selector) {
            String name = targets.players.get(0).name();
            if (done.isEmpty()) {
                source.figura$sendError(FiguraText.of("command.nonhost_unload.none", name));
                return 0;
            }
            source.figura$sendFeedback(FiguraText.of("command.nonhost_unload.done", name));
        } else {
            source.figura$sendFeedback(FiguraText.of("command.nonhost_unload.done_many", done.size(), String.join(", ", done)));
        }
        targets.reportSkipped(source);
        return done.size();
    }

    // -- target resolution -- //

    private static final class Resolution {
        final List<ClientSelector.Target> players = new ArrayList<>();
        boolean selector;
        int skippedEntities;

        void reportSkipped(FiguraClientCommandSource source) {
            if (skippedEntities > 0)
                source.figura$sendFeedback(FiguraText.of("command.nonhost_load.skipped_entities", skippedEntities));
        }
    }

    /** On failure, the user has already been notified and null is returned. */
    private static Resolution resolveTargets(String arg, FiguraClientCommandSource source) {
        Resolution r = new Resolution();

        if (SelectorSpec.looksLikeSelector(arg)) {
            r.selector = true;
            List<ClientSelector.Target> found;
            try {
                Vec3 origin = source.figura$getPlayer() != null ? source.figura$getPosition() : null;
                found = ClientSelector.resolve(arg, origin);
            } catch (SelectorSpec.SyntaxException e) {
                source.figura$sendError(FiguraText.of("command.nonhost_load.bad_selector", arg, e.getMessage()));
                return null;
            }
            for (ClientSelector.Target t : found) {
                if (t.isPlayer()) r.players.add(t);
                else r.skippedEntities++;
            }
            if (r.players.isEmpty()) {
                source.figura$sendError(FiguraText.of("command.nonhost_load.no_match", arg));
                r.reportSkipped(source);
                return null;
            }
            return r;
        }

        UUID uuid = resolveSingle(arg);
        if (uuid == null)
            uuid = findPinnedByName(arg); // so players who already left can still be unpinned
        if (uuid == null) {
            source.figura$sendError(FiguraText.of("command.nonhost_load.invalid_target", arg));
            return null;
        }
        Entity e = EntityUtils.getEntityByUUID(uuid);
        boolean isPlayer = e == null || e instanceof net.minecraft.world.entity.player.Player;
        if (!isPlayer) {
            // @look/@nearest picked a mob; avatars only attach to players
            r.skippedEntities = 1;
            source.figura$sendError(FiguraText.of("command.nonhost_load.no_match", arg));
            r.reportSkipped(source);
            return null;
        }
        r.players.add(new ClientSelector.Target(uuid, describe(uuid, arg), true));
        return r;
    }

    static UUID resolveSingle(String arg) {
        if (arg.equalsIgnoreCase("@look")) {
            Entity e = EntityUtils.getViewedEntity(64f);
            return e == null ? null : e.getUUID();
        }
        if (arg.equalsIgnoreCase("@nearest")) {
            Entity nearest = nearestEntity();
            return nearest == null ? null : nearest.getUUID();
        }
        try {
            return UUID.fromString(arg);
        } catch (IllegalArgumentException ignored) {
            // not a uuid — fall through to a name lookup
        }
        for (Map.Entry<String, UUID> entry : EntityUtils.getPlayerList().entrySet())
            if (entry.getKey().equalsIgnoreCase(arg)) return entry.getValue();
        return null;
    }

    private static UUID findPinnedByName(String arg) {
        for (UUID uuid : AvatarManager.getPinnedAvatars().keySet()) {
            String name = EntityUtils.getNameForUUID(uuid);
            if (name != null && name.equalsIgnoreCase(arg)) return uuid;
        }
        return null;
    }

    private static Entity nearestEntity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            double d = e.distanceToSqr(mc.player);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    static String describe(UUID uuid, String fallback) {
        String name = EntityUtils.getNameForUUID(uuid);
        return name != null ? name : fallback;
    }
}
