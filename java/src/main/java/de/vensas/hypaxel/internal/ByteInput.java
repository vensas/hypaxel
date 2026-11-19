package de.vensas.hypaxel.internal;

import java.io.IOException;

/** A forward-only byte stream, such as the decompressed contents of a ZIP entry. */
public interface ByteInput {
    /** Reads up to {@code length} bytes (at least one if {@code length > 0}); returns -1 at the end. */
    int read(byte[] buffer, int offset, int length) throws IOException;

    /** Releases native resources. Further reads return -1. */
    void close();

    /** Input over a byte array. */
    static ByteInput of(byte[] bytes) {
        return new ByteInput() {
            private int position;

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (position >= bytes.length) {
                    return -1;
                }
                int n = Math.min(length, bytes.length - position);
                System.arraycopy(bytes, position, buffer, offset, n);
                position += n;
                return n;
            }

            @Override
            public void close() {
                position = bytes.length;
            }
        };
    }
}
