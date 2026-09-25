package org.figuramc.figura.utils.selector;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Entity selector resolved on the client: a <b>subset</b> of the vanilla syntax.
 *
 * <p>Vanilla {@code @a[...]} is resolved by the server ({@code EntitySelector.findEntities(CommandSourceStack)}).
 * {@code /figura} is a client command, so it cannot use that path anywhere: not on servers, in singleplayer or in
 * replays (the singleplayer integrated server is a different world on a different thread; Flashback's ReplayServer
 * only has a spectator, and its entities are rebuilt on the client side). So only the syntax is borrowed, and the
 * selector is resolved with <b>what the client knows</b>.
 *
 * <p>This class does not depend on MC (only on brigadier's {@link StringReader}), so that parsing and matching can
 * be tested without running the game. The actual candidates are collected by {@link ClientSelector}.
 *
 * <pre>
 *   @s  self                   @p  nearest player (1)         @r  random player (1)
 *   @a  all players            @e  all entities               @n  nearest entity (1)
 *   options: type name team gamemode distance x y z dx dy dz x_rotation y_rotation limit sort
 *            (name/type/team/gamemode can be negated with !)
 *   not supported: tag scores nbt level advancements predicate. The client does not know these, so they are
 *            rejected with an <b>error</b> (rather than silently returning an empty result)
 * </pre>
 */
public final class SelectorSpec {

    public enum Kind {
        SELF('s', true, false, Sort.ARBITRARY, 1),
        NEAREST_PLAYER('p', true, false, Sort.NEAREST, 1),
        RANDOM_PLAYER('r', true, false, Sort.RANDOM, 1),
        ALL_PLAYERS('a', true, false, Sort.ARBITRARY, -1),
        ALL_ENTITIES('e', false, true, Sort.ARBITRARY, -1),
        NEAREST_ENTITY('n', false, true, Sort.NEAREST, 1);

        public final char symbol;
        public final boolean playersOnly;
        public final boolean includesEntities;
        final Sort defaultSort;
        final int defaultLimit; // -1 = unlimited

        Kind(char symbol, boolean playersOnly, boolean includesEntities, Sort defaultSort, int defaultLimit) {
            this.symbol = symbol;
            this.playersOnly = playersOnly;
            this.includesEntities = includesEntities;
            this.defaultSort = defaultSort;
            this.defaultLimit = defaultLimit;
        }

        static Kind of(char c) {
            for (Kind k : values()) if (k.symbol == c) return k;
            return null;
        }
    }

    public enum Sort { ARBITRARY, NEAREST, FURTHEST, RANDOM }

    /** Selector syntax error. The message is sent to chat as is. */
    public static final class SyntaxException extends Exception {
        public SyntaxException(String message) {
            super(message);
        }
    }

    /** A candidate reduced to just what matching needs. Uses no MC types. */
    public interface Candidate {
        UUID uuid();
        String name();
        /** in the form "minecraft:player" */
        String typeId();
        boolean isPlayer();
        boolean isSelf();
        /** false for tab-list-only players (no entity); they fail position filters and sort last by distance */
        boolean hasPosition();
        double x();
        double y();
        double z();
        /** minX,minY,minZ,maxX,maxY,maxZ; null if there is no position */
        double[] aabb();
        float xRot();
        float yRot();
        /** team name; null if none */
        String team();
        /** survival/creative/adventure/spectator; null if unknown */
        String gamemode();
    }

    /** Closed interval. A missing bound means infinity. */
    public record Range(double min, double max) {
        public boolean matches(double v) {
            return v >= min && v <= max;
        }
    }

    private static final Set<String> SERVER_ONLY = Set.of("tag", "scores", "nbt", "level", "advancements", "predicate");
    private static final Set<String> GAMEMODES = Set.of("survival", "creative", "adventure", "spectator");

    private final String source;
    private final Kind kind;
    private String nameEquals;
    private final Set<String> nameNot = new HashSet<>();
    private String typeEquals;
    private final Set<String> typeNot = new HashSet<>();
    private boolean teamEqualsSet;
    private String teamEquals;
    private final Set<String> teamNot = new HashSet<>();
    private boolean gamemodeUsed;
    private String gamemodeEquals;
    private final Set<String> gamemodeNot = new HashSet<>();
    private Range distance, xRotation, yRotation;
    private Double x, y, z, dx, dy, dz;
    private Integer limit;
    private Sort sort;

    private SelectorSpec(String source, Kind kind) {
        this.source = source;
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public String toString() {
        return source;
    }

    // -- parse -- //

    /**
     * True if it starts with {@code @x} where the second character is a selector kind.
     * Words such as {@code @look} or {@code @nearest} give false.
     */
    public static boolean looksLikeSelector(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != '@') return false;
        if (Kind.of(s.charAt(1)) == null) return false;
        return s.length() == 2 || s.charAt(2) == '[';
    }

    public static SelectorSpec parse(String text) throws SyntaxException {
        StringReader reader = new StringReader(text.strip());
        if (!reader.canRead() || reader.read() != '@')
            throw new SyntaxException("selectors start with '@'");
        if (!reader.canRead())
            throw new SyntaxException("missing selector type after '@'");
        char c = reader.read();
        Kind kind = Kind.of(c);
        if (kind == null)
            throw new SyntaxException("unknown selector type '@" + c + "' (use @s @p @r @a @e @n)");
        SelectorSpec spec = new SelectorSpec(text.strip(), kind);

        if (reader.canRead() && reader.peek() == '[') {
            reader.skip();
            spec.parseOptions(reader);
        }
        reader.skipWhitespace();
        if (reader.canRead())
            throw new SyntaxException("unexpected trailing text '" + reader.getRemaining() + "'");
        return spec;
    }

    private void parseOptions(StringReader reader) throws SyntaxException {
        while (true) {
            reader.skipWhitespace();
            if (!reader.canRead())
                throw new SyntaxException("missing ']'");
            if (reader.peek() == ']') {
                reader.skip();
                return;
            }
            String key = readUnquoted(reader);
            if (key.isEmpty())
                throw new SyntaxException("expected an option name at position " + reader.getCursor());
            reader.skipWhitespace();
            if (!reader.canRead() || reader.peek() != '=')
                throw new SyntaxException("expected '=' after '" + key + "'");
            reader.skip();
            reader.skipWhitespace();
            boolean negate = false;
            if (reader.canRead() && reader.peek() == '!') {
                negate = true;
                reader.skip();
                reader.skipWhitespace();
            }
            String value = readValue(reader);
            apply(key.toLowerCase(Locale.ROOT), value, negate);

            reader.skipWhitespace();
            if (!reader.canRead())
                throw new SyntaxException("missing ']'");
            char next = reader.read();
            if (next == ']') return;
            if (next != ',')
                throw new SyntaxException("expected ',' or ']' after '" + key + "=" + value + "'");
        }
    }

    private static String readUnquoted(StringReader reader) {
        int start = reader.getCursor();
        while (reader.canRead()) {
            char c = reader.peek();
            if (c == '=' || c == ',' || c == ']' || c == '[' || Character.isWhitespace(c)) break;
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    private static String readValue(StringReader reader) throws SyntaxException {
        if (reader.canRead() && (reader.peek() == '"' || reader.peek() == '\'')) {
            try {
                return reader.readQuotedString();
            } catch (CommandSyntaxException e) {
                throw new SyntaxException("bad quoted value: " + e.getMessage());
            }
        }
        return readUnquoted(reader);
    }

    private void apply(String key, String value, boolean negate) throws SyntaxException {
        if (SERVER_ONLY.contains(key))
            throw new SyntaxException("'" + key + "' is not available client-side (the client does not know it)");
        boolean negatable = key.equals("name") || key.equals("type") || key.equals("team") || key.equals("gamemode");
        if (negate && !negatable)
            throw new SyntaxException("'" + key + "' cannot be negated with '!'");

        switch (key) {
            case "name" -> {
                if (negate) nameNot.add(value);
                else if (nameEquals != null) throw new SyntaxException("duplicate 'name='");
                else nameEquals = value;
            }
            case "type" -> {
                if (kind.playersOnly)
                    throw new SyntaxException("'type' is not applicable to @" + kind.symbol + " (players only)");
                if (value.startsWith("#"))
                    throw new SyntaxException("entity type tags (#...) are not available client-side");
                String id = value.contains(":") ? value : "minecraft:" + value;
                if (negate) typeNot.add(id);
                else if (typeEquals != null) throw new SyntaxException("duplicate 'type='");
                else typeEquals = id;
            }
            case "team" -> {
                if (negate) teamNot.add(value);
                else if (teamEqualsSet) throw new SyntaxException("duplicate 'team='");
                else {
                    teamEqualsSet = true;
                    teamEquals = value;
                }
            }
            case "gamemode" -> {
                String mode = value.toLowerCase(Locale.ROOT);
                if (!GAMEMODES.contains(mode))
                    throw new SyntaxException("unknown gamemode '" + value + "'");
                gamemodeUsed = true;
                if (negate) gamemodeNot.add(mode);
                else if (gamemodeEquals != null) throw new SyntaxException("duplicate 'gamemode='");
                else gamemodeEquals = mode;
            }
            case "distance" -> {
                Range r = parseRange(key, value);
                if (r.min() < 0 && r.min() != Double.NEGATIVE_INFINITY)
                    throw new SyntaxException("'distance' cannot be negative");
                // "..5" means 0..5, as in vanilla (omitted lower bound = 0)
                distance = r.min() == Double.NEGATIVE_INFINITY ? new Range(0, r.max()) : r;
            }
            case "x_rotation" -> xRotation = parseRange(key, value);
            case "y_rotation" -> yRotation = parseRange(key, value);
            case "x" -> x = parseDouble(key, value);
            case "y" -> y = parseDouble(key, value);
            case "z" -> z = parseDouble(key, value);
            case "dx" -> dx = parseDouble(key, value);
            case "dy" -> dy = parseDouble(key, value);
            case "dz" -> dz = parseDouble(key, value);
            case "limit" -> {
                if (kind == Kind.SELF)
                    throw new SyntaxException("'limit' is not applicable to @s");
                if (limit != null)
                    throw new SyntaxException("duplicate 'limit='");
                int n;
                try {
                    n = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new SyntaxException("'limit' expects an integer, got '" + value + "'");
                }
                if (n < 1)
                    throw new SyntaxException("'limit' must be at least 1");
                limit = n;
            }
            case "sort" -> {
                if (kind == Kind.SELF)
                    throw new SyntaxException("'sort' is not applicable to @s");
                if (sort != null)
                    throw new SyntaxException("duplicate 'sort='");
                try {
                    sort = Sort.valueOf(value.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new SyntaxException("unknown sort '" + value + "' (nearest, furthest, random, arbitrary)");
                }
            }
            default -> throw new SyntaxException("unknown option '" + key + "'");
        }
    }

    private static double parseDouble(String key, String value) throws SyntaxException {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new SyntaxException("'" + key + "' expects a number, got '" + value + "'");
        }
    }

    /** {@code 5}, {@code ..5}, {@code 3..}, {@code 2..8} */
    static Range parseRange(String key, String value) throws SyntaxException {
        int i = value.indexOf("..");
        try {
            if (i < 0) {
                double v = Double.parseDouble(value);
                return new Range(v, v);
            }
            String lo = value.substring(0, i);
            String hi = value.substring(i + 2);
            if (lo.isEmpty() && hi.isEmpty())
                throw new SyntaxException("'" + key + "' range needs at least one bound");
            double min = lo.isEmpty() ? Double.NEGATIVE_INFINITY : Double.parseDouble(lo);
            double max = hi.isEmpty() ? Double.POSITIVE_INFINITY : Double.parseDouble(hi);
            if (min > max)
                throw new SyntaxException("'" + key + "' range is reversed (" + value + ")");
            return new Range(min, max);
        } catch (NumberFormatException e) {
            throw new SyntaxException("'" + key + "' expects a number or range, got '" + value + "'");
        }
    }

    // -- select -- //

    /**
     * @param pool   all candidates (filtered according to the kind)
     * @param ox     executor position; x/y/z options win over it when present
     * @param random for {@code sort=random} and {@code @r}
     */
    public <C extends Candidate> List<C> select(List<C> pool, double ox, double oy, double oz, Random random) {
        double originX = x != null ? x : ox;
        double originY = y != null ? y : oy;
        double originZ = z != null ? z : oz;

        List<C> out = new ArrayList<>();
        for (C c : pool)
            if (matches(c, originX, originY, originZ)) out.add(c);

        Sort s = sort != null ? sort : kind.defaultSort;
        switch (s) {
            case NEAREST -> out.sort((a, b) -> Double.compare(distSq(a, originX, originY, originZ), distSq(b, originX, originY, originZ)));
            case FURTHEST -> out.sort((a, b) -> Double.compare(distSqOrNegative(b, originX, originY, originZ), distSqOrNegative(a, originX, originY, originZ)));
            case RANDOM -> Collections.shuffle(out, random);
            case ARBITRARY -> { }
        }

        int lim = limit != null ? limit : kind.defaultLimit;
        if (lim > 0 && out.size() > lim)
            out = new ArrayList<>(out.subList(0, lim));
        return out;
    }

    private boolean matches(Candidate c, double originX, double originY, double originZ) {
        if (kind == Kind.SELF && !c.isSelf()) return false;
        if (kind.playersOnly && !c.isPlayer()) return false;

        if (typeEquals != null && !typeEquals.equals(c.typeId())) return false;
        if (typeNot.contains(c.typeId())) return false;

        if (nameEquals != null && !nameEquals.equals(c.name())) return false;
        if (nameNot.contains(c.name())) return false;

        String team = c.team() == null ? "" : c.team();
        if (teamEqualsSet && !Objects.equals(teamEquals, team)) return false;
        if (teamNot.contains(team)) return false;

        if (gamemodeUsed) {
            // same as vanilla: with a gamemode option, every non-player is excluded
            if (!c.isPlayer() || c.gamemode() == null) return false;
            if (gamemodeEquals != null && !gamemodeEquals.equals(c.gamemode())) return false;
            if (gamemodeNot.contains(c.gamemode())) return false;
        }

        if (xRotation != null && !xRotation.matches(wrapDegrees(c.xRot()))) return false;
        if (yRotation != null && !yRotation.matches(wrapDegrees(c.yRot()))) return false;

        boolean volume = dx != null || dy != null || dz != null;
        if (distance != null || volume) {
            if (!c.hasPosition()) return false;
            if (distance != null && !distance.matches(Math.sqrt(distSq(c, originX, originY, originZ)))) return false;
            if (volume && !intersectsVolume(c, originX, originY, originZ)) return false;
        }
        return true;
    }

    /** Same formula as vanilla {@code EntitySelectorParser.createAabb} + move to position + {@code AABB.intersects} */
    private boolean intersectsVolume(Candidate c, double originX, double originY, double originZ) {
        double[] box = c.aabb();
        if (box == null) return false;
        double sx = dx == null ? 0 : dx, sy = dy == null ? 0 : dy, sz = dz == null ? 0 : dz;
        double minX = originX + (sx < 0 ? sx : 0), maxX = originX + (sx < 0 ? 0 : sx) + 1;
        double minY = originY + (sy < 0 ? sy : 0), maxY = originY + (sy < 0 ? 0 : sy) + 1;
        double minZ = originZ + (sz < 0 ? sz : 0), maxZ = originZ + (sz < 0 ? 0 : sz) + 1;
        return box[0] < maxX && box[3] > minX
                && box[1] < maxY && box[4] > minY
                && box[2] < maxZ && box[5] > minZ;
    }

    private static double distSq(Candidate c, double ox, double oy, double oz) {
        if (!c.hasPosition()) return Double.POSITIVE_INFINITY; // candidates without a position go last in nearest sorting
        double ddx = c.x() - ox, ddy = c.y() - oy, ddz = c.z() - oz;
        return ddx * ddx + ddy * ddy + ddz * ddz;
    }

    private static double distSqOrNegative(Candidate c, double ox, double oy, double oz) {
        return c.hasPosition() ? distSq(c, ox, oy, oz) : -1; // they also go last in furthest sorting
    }

    private static double wrapDegrees(double v) {
        double d = v % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }
}
