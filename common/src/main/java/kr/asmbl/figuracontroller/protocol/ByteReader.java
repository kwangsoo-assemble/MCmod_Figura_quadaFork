package kr.asmbl.figuracontroller.protocol;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Byte reader — throws {@link ProtocolException} on short or overflowing input (a broken packet never reaches an avatar) */
public final class ByteReader {
    private final byte[] b;
    private int pos;

    public ByteReader(byte[] b) {
        this.b = b;
    }

    public int remaining() {
        return b.length - pos;
    }

    public int readUByte() {
        if (pos >= b.length) throw new ProtocolException("truncated");
        return b[pos++] & 0xFF;
    }

    /** Unsigned varint — the fifth byte must be 0x07 or less (result fits Integer.MAX_VALUE) */
    public int readUVarInt() {
        int r = 0;
        for (int k = 0; k < 5; k++) {
            int c = readUByte();
            if (k == 4 && c > 0x07) throw new ProtocolException("uvarint overflow");
            r |= (c & 0x7F) << (7 * k);
            if (c < 0x80) return r;
        }
        throw new ProtocolException("uvarint overflow");
    }

    /** Zigzag varint 64 — the tenth byte must be 0x01 or less */
    public long readZigZagLong() {
        long r = 0;
        for (int k = 0; k < 10; k++) {
            int c = readUByte();
            if (k == 9 && c > 0x01) throw new ProtocolException("varint64 overflow");
            r |= (long) (c & 0x7F) << (7 * k);
            if (c < 0x80) return (r >>> 1) ^ -(r & 1);
        }
        throw new ProtocolException("varint64 overflow");
    }

    public long readLong() {
        if (remaining() < 8) throw new ProtocolException("truncated");
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[pos++] & 0xFF);
        return v;
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public UUID readUUID() {
        if (remaining() < 16) throw new ProtocolException("truncated");
        return new UUID(readLong(), readLong());
    }

    public String readString() {
        int n = readUVarInt();
        if (n > remaining()) throw new ProtocolException("truncated");
        String s = new String(b, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }
}
