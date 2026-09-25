package org.figuramc.figura.utils.selector;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.figuramc.figura.FiguraMod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Feeds {@link SelectorSpec} with the candidates the client knows about.
 *
 * <ul>
 *   <li>Players: player entities visible in the world + <b>players that are only in the tab list</b> (too far away
 *       to have an entity). The latter have no position, so they fail distance/volume conditions and go last in
 *       nearest sorting.</li>
 *   <li>{@code @e}, {@code @n}: every entity being rendered.</li>
 *   <li>gamemode is read from the tab list ({@link PlayerInfo}), team from the synced scoreboard teams.</li>
 * </ul>
 * Replays (Flashback) take the same path; the tab list and entities are rebuilt from packets there.
 */
public final class ClientSelector {

    public record Target(UUID uuid, String name, boolean isPlayer) { }

    private ClientSelector() { }

    /** @throws SelectorSpec.SyntaxException on a syntax error; the message is shown to the user as is */
    public static List<Target> resolve(String text, Vec3 origin) throws SelectorSpec.SyntaxException {
        SelectorSpec spec = SelectorSpec.parse(text);
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        ClientPacketListener connection = mc.getConnection();
        if (level == null || connection == null)
            return List.of();

        UUID self = FiguraMod.getLocalPlayerUUID();
        List<Impl> pool = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();

        if (spec.kind().includesEntities) {
            for (Entity e : level.entitiesForRendering())
                if (seen.add(e.getUUID())) pool.add(fromEntity(e, connection, self));
        } else {
            for (Player p : level.players())
                if (seen.add(p.getUUID())) pool.add(fromEntity(p, connection, self));
        }
        // players that are only in the tab list (entity not loaded)
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            UUID id = info.getProfile().getId();
            if (seen.add(id)) pool.add(fromInfo(info, level, self));
        }

        Vec3 o = origin != null ? origin : (mc.player != null ? mc.player.position() : Vec3.ZERO);
        List<Impl> picked = spec.select(pool, o.x, o.y, o.z, new Random());
        List<Target> out = new ArrayList<>(picked.size());
        for (Impl c : picked)
            out.add(new Target(c.uuid, c.name, c.isPlayer));
        return out;
    }

    private static Impl fromEntity(Entity e, ClientPacketListener connection, UUID self) {
        Impl c = new Impl();
        c.uuid = e.getUUID();
        c.isPlayer = e instanceof Player;
        c.isSelf = c.uuid.equals(self);
        c.typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        PlayerInfo info = c.isPlayer ? connection.getPlayerInfo(c.uuid) : null;
        c.name = e instanceof Player p ? p.getGameProfile().getName() : e.getName().getString();
        c.gamemode = info != null && info.getGameMode() != null ? info.getGameMode().getName() : null;
        PlayerTeam team = e.getTeam();
        c.team = team != null ? team.getName() : null;
        c.hasPosition = true;
        c.x = e.getX();
        c.y = e.getY();
        c.z = e.getZ();
        AABB box = e.getBoundingBox();
        c.aabb = new double[] { box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ };
        c.xRot = e.getXRot();
        c.yRot = e.getYRot();
        return c;
    }

    private static Impl fromInfo(PlayerInfo info, ClientLevel level, UUID self) {
        Impl c = new Impl();
        c.uuid = info.getProfile().getId();
        c.isPlayer = true;
        c.isSelf = c.uuid.equals(self);
        c.typeId = "minecraft:player";
        c.name = info.getProfile().getName();
        c.gamemode = info.getGameMode() != null ? info.getGameMode().getName() : null;
        PlayerTeam team = level.getScoreboard().getPlayersTeam(c.name);
        c.team = team != null ? team.getName() : null;
        c.hasPosition = false;
        return c;
    }

    private static final class Impl implements SelectorSpec.Candidate {
        UUID uuid;
        String name, typeId, team, gamemode;
        boolean isPlayer, isSelf, hasPosition;
        double x, y, z;
        double[] aabb;
        float xRot, yRot;

        @Override public UUID uuid() { return uuid; }
        @Override public String name() { return name; }
        @Override public String typeId() { return typeId; }
        @Override public boolean isPlayer() { return isPlayer; }
        @Override public boolean isSelf() { return isSelf; }
        @Override public boolean hasPosition() { return hasPosition; }
        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double[] aabb() { return aabb; }
        @Override public float xRot() { return xRot; }
        @Override public float yRot() { return yRot; }
        @Override public String team() { return team; }
        @Override public String gamemode() { return gamemode; }
    }
}
