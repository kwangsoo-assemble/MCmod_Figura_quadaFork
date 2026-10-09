package org.figuramc.figura.serverdata;

import com.google.gson.JsonElement;
import kr.asmbl.figuracontroller.protocol.C2S;
import kr.asmbl.figuracontroller.protocol.Protocol;
import kr.asmbl.figuracontroller.protocol.ProtocolException;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.backend2.FSB;
import org.figuramc.figura.lua.ReadOnlyLuaView;
import org.figuramc.figura.server.packets.CustomFSBPacket;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One store per client — keeps the states sent by the FiguraController server plugin ({@code figuracontroller:v1}) <b>outside</b> avatars.
 * Avatars only <b>read</b> them through {@code server_data} ({@link org.figuramc.figura.lua.api.ServerDataAPI}).
 * The core is {@link ServerDataModel} (pure) — this class connects it to Minecraft.
 * <ul>
 *   <li>Packets are intercepted by {@code S2CCustomFSBPacketHandler} before avatars see them — nothing is lost while an avatar is not loaded yet, and mob avatars keep their values when they are recreated.</li>
 *   <li>Events are not fired per packet but at the <b>start of the next client tick</b>, comparing «before first touch» with «now» — a RESET followed by FULLs (init · Flashback rewind)
 *       does not produce «removed → added again» noise.</li>
 *   <li>Delivery: a subject's own change → that subject's avatar + avatars watching it · a global change → every avatar without an own value (subject argument nil) ·
 *       signals → the subject's avatar + watchers · global signals → everyone.</li>
 *   <li>Everything runs on the client main thread (packet handling · avatar tick · Flashback snapshot are all on the Render thread).</li>
 * </ul>
 */
public final class ServerDataStore {
    static final ServerDataModel MODEL = new ServerDataModel();
    private static boolean warnedBroken;

    private ServerDataStore() {}

    // ── incoming packets

    /** @return whether this packet was ours (then it is not passed to avatars) */
    public static boolean intercept(CustomFSBPacket packet) {
        if (packet.id() != Protocol.CHANNEL_ID) return false;
        try {
            MODEL.apply(packet.data());
        } catch (ProtocolException e) {
            if (!warnedBroken) {
                warnedBroken = true;
                FiguraMod.LOGGER.warn("[server_data] Dropping a malformed packet (server plugin and core versions may differ): {}", e.getMessage());
            }
        }
        return true;
    }

    /** Left the server · left the world (replays too) — clears without events (avatars are unloaded as well) */
    public static void clear() {
        MODEL.clear();
    }

    // ── reading (ServerDataAPI)

    public static LuaValue get(UUID subject, String name) {
        ServerDataModel.Entry e = MODEL.effective(subject, name);
        return e == null ? LuaValue.NIL : e.lua();
    }

    public static LuaValue getGlobal(String name) {
        ServerDataModel.Entry e = MODEL.globalEntry(name);
        return e == null ? LuaValue.NIL : e.lua();
    }

    public static Map<String, ServerDataModel.Entry> all(UUID subject) {
        return MODEL.all(subject);
    }

    // ── events (before the avatar tick)

    /** Start of {@code AvatarManager.tickLoadedAvatars} — queues last tick's changes on the avatars (they run in this tick's {@code Avatar.run}) */
    public static void flushEvents() {
        ServerDataModel.Drained d = MODEL.drain();
        if (d.isEmpty()) return;
        List<Avatar> avatars = new ArrayList<>(AvatarManager.getLoadedAvatars());
        avatars.addAll(AvatarManager.getLoadedCEMAvatars());

        for (ServerDataModel.Change c : d.own()) {
            LuaValue subj = LuaValue.valueOf(c.subject().toString());
            for (Avatar a : avatars) {
                if (c.subject().equals(a.owner) || watches(a, c.subject())) queueStateChanged(a, c.name(), lua(c.now()), lua(c.before()), subj, false);
            }
        }
        for (ServerDataModel.Change c : d.global()) {
            for (Avatar a : avatars) {
                if (MODEL.hasOwn(a.owner, c.name()) || ServerDataModel.touchedOwn(d, a.owner, c.name())) continue;
                queueStateChanged(a, c.name(), lua(c.now()), lua(c.before()), LuaValue.NIL, false);
            }
        }
        for (ServerDataModel.Signal sig : d.signals()) {
            LuaValue data = ro(JsonLua.toLua(sig.data()));
            LuaValue subj = sig.subject() == null ? LuaValue.NIL : LuaValue.valueOf(sig.subject().toString());
            for (Avatar a : avatars) {
                if (sig.subject() == null || sig.subject().equals(a.owner) || watches(a, sig.subject())) {
                    a.queueLuaCall(rt -> rt.serverData == null ? null : rt.serverData.SIGNAL, LuaValue.valueOf(sig.name()), data, subj);
                }
            }
        }
    }

