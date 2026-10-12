package org.figuramc.figura.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.figuramc.figura.serverdata.LocalArgs;
import org.figuramc.figura.serverdata.LocalServerData;
import org.figuramc.figura.serverdata.LocalValueParse;
import org.figuramc.figura.serverdata.PingLimiter;
import org.figuramc.figura.serverdata.ServerDataModel;
import org.figuramc.figura.serverdata.ServerDataStore;
import org.figuramc.figura.utils.FiguraClientCommandSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 싱글 로컬 모드 {@code /fc} — 서버 FiguraController 의 {@code /fc}(상태 · 신호 · 상태 보기)와 같은 꼴. 대상은 나 · {@code global} 만.
 * <p>
 * 인자는 통째 문자열로 받아 서버(Bukkit)처럼 빈칸으로 쪼갠다 — Brigadier 인자 규칙으로 다시 짜면 {@code {"a": 1}} 같은 값이 서버와 다르게 읽힌다.
 * <p>
 * ⚠ {@code requires(LocalServerData.active())} 를 빼면 멀티에서 서버 {@code /fc} 가 통째로 가려진다 — Fabric 은 클라 명령을 먼저 실행하고
 * 노드를 못 쓸 때(«모르는 명령»)만 서버로 넘긴다 · 자동완성 합치기도 {@code canUse} 를 본다.
 */
public final class LocalFcCommand {
    private static final String P = LocalServerData.PREFIX;

    private LocalFcCommand() {}

