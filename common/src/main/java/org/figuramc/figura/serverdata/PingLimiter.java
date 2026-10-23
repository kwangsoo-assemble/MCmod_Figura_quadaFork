package org.figuramc.figura.serverdata;

/**
 * 싱글 로컬 모드 — 핑 한도. FSB 서버와 **같은 비교식**으로 센다(경계 한 칸까지).
 * <ul>
 *   <li>창: 20 틱 고정 — FSB {@code FiguraUserManager.tick}(카운터가 20 이면 비우고 0 → 그 뒤 +1).</li>
 *   <li>거절: {@code 보낸 수 > 한도} 면 RATE · {@code 보낸 바이트 + 이번 크기 > 한도} 면 SIZE — FSB {@code C2SPingPacketHandler.handle}.
 *       횟수는 «넘은 뒤에» 막으므로 창마다 **한도 + 1 번째까지** 통과한다.</li>
 *   <li>거절된 핑은 세지 않는다(서버도 안 센다).</li>
 * </ul>
 * 한도 값은 라이브 서버 설정의 사본이다 — {@link #LIVE_RATE_LIMIT} · {@link #LIVE_SIZE_LIMIT}.
 */
public final class PingLimiter {
    public enum Result { OK, RATE, SIZE }

    public static final int WINDOW_TICKS = 20;
    /**
     * ⚠ 라이브 RaD 서버 {@code plugins/FiguraFSB/config.json} 의 {@code pingsRateLimit} · {@code pingsSizeLimit} 사본(FSB 코드 기본값 32 · 1024 가 아니다).
     * 서버 설정을 바꾸면 여기도 바꾼다 — 하네스 {@code verify_core_serverdata.py} ④ 가 라이브 파일이 있는 기계에서 같은지 잰다.
     */
    public static final int LIVE_RATE_LIMIT = 64;
    public static final int LIVE_SIZE_LIMIT = 131072;

    private final int rateLimit;
    private final int sizeLimit;
    private int pings;
    private int bytes;
    private int ticks;

    public PingLimiter(int rateLimit, int sizeLimit) {
        this.rateLimit = rateLimit;
        this.sizeLimit = sizeLimit;
    }

    public Result offer(int size) {
        if (pings > rateLimit) return Result.RATE;
        if (bytes + size > sizeLimit) return Result.SIZE;
        pings++;
        bytes += size;
        return Result.OK;
    }

    /** 틱마다 한 번 */
    public void tick() {
        if (ticks == WINDOW_TICKS) {
            pings = 0;
            bytes = 0;
            ticks = 0;
        }
        ticks++;
    }

    public void reset() {
        pings = 0;
        bytes = 0;
        ticks = 0;
    }

    public int rateLimit() {
        return rateLimit;
    }

    public int sizeLimit() {
        return sizeLimit;
    }

    public int pings() {
        return pings;
    }

    public int bytes() {
        return bytes;
    }
}
