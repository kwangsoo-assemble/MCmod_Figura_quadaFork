package kr.asmbl.figuracontroller.protocol;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

import static kr.asmbl.figuracontroller.protocol.Protocol.*;

/**
 * Server → client packet body → op list. Checks shape only (op needing a subject without one · unknown op · broken lengths · FULL n &gt; total).
 * <b>State</b> such as the name dictionary or joining FULL parts is handled by the receiver (the core store) — it outlives a packet.
 * A broken packet throws {@link ProtocolException} and returns none of the ops read before it (no half-applied packets).
 */
public final class S2CReader {
    private S2CReader() {}

    public static List<S2COp> read(byte[] body) {
        ByteReader r = new ByteReader(body);
        List<S2COp> out = new ArrayList<>();
        boolean ctx = false;
        while (r.remaining() > 0) {
            int op = r.readUVarInt();
            if (needsContext(op) && !ctx) throw new ProtocolException("op without subject: " + op);
            switch (op) {
                case OP_NAME -> {
                    int id = r.readUVarInt();
                    out.add(new S2COp.NameOp(id, r.readString()));
                }
                case OP_SUBJECT -> {
                    out.add(new S2COp.SubjectOp(r.readUUID()));
                    ctx = true;
                }
                case OP_GLOBAL -> {
                    out.add(new S2COp.GlobalOp());
                    ctx = true;
                }
                case OP_SET -> {
                    int id = r.readUVarInt();
                    out.add(new S2COp.SetOp(id, Values.read(r)));
                }
                case OP_UNSET -> out.add(new S2COp.UnsetOp(r.readUVarInt()));
                case OP_FULL -> {
                    int total = r.readUVarInt();
                    List<S2COp.Entry> e = entries(r);
                    if (e.size() > total) throw new ProtocolException("FULL n > total");
                    out.add(new S2COp.FullOp(total, e));
                }
                case OP_FULL_CONT -> out.add(new S2COp.FullContOp(entries(r)));
                case OP_DROP -> out.add(new S2COp.DropOp());
                case OP_SIGNAL -> {
                    int id = r.readUVarInt();
                    out.add(new S2COp.SignalOp(id, Values.read(r)));
                }
                case OP_RESET -> out.add(new S2COp.ResetOp());
                default -> throw new ProtocolException("unknown op " + op);
            }
        }
        return out;
    }

    private static List<S2COp.Entry> entries(ByteReader r) {
        int n = Values.count(r);
        List<S2COp.Entry> e = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = r.readUVarInt();
            JsonElement v = Values.read(r);
            e.add(new S2COp.Entry(id, v));
        }
        return e;
    }
}
