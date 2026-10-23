package org.figuramc.figura.serverdata;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

/**
 * 싱글 로컬 모드 — 명령 글 → 값.
 * <p>
 * ★ FiguraController 플러그인 {@code kr.asmbl.figuracontroller.core.ValueParse} 의 사본이다(원본은 플러그인 저장소).
 * 싱글에서 친 {@code /fc} 가 서버에서 같은 값으로 읽혀야 한다 — 하네스 {@code projects/figura_controller/tests/verify_core_serverdata.py} ④ 가
 * 같은 입력 묶음에 두 쪽이 같은 답을 내는지 잰다. 원본을 고치면 여기도 고친다.
 */
public final class LocalValueParse {
    private static final TypeAdapter<JsonElement> ADAPTER = new Gson().getAdapter(JsonElement.class);

    private LocalValueParse() {}

    /**
     * **엄격한 JSON** 이면 그 값(`true` · `3` · `1.5` · `"글"` · `[1,2]` · `{"x":1}` · `null`) · 아니면 **글 그대로**(`RATH4R`).
     * ⚠ Gson 의 느슨한 해석은 따옴표 없는 낱말도 글로 읽어 «JSON 인지» 를 못 가린다 — 그래서 엄격 모드로 읽고 끝까지 먹었는지 본다.
     */
    public static JsonElement jsonOrString(String raw) {
        try {
            JsonReader r = new JsonReader(new StringReader(raw));
            r.setStrictness(Strictness.STRICT);
            JsonElement v = ADAPTER.read(r);
            if (r.peek() == JsonToken.END_DOCUMENT) return v;
        } catch (IOException | RuntimeException ignored) {
            // JSON 이 아니다
        }
        return new JsonPrimitive(raw);
    }
}
