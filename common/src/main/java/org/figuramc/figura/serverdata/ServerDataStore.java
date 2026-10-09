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
 * 클라 하나에 저장소 하나 — 서버 플러그인 FiguraController 가 보낸 상태(`figuracontroller:v1`)를 아바타 **밖에** 둔다
 * (하네스 `projects/figura_controller` 설계 §3). 아바타는 {@code server_data}({@link org.figuramc.figura.lua.api.ServerDataAPI})로 **읽기만** 한다.
 * 몸은 {@link ServerDataModel}(순수) — 여기는 Minecraft 와 잇는 곳이다.
 * <ul>
 *   <li>패킷은 {@code S2CCustomFSBPacketHandler} 가 아바타보다 먼저 가로챈다 — 아바타가 아직 안 실렸어도 안 잃는다 · 몹 아바타가 다시 생겨도 그대로다.</li>
 *   <li>이벤트는 패킷마다가 아니라 **다음 클라 틱 시작**에 «처음 건드리기 전 ↔ 지금» 을 비교해 낸다 — RESET 뒤 FULL(초기화 · Flashback 되감기)이
 *       «지웠다 → 다시 넣었다» 를 헛내지 않는다.</li>
 *   <li>나누는 규칙: 대상 자기 값의 변화 → 그 대상의 아바타 + 그 대상을 watch 한 아바타 · 전역 변화 → 자기 값이 없는(= 전역 값을 보던) 아바타 전부(대상 칸 nil) ·
 *       신호 → 대상의 아바타 + watch 한 아바타 · 전역 신호는 전부.</li>
 *   <li>모든 접근은 클라 메인 스레드(패킷 처리 · 아바타 틱 · Flashback 스냅샷 모두 Render thread — T1 실측).</li>
 * </ul>
 */
public final class ServerDataStore {
    static final ServerDataModel MODEL = new ServerDataModel();
    private static boolean warnedBroken;

    private ServerDataStore() {}

    // ── 들어오는 패킷

    /** @return 이 패킷이 우리 것이었나(그러면 아바타로 안 보낸다) */
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

    /** 서버를 떠남 · 월드를 나감(리플레이 포함) — 이벤트 없이 비운다(아바타도 같이 내려간다) */
    public static void clear() {
        MODEL.clear();
    }

    // ── 읽기 (ServerDataAPI)

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

    // ── 이벤트 (아바타 틱 앞에서)

    /** {@code AvatarManager.tickLoadedAvatars} 맨 앞 — 지난 틱 동안 바뀐 것을 아바타 대기열에 넣는다(이 틱의 `Avatar.run` 에서 돈다) */
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

    /** 아바타가 실렸다(`ENTITY_INIT` 직후) — 지금 값을 «처음» 으로 한 번씩(설계 §9-C). 전역에서 온 값은 대상 칸 nil · 자기 값은 자기 UUID */
    public static void onAvatarInit(Avatar a) {
        for (Map.Entry<String, ServerDataModel.Entry> e : MODEL.all(a.owner).entrySet()) {
            boolean own = MODEL.hasOwn(a.owner, e.getKey());
            queueStateChanged(a, e.getKey(), lua(e.getValue()), LuaValue.NIL, own ? LuaValue.valueOf(a.owner.toString()) : LuaValue.NIL, true);
        }
    }

    /** `watch` 한 순간 — 그 대상의 **자기 값**을 «처음» 으로 */
    public static void onWatch(Avatar a, UUID subject) {
        for (Map.Entry<String, ServerDataModel.Entry> e : MODEL.own(subject).entrySet()) {
            queueStateChanged(a, e.getKey(), lua(e.getValue()), LuaValue.NIL, LuaValue.valueOf(subject.toString()), true);
        }
    }

    // ── 보내기

    private static UUID self() {
        Player p = Minecraft.getInstance().player;
        return p != null ? p.getUUID() : Minecraft.getInstance().getUser().getProfileId();
    }

    /** FSB 악수가 끝났다(처음 · 서버가 세션을 잃고 다시) — 서버에 «준비됨» 을 알린다 · 서버는 RESET + 전부로 답한다 */
    public static void sendHello() {
        FSB fsb = FSB.instance();
        if (!fsb.connected()) return;
        fsb.sendPacket(new CustomFSBPacket(self(), Protocol.CHANNEL_ID, C2S.hello(Protocol.VERSION)));
    }

    /** 호스트 아바타 → 서버 신호 · @return 보냈나(FSB 연결이 없으면 안 보낸다) */
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

    /** 스냅샷에 넣을 FSB 패킷 주인(아무 UUID 나 된다 — 가로채기는 id 만 본다) */
    public static UUID snapshotOwner() {
        return self();
    }

    // ── 진단

    public static int subjectCount() {
        return MODEL.subjectCount();
    }

    public static int globalCount() {
        return MODEL.globalCount();
    }
}
