package kr.asmbl.figuracontroller.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.regex.Pattern;

import static kr.asmbl.figuracontroller.protocol.Protocol.*;

/**
 * Value = 1 type byte + body. Server and client both handle it as Gson {@code JsonElement} (Paper and Minecraft both ship Gson).
 * <p>
 * Number kind — decided by <b>textual form</b>: Integer · Long · Short · Byte are INT · Double · Float are DOUBLE ·
 * anything else (a {@code LazilyParsedNumber} read from JSON · BigInteger · BigDecimal) is INT when {@code toString()} is «sign + digits» and fits 64 bits, otherwise DOUBLE.
 * ⇒ JSON «1» is INT · «1.0» is DOUBLE (same result as {@code json.loads} in the reference implementation).
 * On read INT → {@code JsonPrimitive(Long)} · DOUBLE → {@code JsonPrimitive(Double)} — writing it again gives the same bytes.
 */
public final class Values {
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");

    private Values() {}

    public static byte[] encode(JsonElement v) {
        ByteWriter w = new ByteWriter();
        write(w, v);
        return w.toByteArray();
    }

    public static void write(ByteWriter w, JsonElement v) {
        write(w, v, 0);
    }

    private static void write(ByteWriter w, JsonElement v, int depth) {
        if (v == null || v.isJsonNull()) {
            w.writeByte(T_NULL);
            return;
        }
        if (v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isBoolean()) {
                w.writeByte(p.getAsBoolean() ? T_TRUE : T_FALSE);
            } else if (p.isString()) {
                w.writeByte(T_STRING);
                w.writeString(p.getAsString());
            } else {
                Number n = p.getAsNumber();
                Long l = asLong(n);
                if (l != null) {
                    w.writeByte(T_INT);
                    w.writeZigZagLong(l);
                } else {
                    w.writeByte(T_DOUBLE);
                    w.writeDouble(n.doubleValue());
                }
            }
            return;
        }
        if (depth >= MAX_DEPTH) throw new ProtocolException("nesting too deep");
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            w.writeByte(T_ARRAY);
            w.writeUVarInt(a.size());
            for (JsonElement e : a) write(w, e, depth + 1);
            return;
        }
        JsonObject o = v.getAsJsonObject();
        w.writeByte(T_OBJECT);
        w.writeUVarInt(o.size());
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            w.writeString(e.getKey());
            write(w, e.getValue(), depth + 1);
        }
    }

    /** The value when it can travel as INT, otherwise null (DOUBLE) */
    static Long asLong(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) return n.longValue();
        if (n instanceof Double || n instanceof Float) return null;
        String s = n.toString();
        if (!INTEGER.matcher(s).matches()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static JsonElement read(ByteReader r) {
        return read(r, 0);
    }

    private static JsonElement read(ByteReader r, int depth) {
        int t = r.readUByte();
        switch (t) {
            case T_NULL:
                return JsonNull.INSTANCE;
            case T_FALSE:
                return new JsonPrimitive(false);
            case T_TRUE:
                return new JsonPrimitive(true);
            case T_INT:
                return new JsonPrimitive(r.readZigZagLong());
            case T_DOUBLE:
                return new JsonPrimitive(r.readDouble());
            case T_STRING:
                return new JsonPrimitive(r.readString());
            case T_ARRAY: {
                if (depth >= MAX_DEPTH) throw new ProtocolException("nesting too deep");
                int n = count(r);
                JsonArray a = new JsonArray(n);
                for (int i = 0; i < n; i++) a.add(read(r, depth + 1));
                return a;
            }
            case T_OBJECT: {
                if (depth >= MAX_DEPTH) throw new ProtocolException("nesting too deep");
                int n = count(r);
                JsonObject o = new JsonObject();
                for (int i = 0; i < n; i++) {
                    String k = r.readString();
                    o.add(k, read(r, depth + 1));
                }
                return o;
            }
            default:
                throw new ProtocolException("unknown value type " + t);
        }
    }

    /** Element count — every element takes at least one byte, so a count above the remaining bytes means a broken packet */
    static int count(ByteReader r) {
        int n = r.readUVarInt();
        if (n > r.remaining()) throw new ProtocolException("count exceeds remaining bytes");
        return n;
    }
}
