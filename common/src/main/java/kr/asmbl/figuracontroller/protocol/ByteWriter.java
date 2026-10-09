package kr.asmbl.figuracontroller.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Growable byte buffer — no Netty, so the same code runs in the core and the plugin */
public final class ByteWriter {
    private byte[] buf;
    private int len;

    public ByteWriter() {
        this(64);
    }

    public ByteWriter(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    public int size() {
        return len;
    }

    public void reset() {
        len = 0;
    }

    private void ensure(int more) {
        if (len + more > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + more));
    }

    public void writeByte(int b) {
        ensure(1);
        buf[len++] = (byte) b;
    }

    public void writeBytes(byte[] b) {
        writeBytes(b, 0, b.length);
    }

    public void writeBytes(byte[] b, int off, int n) {
        ensure(n);
        System.arraycopy(b, off, buf, len, n);
        len += n;
    }

    /** Unsigned varint (LEB128) — 0 to Integer.MAX_VALUE only */
    public void writeUVarInt(int v) {
        if (v < 0) throw new ProtocolException("uvarint out of range: " + v);
        while ((v & ~0x7F) != 0) {
            writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        writeByte(v);
    }

    /** Zigzag then varint — any 64-bit integer */
    public void writeZigZagLong(long v) {
        long z = (v << 1) ^ (v >> 63);
        while ((z & ~0x7FL) != 0) {
            writeByte((int) ((z & 0x7F) | 0x80));
            z >>>= 7;
        }
        writeByte((int) z);
    }

    public void writeLong(long v) {
        for (int i = 7; i >= 0; i--) writeByte((int) (v >>> (i * 8)));
    }

    /** 8 bytes, big-endian */
    public void writeDouble(double d) {
        writeLong(Double.doubleToRawLongBits(d));
    }

    public void writeUUID(UUID u) {
        writeLong(u.getMostSignificantBits());
        writeLong(u.getLeastSignificantBits());
    }

    /** uvarint byte count + UTF-8 */
    public void writeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeUVarInt(b.length);
        writeBytes(b);
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, len);
    }

    /** Number of bytes {@link #writeUVarInt} will write */
    public static int uvarIntSize(int v) {
        if (v < 0) throw new ProtocolException("uvarint out of range: " + v);
        int n = 1;
        while ((v & ~0x7F) != 0) {
            n++;
            v >>>= 7;
        }
        return n;
    }
}
