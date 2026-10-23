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
 * 남에게 로컬 아바타를 입힌다 — <b>이 클라이언트에서만</b> 보인다. 서버·타 클라이언트에는 아무것도 가지 않는다.
 *
 * <pre>
 *   /figura nonhost_load   &lt;target&gt; &lt;path&gt;   avatars/ 기준 경로의 아바타를 target 에게 입히고 pin 한다
 *   /figura nonhost_unload &lt;target|all&gt;     pin 해제 → (netlock 이 아니면) 클라우드/FSB 아바타를 다시 받는다
 * </pre>
 *
 * <p>target:
 * <ul>
 *   <li>플레이어 이름 · UUID</li>
 *   <li>{@code @look}(보고 있는 엔티티) · {@code @nearest}(가장 가까운 엔티티) — 이 모드 고유</li>
 *   <li>바닐라꼴 셀렉터 {@code @a @e @p @r @s @n} + {@code [type=, name=, team=, gamemode=, distance=, x/y/z, dx/dy/dz,
 *       x_rotation, y_rotation, limit=, sort=]}. <b>서버가 아니라 클라이언트가 푼다</b>({@link SelectorSpec}) —
 *       서버·싱글·리플레이 어디서나 같다. 클라가 모르는 {@code tag/scores/nbt/level/advancements/predicate} 는 에러.</li>
 * </ul>
 * 아바타는 <b>플레이어에게만</b> 붙는다({@code AvatarManager.getAvatar} 가 플레이어만 userdata 를 본다). 셀렉터가 고른
 * 비플레이어 엔티티는 건너뛰고 몇 개였는지 알린다.
 *
 * <p>2026-09-22 SillyPlugin 에서 이관. 예전 구현은 {@code clearAvatars()} 만 부르고 {@code FETCHED_USERS} 표식을
 * 되돌리지 못해(비공개) 다음 렌더가 클라우드 아바타로 덮었다. 지금은 {@link AvatarManager#loadLocalAvatarFor}
 * 가 표식·pin 을 함께 처리한다 — 「pin / netlock」 절 참고.
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
                source.figura$sendFeedback(FiguraText.of("command.load.loading")); // 자기 자신이면 /figura load 와 같다
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

    /** 실패하면 사용자에게 이미 알렸고 null 을 돌려준다. */
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
            uuid = findPinnedByName(arg); // 이미 나간 플레이어도 unpin 할 수 있게
        if (uuid == null) {
            source.figura$sendError(FiguraText.of("command.nonhost_load.invalid_target", arg));
            return null;
        }
        Entity e = EntityUtils.getEntityByUUID(uuid);
        boolean isPlayer = e == null || e instanceof net.minecraft.world.entity.player.Player;
        if (!isPlayer) {
            // @look/@nearest 가 몹을 집었다 — 아바타는 플레이어에게만 붙는다
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
