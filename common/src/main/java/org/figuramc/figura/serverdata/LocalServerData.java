package org.figuramc.figura.serverdata;

import com.google.gson.JsonElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.backend2.FSB;
import org.figuramc.figura.compat.FlashbackCompat;
import org.figuramc.figura.gui.FiguraToast;
import org.figuramc.figura.utils.FiguraText;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 싱글 로컬 모드 — 서버(FiguraController · FSB) 없이 싱글에서 상태 · 신호 · 핑을 돌린다.
 * <ul>
 *   <li>켜짐 = 내 PC 에 내장 서버가 떠 있다(싱글 · LAN 을 연 호스트) · 리플레이가 아니다 · FSB 에 안 붙었다 — {@link #active()} 한 곳에서만 가른다.
 *       멀티 · 리플레이 · LAN 손님에서는 꺼져 있고, 꺼져 있으면 이 클래스는 아무것도 안 한다.</li>
 *   <li>상태 · 신호: {@link LocalBodies} 가 서버와 같은 규약 몸을 만들어 {@link ServerDataStore#applyLocal} 로 넣는다 — 그 뒤(이벤트 · {@code initial} · 나누기)는 서버 때와 같은 경로.</li>
 *   <li>핑: {@link #offerPing} — 라이브 서버와 같은 한도({@link PingLimiter}) · 넘으면 서버와 같은 토스트를 띄우고 버린다.</li>
 *   <li>아바타 → 서버 신호: 받을 서버가 없으니 채팅에 찍는다. {@link #setQuiet}({@code /fc local quiet on})는 이것과 {@code /fc} 성공 응답을 같이 끈다 · 재시작하면 다시 켜짐.</li>
 * </ul>
 * 설계: 하네스 {@code projects/figura_controller/design/싱글_로컬서버/plan.md}.
 */
public final class LocalServerData {
    public static final String PREFIX = "§7[싱글 fc] ";

    private static final LocalBodies BODIES = new LocalBodies();
    private static final PingLimiter PINGS = new PingLimiter(PingLimiter.LIVE_RATE_LIMIT, PingLimiter.LIVE_SIZE_LIMIT);
    private static boolean quiet;

    private LocalServerData() {}

    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.hasSingleplayerServer()) return false;
        // ⚠ Flashback 재생도 내장 서버(ReplayServer)를 띄운다 — isInReplay 는 «여는 중» 부터 참
        if (FlashbackCompat.isInReplay()) return false;
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && server.getClass().getName().contains("flashback")) return false;
        return !FSB.instance().connected();
    }

    // ── 상태 · 신호 (명령이 부른다 — 오류는 그대로 올린다)

    public static void set(UUID subject, String name, JsonElement value, int expireTicks) {
        apply(BODIES.set(subject, name, value, expireTicks));
    }

    public static void unset(UUID subject, String name) {
        apply(BODIES.unset(subject, name));
    }

    /** 그 대상의 자기 값 전부(전역이면 전역 전부)를 지운다 · @return 지운 수 */
    public static int clear(UUID subject) {
        List<String> names = new ArrayList<>(subject == null ? ServerDataStore.MODEL.all(null).keySet() : ServerDataStore.MODEL.own(subject).keySet());
        if (!names.isEmpty()) apply(BODIES.unsetAll(subject, names));
        return names.size();
    }

    public static void signal(UUID subject, String name, JsonElement data) {
        apply(BODIES.signal(subject, name, data));
    }

    /** 남은 만료 틱 — 없으면 -1 */
    public static int expiresIn(UUID subject, String name) {
        return BODIES.expiresIn(subject, name);
    }

    private static void apply(List<byte[]> bodies) {
        for (byte[] body : bodies) ServerDataStore.applyLocal(body);
    }

    // ── 틱 · 비우기

    /** {@code AvatarManager.tickLoadedAvatars} 의 {@code flushEvents} 바로 앞 — 만료로 지운 것이 같은 틱에 실린다 */
    public static void tick() {
        // ⚠ 일시정지면 세지 않는다 — 싱글 일시정지는 서버 틱도 멈춘다(서버에서는 그동안 만료가 안 준다)
        if (!active() || Minecraft.getInstance().isPaused()) return;
        PINGS.tick();
        try {
            apply(BODIES.tick());
        } catch (RuntimeException e) {
            FiguraMod.sendChatMessage(Component.literal(PREFIX + "§c만료 처리 실패: " + e.getMessage()));
        }
    }

    /** {@link ServerDataStore#clear} 가 부른다 — 저장소와 번호표 · 만료표 · 핑 창은 함께 비워야 한다 */
    static void reset() {
        BODIES.reset();
        PINGS.reset();
    }

    // ── 핑

    /** @return 돌려도 되나 — 거절이면 서버와 같은 토스트를 띄운다(그 핑은 버린다 · 서버도 본인에게조차 안 돌린다) */
    public static boolean offerPing(int size) {
        PingLimiter.Result r = PINGS.offer(size);
        if (r == PingLimiter.Result.OK) return true;
        String key = r == PingLimiter.Result.RATE ? "backend.ping_rate" : "backend.ping_size";
        FiguraToast.sendToast(FiguraText.of("backend.warning"), FiguraText.of(key), FiguraToast.ToastType.ERROR);
        return false;
    }

    public static PingLimiter pings() {
        return PINGS;
    }

    // ── 아바타 → 서버 신호

    public static void onAvatarSignal(String name, JsonElement data) {
        if (!quiet) FiguraMod.sendChatMessage(Component.literal(PREFIX + "신호 ← " + name + " " + data));
    }

    public static boolean quiet() {
        return quiet;
    }

    public static void setQuiet(boolean q) {
        quiet = q;
    }
}
