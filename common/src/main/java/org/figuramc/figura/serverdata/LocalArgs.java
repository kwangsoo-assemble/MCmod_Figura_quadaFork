package org.figuramc.figura.serverdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 싱글 로컬 모드 — {@code /fc} 인자 쪼개기 · 깃발 뽑기.
 * <p>
 * FiguraController 플러그인 {@code bukkit/FcCommand.flags} 와 같은 꼴 · 같은 오류 문구다 — 싱글에서 친 명령을 서버에서 그대로 칠 수 있어야 한다.
 * 쪼개기는 Bukkit 명령 줄처럼 빈칸 하나로(겹친 빈칸은 빈 조각이 된다 · 끝 빈칸은 버린다).
 */
public final class LocalArgs {
    private static final Pattern SPACE = Pattern.compile(" ", Pattern.LITERAL);

    /** 오류 — 문구를 그대로 사용자에게 보인다 */
    public static final class ArgError extends RuntimeException {
        public ArgError(String message) {
            super(message);
        }
    }

    /** {@code to} — `self` · `trackers` · `all` 중 하나(소문자) 또는 null */
    public record Flags(boolean priv, boolean transientState, int expire, String to, boolean dryRun, List<String> rest) {}

    private LocalArgs() {}

    public static String[] split(String line) {
        if (line == null || line.isEmpty()) return new String[0];
        return SPACE.split(line);
    }

    public static Flags flags(String[] args, int from) {
        boolean priv = false;
        boolean tr = false;
        boolean dry = false;
        int exp = 0;
        String to = null;
        List<String> rest = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--private" -> priv = true;
                case "--transient" -> tr = true;
                case "--dry-run" -> dry = true;
                case "--expire" -> {
                    if (i + 1 >= args.length) throw new ArgError("--expire <틱>");
                    try {
                        exp = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        throw new ArgError("--expire 는 틱(정수)이다");
                    }
                    if (exp <= 0) throw new ArgError("--expire 는 1 이상");
                }
                case "--to" -> {
                    if (i + 1 >= args.length) throw new ArgError("--to self|trackers|all");
                    String t = args[++i].toLowerCase(Locale.ROOT);
                    if (!t.equals("self") && !t.equals("trackers") && !t.equals("all")) throw new ArgError("audience: self · trackers · all");
                    to = t;
                }
                default -> rest.add(a);
            }
        }
        return new Flags(priv, tr, exp, to, dry, rest);
    }
}
