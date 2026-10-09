package kr.asmbl.figuracontroller.protocol;

/**
 * figuracontroller:v1 — constants of the binary protocol between the server plugin and the forked core.
 * <p>
 * The original of this package ({@code protocol}) lives in the FiguraController plugin repository; the core keeps a verbatim copy
 * (same arrangement as FSB {@code server-common} — a checker compares both sides).
 * When changing it, update the reference implementation as well and regenerate the test vectors.
 * <p>
 * Carried in: the {@code id} of an FSB {@code CustomFSBPacket} ({@code figura:ping/server}) = {@link #CHANNEL_ID}, body = the op stream below.
 */
public final class Protocol {
    public static final String CHANNEL = "figuracontroller:v1";
    /** FSB carries {@code String.hashCode()}, not the name text */
    public static final int CHANNEL_ID = CHANNEL.hashCode();
    /** Protocol version the client announces in HELLO */
    public static final int VERSION = 1;
    /** Body limit per packet — leaves headroom under the FSB limit (32,746 B) */
    public static final int MAX_BODY = 30_000;
    /** Encoded size limit of a single value — {@code set} rejects anything larger (room for the op header and subject) */
    public static final int MAX_VALUE = 29_900;
    /** Nesting limit for bundles (ARRAY · OBJECT) */
    public static final int MAX_DEPTH = 32;

    // ── server → client ops
    /** uvarint id · string — name dictionary (per client session, outlives a packet) */
    public static final int OP_NAME = 1;
    /** uuid — subject of the following ops (lives within one packet) */
    public static final int OP_SUBJECT = 2;
    /** subject = global */
    public static final int OP_GLOBAL = 3;
    /** uvarint nameId · value */
    public static final int OP_SET = 4;
    /** uvarint nameId */
    public static final int OP_UNSET = 5;
    /** uvarint total · uvarint n · (uvarint nameId · value) × n — replaces this subject with {@code total} entries */
    public static final int OP_FULL = 6;
    /** removes this subject from the client store */
    public static final int OP_DROP = 7;
    /** uvarint nameId · value — one-shot signal */
    public static final int OP_SIGNAL = 8;
    /** clears the whole client store and name dictionary (head of a Flashback snapshot) */
    public static final int OP_RESET = 9;
    /** uvarint n · (uvarint nameId · value) × n — rest of the preceding FULL (when it spans packets) */
    public static final int OP_FULL_CONT = 10;

    // ── client → server ops (one packet = one op)
    /** uvarint protocol version */
    public static final int C2S_HELLO = 1;
    /** string name · value */
    public static final int C2S_SIGNAL = 2;

    // ── value types
    public static final int T_NULL = 0;
    public static final int T_FALSE = 1;
    public static final int T_TRUE = 2;
    /** zigzag varint 64 */
    public static final int T_INT = 3;
    /** 8 bytes, big-endian */
    public static final int T_DOUBLE = 4;
    /** uvarint byte count + UTF-8 */
    public static final int T_STRING = 5;
    /** uvarint n + n values */
    public static final int T_ARRAY = 6;
    /** uvarint n + n (string key · value) */
    public static final int T_OBJECT = 7;

    private Protocol() {}

    /** ops that need a subject (SUBJECT · GLOBAL) first */
    public static boolean needsContext(int op) {
        return op == OP_SET || op == OP_UNSET || op == OP_FULL || op == OP_DROP || op == OP_SIGNAL || op == OP_FULL_CONT;
    }
}
