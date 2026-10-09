package kr.asmbl.figuracontroller.protocol;

import com.google.gson.JsonElement;

import static kr.asmbl.figuracontroller.protocol.Protocol.*;

/** Client → server — one packet = one op (rare, so names travel as text without the dictionary) */
public final class C2S {
    private C2S() {}

    public sealed interface Op permits Hello, Signal {}

    /** Sent once by the core after the FSB handshake — the server treats the client as ready only after this */
    public record Hello(int version) implements Op {}

    /** Avatar → server signal (host only — the server filters by {@code avatarOwner == sender}) */
    public record Signal(String name, JsonElement data) implements Op {}

    public static byte[] hello(int version) {
        ByteWriter w = new ByteWriter(8);
        w.writeUVarInt(C2S_HELLO);
        w.writeUVarInt(version);
        return w.toByteArray();
    }

    public static byte[] signal(String name, JsonElement data) {
        ByteWriter w = new ByteWriter();
        w.writeUVarInt(C2S_SIGNAL);
        w.writeString(name);
        Values.write(w, data);
        return w.toByteArray();
    }

    public static byte[] write(Op op) {
        if (op instanceof Hello h) return hello(h.version());
        Signal s = (Signal) op;
        return signal(s.name(), s.data());
    }

    public static Op read(byte[] body) {
        ByteReader r = new ByteReader(body);
        int op = r.readUVarInt();
        Op out;
        if (op == C2S_HELLO) {
            out = new Hello(r.readUVarInt());
        } else if (op == C2S_SIGNAL) {
            String name = r.readString();
            out = new Signal(name, Values.read(r));
        } else {
            throw new ProtocolException("unknown C2S op " + op);
        }
        if (r.remaining() != 0) throw new ProtocolException("trailing bytes after C2S op");
        return out;
    }
}