    private static LuaValue lua(ServerDataModel.Entry e) {
        return e == null ? LuaValue.NIL : ro(e.lua());
    }

    private static LuaValue ro(LuaValue v) {
        return v.istable() ? ReadOnlyLuaView.of(v) : v;
    }

    private static boolean watches(Avatar a, UUID subject) {
        return a.luaRuntime != null && a.luaRuntime.serverData != null && a.luaRuntime.serverData.isWatching(subject);
    }

    private static void queueStateChanged(Avatar a, String name, LuaValue now, LuaValue old, LuaValue subject, boolean initial) {
        a.queueLuaCall(rt -> rt.serverData == null ? null : rt.serverData.STATE_CHANGED,
                LuaValue.valueOf(name), now, old, subject, LuaValue.valueOf(initial));
    }

    /** An avatar was loaded (right after {@code ENTITY_INIT}) — delivers the current values once as initial events. Values from global get subject nil · own values get the own UUID */
    public static void onAvatarInit(Avatar a) {
        for (Map.Entry<String, ServerDataModel.Entry> e : MODEL.all(a.owner).entrySet()) {
            boolean own = MODEL.hasOwn(a.owner, e.getKey());
            queueStateChanged(a, e.getKey(), lua(e.getValue()), LuaValue.NIL, own ? LuaValue.valueOf(a.owner.toString()) : LuaValue.NIL, true);
        }
    }

    /** When {@code watch} is called — delivers the subject's <b>own values</b> as initial events */
    public static void onWatch(Avatar a, UUID subject) {
        for (Map.Entry<String, ServerDataModel.Entry> e : MODEL.own(subject).entrySet()) {
            queueStateChanged(a, e.getKey(), lua(e.getValue()), LuaValue.NIL, LuaValue.valueOf(subject.toString()), true);
        }
    }

    // ── sending

    private static UUID self() {
        Player p = Minecraft.getInstance().player;
        return p != null ? p.getUUID() : Minecraft.getInstance().getUser().getProfileId();
    }

    /** The FSB handshake finished (first time, or the server lost the session) — tells the server we are ready · the server answers with RESET + everything */
    public static void sendHello() {
        FSB fsb = FSB.instance();
        if (!fsb.connected()) return;
        fsb.sendPacket(new CustomFSBPacket(self(), Protocol.CHANNEL_ID, C2S.hello(Protocol.VERSION)));
    }

    /** Host avatar → server signal · @return whether it was sent (not sent without an FSB connection) */
    public static boolean sendSignal(UUID owner, String name, JsonElement data) {
        FSB fsb = FSB.instance();
        if (!fsb.connected()) return false;
        byte[] body = C2S.signal(name, data);
        if (body.length > Protocol.MAX_BODY) throw new LuaError("Signal too large (" + body.length + " bytes)");
        fsb.sendPacket(new CustomFSBPacket(owner, Protocol.CHANNEL_ID, body));
        return true;
    }

    // ── Flashback

    public static List<byte[]> snapshotBodies() {
        return MODEL.snapshotBodies();
    }

    /** Owner UUID for the snapshot FSB packets (any UUID works — interception only looks at the id) */
    public static UUID snapshotOwner() {
        return self();
    }

    // ── diagnostics

    public static int subjectCount() {
        return MODEL.subjectCount();
    }

    public static int globalCount() {
        return MODEL.globalCount();
    }
}
