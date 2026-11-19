package de.vensas.hypaxel.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A growable byte array, reused to collect decoded UTF-8 text without allocating per value. */
public final class ByteBuilder {
    byte[] data;
    int length;

    public ByteBuilder(int capacity) {
        data = new byte[Math.max(16, capacity)];
    }

    public int length() {
        return length;
    }

    public void clear() {
        length = 0;
    }

    public void truncate(int newLength) {
        length = newLength;
    }

    void ensure(int extra) {
        int needed = length + extra;
        if (needed < 0) {
            throw new OutOfMemoryError("text buffer too large");
        }
        if (needed > data.length) {
            data = Arrays.copyOf(data, Math.max(needed, Math.min(Integer.MAX_VALUE - 8, data.length * 2)));
        }
    }

    public void append(byte b) {
        if (length == data.length) {
            ensure(1);
        }
        data[length++] = b;
    }

    public void append(byte[] bytes, int offset, int count) {
        ensure(count);
        System.arraycopy(bytes, offset, data, length, count);
        length += count;
    }

    public String toString(int start, int end) {
        return new String(data, start, end - start, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return toString(0, length);
    }
}
