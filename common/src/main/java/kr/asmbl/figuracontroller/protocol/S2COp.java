package kr.asmbl.figuracontroller.protocol;

import com.google.gson.JsonElement;

import java.util.List;
import java.util.UUID;

import static kr.asmbl.figuracontroller.protocol.Protocol.*;

/**
 * One server → client op (as read). {@link #write} writes the same bytes back — for tests (read then write is identical) and Flashback snapshots.
 * When sending, the server uses {@link S2CBuilder} (pre-encoded value bytes) so each value is encoded only once.
 */
public sealed interface S2COp {
    int op();

    void write(ByteWriter w);

    record Entry(int nameId, JsonElement value) {}

    record NameOp(int id, String name) implements S2COp {
        public int op() { return OP_NAME; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_NAME);
            w.writeUVarInt(id);
            w.writeString(name);
        }
    }

    record SubjectOp(UUID uuid) implements S2COp {
        public int op() { return OP_SUBJECT; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_SUBJECT);
            w.writeUUID(uuid);
        }
    }

    record GlobalOp() implements S2COp {
        public int op() { return OP_GLOBAL; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_GLOBAL);
        }
    }

    record SetOp(int nameId, JsonElement value) implements S2COp {
        public int op() { return OP_SET; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_SET);
            w.writeUVarInt(nameId);
            Values.write(w, value);
        }
    }

    record UnsetOp(int nameId) implements S2COp {
        public int op() { return OP_UNSET; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_UNSET);
            w.writeUVarInt(nameId);
        }
    }

    /** Replaces this subject with {@code total} entries — if {@code entries} has fewer, the rest follow in {@link FullContOp} */
    record FullOp(int total, List<Entry> entries) implements S2COp {
        public int op() { return OP_FULL; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_FULL);
            w.writeUVarInt(total);
            writeEntries(w, entries);
        }
    }

    record FullContOp(List<Entry> entries) implements S2COp {
        public int op() { return OP_FULL_CONT; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_FULL_CONT);
            writeEntries(w, entries);
        }
    }

    record DropOp() implements S2COp {
        public int op() { return OP_DROP; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_DROP);
        }
    }

    record SignalOp(int nameId, JsonElement value) implements S2COp {
        public int op() { return OP_SIGNAL; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_SIGNAL);
            w.writeUVarInt(nameId);
            Values.write(w, value);
        }
    }

    record ResetOp() implements S2COp {
        public int op() { return OP_RESET; }

        public void write(ByteWriter w) {
            w.writeUVarInt(OP_RESET);
        }
    }

    private static void writeEntries(ByteWriter w, List<Entry> entries) {
        w.writeUVarInt(entries.size());
        for (Entry e : entries) {
            w.writeUVarInt(e.nameId());
            Values.write(w, e.value());
        }
    }
}
