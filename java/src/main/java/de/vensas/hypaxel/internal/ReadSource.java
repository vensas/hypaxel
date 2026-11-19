package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.XlsxException;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Random-access bytes of a workbook. Positional reads let several entry streams (for example
 * worksheets read on different threads) share one source.
 */
public abstract class ReadSource implements Closeable {
    /** Returns the total length in bytes. */
    public abstract long size() throws IOException;

    /** Reads up to {@code length} bytes at {@code position}; returns -1 at the end of the source. */
    public abstract int read(long position, byte[] buffer, int offset, int length) throws IOException;

    /** Reads exactly {@code length} bytes or fails because the archive is truncated. */
    public final void readFully(long position, byte[] buffer, int offset, int length) throws IOException {
        while (length > 0) {
            int n = read(position, buffer, offset, length);
            if (n <= 0) {
                throw new XlsxException(XlsxException.Kind.INVALID_FILE, "the ZIP archive is truncated");
            }
            position += n;
            offset += n;
            length -= n;
        }
    }

    public static ReadSource of(byte[] bytes) {
        return new Bytes(Objects.requireNonNull(bytes, "bytes"));
    }

    public static ReadSource of(Path file) throws IOException {
        return new Channel(FileChannel.open(file, StandardOpenOption.READ));
    }

    public static ReadSource of(SeekableByteChannel channel) {
        return new Channel(Objects.requireNonNull(channel, "channel"));
    }

    private static final class Bytes extends ReadSource {
        private final byte[] bytes;

        Bytes(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            if (position >= bytes.length) {
                return -1;
            }
            int n = (int) Math.min(length, bytes.length - position);
            System.arraycopy(bytes, (int) position, buffer, offset, n);
            return n;
        }

        @Override
        public void close() {
        }
    }

    private static final class Channel extends ReadSource {
        private final SeekableByteChannel channel;
        private final FileChannel file;

        Channel(SeekableByteChannel channel) {
            this.channel = channel;
            this.file = channel instanceof FileChannel f ? f : null;
        }

        @Override
        public long size() throws IOException {
            return channel.size();
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) throws IOException {
            ByteBuffer target = ByteBuffer.wrap(buffer, offset, length);
            if (file != null) {
                return file.read(target, position);
            }
            // A generic channel has a single position, so a read and the seek before it must not interleave.
            synchronized (channel) {
                channel.position(position);
                return channel.read(target);
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
