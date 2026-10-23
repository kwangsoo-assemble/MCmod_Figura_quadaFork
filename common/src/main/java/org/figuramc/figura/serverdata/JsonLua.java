package org.figuramc.figura.serverdata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * figuracontroller 값(Gson {@link JsonElement}) ↔ Lua 값.
 * <ul>
 *   <li>JSON → Lua: null → nil · 수 · 글 · 불 · 배열 → 1 부터 · 객체 → 글 키. 만든 표는 **저장소가 갖고 아바타끼리 나눠 본다**
 *       (줄 때는 {@link org.figuramc.figura.lua.ReadOnlyLuaView} 로 감싼다 — 고칠 수 없다)</li>
 *   <li>Lua → JSON (아바타 → 서버 신호): 1..n 이 빈틈 없이 차 있고 다른 키가 없으면 배열 · 아니면 객체(키는 글로) · 빈 표는 객체 ·
 *       정수 값은 INT · 아니면 DOUBLE · 함수 · userdata 는 오류 · 깊이 32 · 순환은 오류</li>
 * </ul>
 */
public final class JsonLua {
    public static final int MAX_DEPTH = 32;

    private JsonLua() {}

    public static LuaValue toLua(JsonElement e) {
        if (e == null || e.isJsonNull()) return LuaValue.NIL;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return LuaValue.valueOf(p.getAsBoolean());
            if (p.isString()) return LuaValue.valueOf(p.getAsString());
            Number n = p.getAsNumber();
            if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
                long l = n.longValue();
                return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? LuaValue.valueOf((int) l) : LuaValue.valueOf((double) l);
            }
            return LuaValue.valueOf(n.doubleValue());
        }
        LuaTable t = new LuaTable();
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) t.rawset(i + 1, toLua(a.get(i)));
            return t;
        }
        for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) t.rawset(en.getKey(), toLua(en.getValue()));
        return t;
    }

    public static JsonElement toJson(LuaValue v) {
        return toJson(v, 0, new IdentityHashMap<>());
    }

    private static JsonElement toJson(LuaValue v, int depth, IdentityHashMap<LuaValue, Boolean> seen) {
        if (v == null || v.isnil()) return JsonNull.INSTANCE;
        if (v.isboolean()) return new JsonPrimitive(v.toboolean());
        if (v.type() == LuaValue.TNUMBER) {
            if (v.isint()) return new JsonPrimitive((long) v.toint());
            double d = v.todouble();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0E15) return new JsonPrimitive((long) d);
            return new JsonPrimitive(d);
        }
        if (v.isstring()) return new JsonPrimitive(v.tojstring());
        if (!v.istable()) throw new LuaError("Cannot send this value to the server: " + v.typename());
        if (depth >= MAX_DEPTH) throw new LuaError("Table nested too deep (" + MAX_DEPTH + ")");
        if (seen.put(v, Boolean.TRUE) != null) throw new LuaError("Table contains itself (cycle)");
        try {
            int n = v.length();
            int count = 0;
            LuaValue k = LuaValue.NIL;
            boolean array = n > 0;
            while (true) {
                Varargs next = v.next(k);
                if ((k = next.arg1()).isnil()) break;
                count++;
                if (!(k.isint() && k.toint() >= 1 && k.toint() <= n)) array = false;
            }
            if (array && count == n) {
                JsonArray a = new JsonArray(n);
                for (int i = 1; i <= n; i++) a.add(toJson(v.get(i), depth + 1, seen));
                return a;
            }
            JsonObject o = new JsonObject();
            k = LuaValue.NIL;
            while (true) {
                Varargs next = v.next(k);
                if ((k = next.arg1()).isnil()) break;
                o.add(k.tojstring(), toJson(next.arg(2), depth + 1, seen));
            }
            return o;
        } finally {
            seen.remove(v);
        }
    }
}
