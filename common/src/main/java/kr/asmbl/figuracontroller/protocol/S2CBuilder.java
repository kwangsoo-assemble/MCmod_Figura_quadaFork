package kr.asmbl.figuracontroller.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static kr.asmbl.figuracontroller.protocol.Protocol.*;

/**
 * Packs the op stream for one client into packet bodies — values come as <b>pre-encoded bytes</b> ({@link Values#encode})
 * (the server encodes a changed value once per subject and appends the same bytes for every receiver).
 * <p>
 * Splitting rules (must match the reference implementation byte for byte):
 * <ul>
 *   <li>Past the body limit ({@link Protocol#MAX_BODY}) the next op starts a new packet.</li>
 *   <li>{@link #subject} · {@link #global} are not written at once but in front of the next op that needs a subject — and again at the start of a new packet
 *       (a subject lives within one packet).</li>
 *   <li>If {@link #full} does not fit, as many entries as fit go into FULL (total = all entries) and the rest follow as FULL_CONT.</li>
 *   <li>A single op (including its subject) larger than the limit throws {@link ProtocolException}.</li>
 * </ul>
 */
public final class S2CBuilder {
    private final int max;
    private final List<byte[]> packets = new ArrayList<>();
    private final ByteWriter cur = new ByteWriter(256);
    private final ByteWriter tmp = new ByteWriter(256);
    private byte[] ctx;
    private boolean ctxEmitted;

    public S2CBuilder() {
        this(MAX_BODY);
    }

    public S2CBuilder(int maxBody) {
        this.max = maxBody;
    }

    public S2CBuilder name(int id, String name) {
        tmp.reset();
        tmp.writeUVarInt(OP_NAME);
        tmp.writeUVarInt(id);
        tmp.writeString(name);
        free(tmp.toByteArray());
        return this;
    }

    public S2CBuilder reset() {
        tmp.reset();
        tmp.writeUVarInt(OP_RESET);
        free(tmp.toByteArray());
        return this;
    }

    public S2CBuilder subject(UUID uuid) {
        tmp.reset();
        tmp.writeUVarInt(OP_SUBJECT);
        tmp.writeUUID(uuid);
        ctx = tmp.toByteArray();
        ctxEmitted = false;
        return this;
    }

    public S2CBuilder global() {
        tmp.reset();
        tmp.writeUVarInt(OP_GLOBAL);
        ctx = tmp.toByteArray();
        ctxEmitted = false;
        return this;
    }

    public S2CBuilder set(int nameId, byte[] value) {
        tmp.reset();
        tmp.writeUVarInt(OP_SET);
        tmp.writeUVarInt(nameId);
        tmp.writeBytes(value);
        ctxOp(tmp.toByteArray());
        return this;
    }

    public S2CBuilder unset(int nameId) {
        tmp.reset();
        tmp.writeUVarInt(OP_UNSET);
        tmp.writeUVarInt(nameId);
        ctxOp(tmp.toByteArray());
        return this;
    }

    public S2CBuilder drop() {
        tmp.reset();
        tmp.writeUVarInt(OP_DROP);
        ctxOp(tmp.toByteArray());
        return this;
    }

    public S2CBuilder signal(int nameId, byte[] value) {
        tmp.reset();
        tmp.writeUVarInt(OP_SIGNAL);
        tmp.writeUVarInt(nameId);
        tmp.writeBytes(value);
        ctxOp(tmp.toByteArray());
        return this;
    }

    /** Everything of this subject — {@code values[i]} (pre-encoded bytes) is the value of {@code nameIds[i]} */
    public S2CBuilder full(int[] nameIds, byte[][] values) {
        if (ctx == null) throw new ProtocolException("FULL without subject");
        if (nameIds.length != values.length) throw new IllegalArgumentException("nameIds/values length mismatch");
        int n = nameIds.length;
        byte[][] enc = new byte[n][];
        for (int i = 0; i < n; i++) {
            tmp.reset();
            tmp.writeUVarInt(nameIds[i]);
            tmp.writeBytes(values[i]);
            enc[i] = tmp.toByteArray();
        }
        int headLen = ByteWriter.uvarIntSize(OP_FULL) + ByteWriter.uvarIntSize(n);
        int contLen = ByteWriter.uvarIntSize(OP_FULL_CONT);
        int start = 0;
        boolean first = true;
        while (true) {
            int hl = first ? headLen : contLen;
            int avail = max - cur.size() - (ctxEmitted ? 0 : ctx.length);
            int k = 0;
            int total = 0;
            while (start + k < n) {
                int e = enc[start + k].length;
                if (hl + ByteWriter.uvarIntSize(k + 1) + total + e <= avail) {
                    k++;
                    total += e;
                } else {
                    break;
                }
            }
            boolean done = start + k == n;
            boolean fits = k > 0 || (first && n == 0 && hl + ByteWriter.uvarIntSize(0) <= avail);
            if (!fits) {
                if (cur.size() > 0) {
                    flush();
                    continue;
                }
                throw new ProtocolException("a FULL entry exceeds the body limit");
            }
            tmp.reset();
            if (first) {
                tmp.writeUVarInt(OP_FULL);
                tmp.writeUVarInt(n);
            } else {
                tmp.writeUVarInt(OP_FULL_CONT);
            }
            tmp.writeUVarInt(k);
            for (int j = start; j < start + k; j++) tmp.writeBytes(enc[j]);
            ctxOp(tmp.toByteArray());
            start += k;
            first = false;
            if (done) return this;
        }
    }

    /** Nothing added yet (choosing a subject alone does not count) */
    public boolean isEmpty() {
        return packets.isEmpty() && cur.size() == 0;
    }

    /** Packet bodies — an empty list when nothing was added */
    public List<byte[]> build() {
        flush();
        return new ArrayList<>(packets);
    }

    private void flush() {
        if (cur.size() > 0) {
            packets.add(cur.toByteArray());
            cur.reset();
        }
        ctxEmitted = false;
    }

    private void free(byte[] b) {
        if (b.length > max) throw new ProtocolException("op exceeds the body limit");
        if (cur.size() + b.length > max) flush();
        cur.writeBytes(b);
    }

    private void ctxOp(byte[] b) {
        if (ctx == null) throw new ProtocolException("op requires a subject");
        int need = b.length + (ctxEmitted ? 0 : ctx.length);
        if (cur.size() + need > max && cur.size() > 0) {
            flush();
            need = b.length + ctx.length;
        }
        if (need > max) throw new ProtocolException("op exceeds the body limit");
        if (!ctxEmitted) {
            cur.writeBytes(ctx);
            ctxEmitted = true;
        }
        cur.writeBytes(b);
    }
}
