package org.figuramc.figura.serverdata;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import kr.asmbl.figuracontroller.protocol.Protocol;
import kr.asmbl.figuracontroller.protocol.S2CBuilder;
import kr.asmbl.figuracontroller.protocol.Values;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 싱글 로컬 모드 — 상태 · 신호를 FC 규약 몸(서버가 보내는 것과 같은 바이트 꼴)으로 만든다. Minecraft 를 안 쓴다(하네스가 오프라인으로 잰다).
 * <ul>
 *   <li>이름 번호표: 처음 쓰는 이름만 그 몸 앞에 {@code NAME} op 를 붙인다. 저장소의 이름 사전은 RESET · 월드를 나갈 때까지 살아서
 *       ⚠ 저장소를 비울 때 {@link #reset} 도 같이 불러야 한다 — 안 그러면 다음 월드에서 «모르는 번호» 로 버려진다.</li>
 *   <li>검사: 이름 = FC {@code StateStore.validateName}(빈 이름 · UTF-8 256 B 넘음) · 값 = 인코딩 뒤 {@link Protocol#MAX_VALUE} 넘음 — 서버가 거절할 것을 여기서도 거절한다.</li>
 *   <li>만료: {@code set} 마다 새로 정한다(깃발이 없으면 지운다 · 같은 값이어도 시간만 바뀐다) — FC {@code StateStore.set} 이 엔트리를 통째로 바꾸는 것과 같다.
 *       {@link #tick} 이 다 된 것을 {@code UNSET} 몸으로 돌려준다.</li>
 * </ul>
 * 대상 {@code null} = 전역.
 */
public final class LocalBodies {
    public static final int MAX_NAME = 256;

    private record Key(UUID subject, String name) {}

    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<Key, Integer> expiries = new LinkedHashMap<>();

    public List<byte[]> set(UUID subject, String name, JsonElement value, int expireTicks) {
        validateName(name);
        byte[] enc = encode(value);
        S2CBuilder b = new S2CBuilder();
        int id = id(b, name);
        target(b, subject).set(id, enc);
        Key k = new Key(subject, name);
        if (expireTicks > 0) expiries.put(k, expireTicks);
        else expiries.remove(k);
        return b.build();
    }

    public List<byte[]> unset(UUID subject, String name) {
        return unsetAll(subject, List.of(name));
    }

    public List<byte[]> unsetAll(UUID subject, Collection<String> names) {
        S2CBuilder b = new S2CBuilder();
        for (String name : names) {
            validateName(name);
            int id = id(b, name);
            target(b, subject).unset(id);
            expiries.remove(new Key(subject, name));
        }
        return b.build();
    }

    public List<byte[]> signal(UUID subject, String name, JsonElement data) {
        validateName(name);
        byte[] enc = encode(data);
        S2CBuilder b = new S2CBuilder();
        int id = id(b, name);
        target(b, subject).signal(id, enc);
        return b.build();
    }

    /** 틱마다 한 번 — 만료가 다 된 것을 지우는 몸(없으면 빈 목록) */
    public List<byte[]> tick() {
        if (expiries.isEmpty()) return List.of();
        S2CBuilder b = new S2CBuilder();
        Iterator<Map.Entry<Key, Integer>> it = expiries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left > 0) {
                e.setValue(left);
                continue;
            }
            it.remove();
            int id = id(b, e.getKey().name());
            target(b, e.getKey().subject()).unset(id);
        }
        return b.build();
    }

    /** 남은 만료 틱 — 없으면 -1 */
    public int expiresIn(UUID subject, String name) {
        Integer left = expiries.get(new Key(subject, name));
        return left == null ? -1 : left;
    }

    public void reset() {
        ids.clear();
        expiries.clear();
    }

    public static void validateName(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("이름이 비었다");
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME) throw new IllegalArgumentException("이름이 너무 길다(" + MAX_NAME + " 바이트)");
    }

    private static byte[] encode(JsonElement value) {
        byte[] enc;
        try {
            enc = Values.encode(value == null ? JsonNull.INSTANCE : value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("값을 실을 수 없다: " + e.getMessage(), e);
        }
        if (enc.length > Protocol.MAX_VALUE) throw new IllegalArgumentException("값이 너무 크다(" + enc.length + " > " + Protocol.MAX_VALUE + " 바이트)");
        return enc;
    }

    private int id(S2CBuilder b, String name) {
        Integer id = ids.get(name);
        if (id != null) return id;
        int fresh = ids.size();
        ids.put(name, fresh);
        b.name(fresh, name);
        return fresh;
    }

    private static S2CBuilder target(S2CBuilder b, UUID subject) {
        return subject == null ? b.global() : b.subject(subject);
    }
}