    public static LiteralArgumentBuilder<FiguraClientCommandSource> getCommandRoot() {
        LiteralArgumentBuilder<FiguraClientCommandSource> root = LiteralArgumentBuilder.<FiguraClientCommandSource>literal("fc")
                .requires(s -> LocalServerData.active());
        root.executes(ctx -> {
            usage(ctx.getSource());
            return 1;
        });
        RequiredArgumentBuilder<FiguraClientCommandSource, String> args = RequiredArgumentBuilder.<FiguraClientCommandSource, String>argument("args", StringArgumentType.greedyString())
                .suggests(LocalFcCommand::suggest)
                .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "args")));
        root.then(args);
        return root;
    }

    // ── 실행

    static int run(FiguraClientCommandSource s, String line) {
        String[] a = LocalArgs.split(line);
        try {
            if (a.length == 0) {
                usage(s);
                return 1;
            }
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "state" -> state(s, a);
                case "signal" -> signal(s, a);
                case "preset" -> throw new LocalArgs.ArgError("싱글에서는 프리셋이 없다 — 서버에서 쓴다");
                case "status" -> status(s);
                case "local" -> local(s, a);
                case "help" -> usage(s);
                default -> {
                    usage(s);
                    return 0;
                }
            }
            return 1;
        } catch (LocalArgs.ArgError | IllegalArgumentException e) {
            err(s, e.getMessage());
            return 0;
        } catch (RuntimeException e) {
            err(s, "실패: " + e);
            return 0;
        }
    }

    private static void state(FiguraClientCommandSource s, String[] a) {
        if (a.length < 3) throw new LocalArgs.ArgError("/fc state get|set|unset|clear <대상> …");
        String op = a[1].toLowerCase(Locale.ROOT);
        if (op.equals("clear") && a[2].equalsIgnoreCase("all")) {
            if (a.length < 4 || !a[3].equalsIgnoreCase("confirm")) throw new LocalArgs.ArgError("전역 + 나의 상태를 모두 지운다 — /fc state clear all confirm");
            int n = LocalServerData.clear(null) + LocalServerData.clear(me(s).getUUID());
            ok(s, "모두 지웠다 — 상태 " + n + "개");
            return;
        }
        UUID t = target(s, a[2]);
        switch (op) {
            case "get" -> print(s, t, a.length >= 4 ? a[3] : null);
            case "set" -> {
                if (a.length < 5) throw new LocalArgs.ArgError("/fc state set <대상> <이름> <값> [--private] [--transient] [--expire <틱>]");
                String name = a[3];
                LocalArgs.Flags f = LocalArgs.flags(a, 4);
                if (f.rest().isEmpty()) throw new LocalArgs.ArgError("값이 없다");
                if (f.priv() && t == null) throw new LocalArgs.ArgError("전역에는 --private 를 못 쓴다(늘 전원)");
                JsonElement v = LocalValueParse.jsonOrString(String.join(" ", f.rest()));
                LocalServerData.set(t, name, v, f.expire());
                ok(s, name + " = " + v + " — " + describe(s, t) + (f.expire() > 0 ? " §8[만료 " + f.expire() + "틱]" : ""));
                ignored(s, f);
            }
            case "unset" -> {
                if (a.length < 4) throw new LocalArgs.ArgError("/fc state unset <대상> <이름>");
                LocalServerData.unset(t, a[3]);
                ok(s, a[3] + " 지움 — " + describe(s, t));
            }
            case "clear" -> ok(s, "지움 — 상태 " + LocalServerData.clear(t) + "개 (" + describe(s, t) + ")");
            default -> throw new LocalArgs.ArgError("/fc state get|set|unset|clear");
        }
    }

    private static void signal(FiguraClientCommandSource s, String[] a) {
        if (a.length < 3) throw new LocalArgs.ArgError("/fc signal <대상> <이름> [데이터] [--to self|trackers|all]");
        UUID t = target(s, a[1]);
        String name = a[2];
        LocalArgs.Flags f = LocalArgs.flags(a, 3);
        JsonElement data = f.rest().isEmpty() ? JsonNull.INSTANCE : LocalValueParse.jsonOrString(String.join(" ", f.rest()));
        LocalServerData.signal(t, name, data);
        ok(s, "신호 " + name + " — " + describe(s, t));
        ignored(s, f);
    }

    private static void status(FiguraClientCommandSource s) {
        PingLimiter p = LocalServerData.pings();
        s.figura$sendFeedback(Component.literal("§e싱글 로컬 모드 §7— 켜짐 · 내 상태 " + ServerDataStore.own(me(s).getUUID()).size() + " · 전역 " + ServerDataStore.all(null).size()));
        s.figura$sendFeedback(Component.literal("§7핑 창(20틱) " + p.pings() + " / " + (p.rateLimit() + 1) + "번 · " + p.bytes() + " / " + p.sizeLimit()
                + " B · 신호 찍기 " + (LocalServerData.quiet() ? "끔" : "켬")));
    }

    private static void local(FiguraClientCommandSource s, String[] a) {
        if (a.length < 2) {
            status(s);
            return;
        }
        if (!a[1].equalsIgnoreCase("quiet")) throw new LocalArgs.ArgError("/fc local quiet [on|off]");
        boolean q = a.length < 3 ? !LocalServerData.quiet() : switch (a[2].toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new LocalArgs.ArgError("/fc local quiet [on|off]");
        };
        LocalServerData.setQuiet(q);
        ok(s, "아바타 신호 찍기 " + (q ? "끔" : "켬") + " (재시작하면 켬)");
    }

    private static void usage(FiguraClientCommandSource s) {
        s.figura$sendFeedback(Component.literal("§e/fc state get <대상> [이름]"));
        s.figura$sendFeedback(Component.literal("§e/fc state set <대상> <이름> <값> §7[--private] [--transient] [--expire <틱>]"));
        s.figura$sendFeedback(Component.literal("§e/fc state unset <대상> <이름> §7· §e/fc state clear <대상> | all confirm"));
        s.figura$sendFeedback(Component.literal("§e/fc signal <대상> <이름> [데이터] §7[--to self|trackers|all]"));
        s.figura$sendFeedback(Component.literal("§e/fc status §7· §e/fc local quiet [on|off]"));
        s.figura$sendFeedback(Component.literal("§8싱글 로컬 모드 — 대상은 나(@s · 내 이름) · global 만 · 프리셋 없음 · 저장 없음(월드를 나가면 비워진다)"));
    }

    private static void print(FiguraClientCommandSource s, UUID t, String name) {
        // 서버 state get 처럼 그 대상의 자기 값만(전역 빼고) · 전역이면 전역 전부
        Map<String, ServerDataModel.Entry> entries = t == null ? ServerDataStore.all(null) : ServerDataStore.own(t);
        String who = describe(s, t);
        if (name != null) {
            ServerDataModel.Entry e = entries.get(name);
            s.figura$sendFeedback(Component.literal("§e" + who + " §7· §f" + name + (e == null ? " §7(없음)" : " = " + render(t, name, e))));
            return;
        }
        s.figura$sendFeedback(Component.literal("§e" + who + " §7— 상태 " + entries.size()));
        for (Map.Entry<String, ServerDataModel.Entry> e : entries.entrySet())
            s.figura$sendFeedback(Component.literal(" §f" + e.getKey() + " = " + render(t, e.getKey(), e.getValue())));
    }

    private static String render(UUID t, String name, ServerDataModel.Entry e) {
        String v = e.json.toString();
        if (v.length() > 200) v = v.substring(0, 200) + "…";
        int left = LocalServerData.expiresIn(t, name);
        return left >= 0 ? v + " §8[만료 " + left + "틱]" : v;
    }

    private static void ignored(FiguraClientCommandSource s, LocalArgs.Flags f) {
        List<String> x = new ArrayList<>();
        if (f.priv()) x.add("--private");
        if (f.transientState()) x.add("--transient");
        if (f.to() != null) x.add("--to");
        if (f.dryRun()) x.add("--dry-run");
        if (!x.isEmpty()) s.figura$sendFeedback(Component.literal("§8(" + String.join(" · ", x) + " 는 싱글에서 뜻이 없다 — 무시)"));
    }

    private static void ok(FiguraClientCommandSource s, String msg) {
        s.figura$sendFeedback(Component.literal(P + msg));
    }

    private static void err(FiguraClientCommandSource s, String msg) {
        s.figura$sendError(Component.literal(P + "§c" + msg));
    }

    // ── 대상

    private static LocalPlayer me(FiguraClientCommandSource s) {
        LocalPlayer p = s.figura$getPlayer();
        if (p == null) throw new LocalArgs.ArgError("플레이어가 없다");
        return p;
    }

    /** null = 전역 */
    private static UUID target(FiguraClientCommandSource s, String t) {
        if (t.equalsIgnoreCase("global")) return null;
        LocalPlayer p = me(s);
        if (t.equals("@s") || t.equals("@p") || t.equals("@a") || t.equalsIgnoreCase(p.getName().getString()) || t.equalsIgnoreCase(p.getUUID().toString()))
            return p.getUUID();
        throw new LocalArgs.ArgError("싱글에서는 나(@s · 내 이름) · global 만 된다(몹은 범위 밖): " + t);
    }

    private static String describe(FiguraClientCommandSource s, UUID t) {
        return t == null ? "전역" : me(s).getName().getString();
    }

    // ── 자동완성

    private static CompletableFuture<Suggestions> suggest(CommandContext<FiguraClientCommandSource> ctx, SuggestionsBuilder b) {
        String rem = b.getRemaining();
        String[] parts = rem.split(" ", -1);
        String cur = parts[parts.length - 1];
        SuggestionsBuilder sb = b.createOffset(b.getStart() + rem.length() - cur.length());
        String low = cur.toLowerCase(Locale.ROOT);
        for (String c : candidates(ctx.getSource(), parts, parts.length - 1)) if (c.toLowerCase(Locale.ROOT).startsWith(low)) sb.suggest(c);
        return sb.buildFuture();
    }

    private static List<String> candidates(FiguraClientCommandSource s, String[] p, int i) {
        if (i == 0) return List.of("state", "signal", "status", "local", "help");
        String sub = p[0].toLowerCase(Locale.ROOT);
        if (sub.equals("state")) {
            String op = p[1].toLowerCase(Locale.ROOT);
            if (i == 1) return List.of("get", "set", "unset", "clear");
            if (i == 2) {
                List<String> t = targets(s);
                if (op.equals("clear")) t.add("all");
                return t;
            }
            if (i == 3) {
                if (op.equals("clear") && p[2].equalsIgnoreCase("all")) return List.of("confirm");
                if (op.equals("get") || op.equals("set") || op.equals("unset")) return names(s, p[2]);
            }
            if (op.equals("set") && i >= 5) return List.of("--expire", "--private", "--transient");
            return List.of();
        }
        if (sub.equals("signal")) {
            if (i == 1) return targets(s);
            if (i >= 4) return List.of("--to");
            return List.of();
        }
        if (sub.equals("local")) {
            if (i == 1) return List.of("quiet");
            if (i == 2 && p[1].equalsIgnoreCase("quiet")) return List.of("on", "off");
        }
        return List.of();
    }

    private static List<String> targets(FiguraClientCommandSource s) {
        List<String> t = new ArrayList<>(List.of("@s", "global"));
        LocalPlayer p = s.figura$getPlayer();
        if (p != null) t.add(p.getName().getString());
        return t;
    }

    private static List<String> names(FiguraClientCommandSource s, String target) {
        try {
            return new ArrayList<>(ServerDataStore.all(target(s, target)).keySet());
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
