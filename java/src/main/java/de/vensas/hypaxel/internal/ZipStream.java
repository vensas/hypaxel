package de.vensas.hypaxel.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Minimal streaming ZIP writer.
 *
 * <p>Every entry carries a data descriptor (general purpose flag bit 3), so sizes and checksums
 * never have to be patched into bytes already written: the archive is produced strictly front to
 * back and works with destinations that cannot seek. ZIP64 records are added when an entry, an
 * offset or the entry count passes the classic limits.
 *
 * <p>The writer performs no I/O. Compressed bytes accumulate in an output buffer that the caller
 * drains with {@link #output()}, {@link #outputLength()} and {@link #clearOutput()}.
 */
public final class ZipStream {
    static final long CLASSIC_SIZE_LIMIT = 0xFFFF_FFFFL;
    static final int CLASSIC_ENTRY_LIMIT = 0xFFFF;

    private static final int LOCAL_HEADER_SIGNATURE = 0x0403_4b50;
    private static final int DATA_DESCRIPTOR_SIGNATURE = 0x0807_4b50;
    private static final int CENTRAL_HEADER_SIGNATURE = 0x0201_4b50;
    private static final int END_SIGNATURE = 0x0605_4b50;
    private static final int ZIP64_END_SIGNATURE = 0x0606_4b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x0706_4b50;

    private static final int FLAG_DATA_DESCRIPTOR = 1 << 3;
    private static final int METHOD_DEFLATE = 8;
    private static final int VERSION_DEFAULT = 20;
    private static final int VERSION_ZIP64 = 45;
    // 1980-01-01 00:00:00 in MS-DOS format. A fixed time keeps the output reproducible.
    private static final int DOS_TIME = 0;
    private static final int DOS_DATE = (1 << 5) | 1;
    private static final int ZIP64_EXTRA_ID = 0x0001;

    private static final int MIN_FREE_FOR_DEFLATE = 16 * 1024;
    private static final int GROWTH = 64 * 1024;

    private record CentralRecord(byte[] name, int crc, long compressed, long uncompressed, long offset) {
    }

    private final Deflater deflater;
    private final CRC32 crc = new CRC32();
    private final List<CentralRecord> central = new ArrayList<>();
    private final long sizeLimit;
    private final int entryLimit;

    private byte[] out;
    private int outLength;
    private long position;

    private byte[] entryName;
    private long entryOffset;
    private long entryDataStart;
    private long entryUncompressed;

    public ZipStream(int compressionLevel, int capacity) {
        this(compressionLevel, capacity, CLASSIC_SIZE_LIMIT, CLASSIC_ENTRY_LIMIT);
    }

    /** Lower limits let tests reach the ZIP64 code paths without writing gigabytes. */
    ZipStream(int compressionLevel, int capacity, long sizeLimit, int entryLimit) {
        this.deflater = new Deflater(compressionLevel, true);
        this.out = new byte[Math.max(capacity, MIN_FREE_FOR_DEFLATE)];
        this.sizeLimit = sizeLimit;
        this.entryLimit = entryLimit;
    }

    public byte[] output() {
        return out;
    }

    public int outputLength() {
        return outLength;
    }

    public void clearOutput() {
        outLength = 0;
    }

    /** Total number of bytes produced so far, drained or not. */
    public long position() {
        return position;
    }

    public void startEntry(String name) {
        if (entryName != null) {
            throw new IllegalStateException("previous entry is not finished");
        }
        entryName = name.getBytes(StandardCharsets.US_ASCII);
        entryOffset = position;
        reserve(30 + entryName.length);
        putInt(LOCAL_HEADER_SIGNATURE);
        putShort(VERSION_DEFAULT);
        putShort(FLAG_DATA_DESCRIPTOR);
        putShort(METHOD_DEFLATE);
        putShort(DOS_TIME);
        putShort(DOS_DATE);
        putInt(0);
        putInt(0);
        putInt(0);
        putShort(entryName.length);
        putShort(0);
        putBytes(entryName);
        deflater.reset();
        crc.reset();
        entryUncompressed = 0;
        entryDataStart = position;
    }

    /** Compresses data into the current entry. */
    public void write(byte[] data, int offset, int length) {
        if (entryName == null) {
            throw new IllegalStateException("no open entry");
        }
        if (length == 0) {
            return;
        }
        crc.update(data, offset, length);
        entryUncompressed += length;
        deflater.setInput(data, offset, length);
        while (!deflater.needsInput()) {
            deflateIntoOutput();
        }
    }

    public void finishEntry() {
        if (entryName == null) {
            throw new IllegalStateException("no open entry");
        }
        deflater.finish();
        while (!deflater.finished()) {
            deflateIntoOutput();
        }
        long compressed = position - entryDataStart;
        int checksum = (int) crc.getValue();

        reserve(24);
        putInt(DATA_DESCRIPTOR_SIGNATURE);
        putInt(checksum);
        if (compressed > sizeLimit || entryUncompressed > sizeLimit) {
            putLong(compressed);
            putLong(entryUncompressed);
        } else {
            putInt((int) compressed);
            putInt((int) entryUncompressed);
        }
        central.add(new CentralRecord(entryName, checksum, compressed, entryUncompressed, entryOffset));
        entryName = null;
    }

    private void deflateIntoOutput() {
        if (out.length - outLength < MIN_FREE_FOR_DEFLATE) {
            grow(GROWTH);
        }
        int produced = deflater.deflate(out, outLength, out.length - outLength, Deflater.NO_FLUSH);
        outLength += produced;
        position += produced;
    }

    /** Writes the central directory. No entries may be added afterwards. */
    public void finish() {
        if (entryName != null) {
            throw new IllegalStateException("an entry is still open");
        }
        long directoryStart = position;
        for (CentralRecord record : central) {
            boolean zip64Sizes = record.compressed > sizeLimit || record.uncompressed > sizeLimit;
            boolean zip64Offset = record.offset > sizeLimit;
            int extraFields = (zip64Sizes ? 16 : 0) + (zip64Offset ? 8 : 0);
            int extraLength = extraFields == 0 ? 0 : 4 + extraFields;

            reserve(46 + record.name.length + extraLength);
            putInt(CENTRAL_HEADER_SIGNATURE);
            putShort(VERSION_ZIP64);
            putShort(extraLength == 0 ? VERSION_DEFAULT : VERSION_ZIP64);
            putShort(FLAG_DATA_DESCRIPTOR);
            putShort(METHOD_DEFLATE);
            putShort(DOS_TIME);
            putShort(DOS_DATE);
            putInt(record.crc);
            putInt(zip64Sizes ? -1 : (int) record.compressed);
            putInt(zip64Sizes ? -1 : (int) record.uncompressed);
            putShort(record.name.length);
            putShort(extraLength);
            putShort(0);
            putShort(0);
            putShort(0);
            putInt(0);
            putInt(zip64Offset ? -1 : (int) record.offset);
            putBytes(record.name);
            if (extraLength != 0) {
                putShort(ZIP64_EXTRA_ID);
                putShort(extraFields);
                if (zip64Sizes) {
                    putLong(record.uncompressed);
                    putLong(record.compressed);
                }
                if (zip64Offset) {
                    putLong(record.offset);
                }
            }
        }

        long directorySize = position - directoryStart;
        int count = central.size();
        boolean zip64 = count > entryLimit || directorySize > sizeLimit || directoryStart > sizeLimit;
        reserve(98);
        if (zip64) {
            long zip64EndOffset = position;
            putInt(ZIP64_END_SIGNATURE);
            putLong(44);
            putShort(VERSION_ZIP64);
            putShort(VERSION_ZIP64);
            putInt(0);
            putInt(0);
            putLong(count);
            putLong(count);
            putLong(directorySize);
            putLong(directoryStart);

            putInt(ZIP64_LOCATOR_SIGNATURE);
            putInt(0);
            putLong(zip64EndOffset);
            putInt(1);
        }

        putInt(END_SIGNATURE);
        putShort(0);
        putShort(0);
        putShort(count > entryLimit ? 0xFFFF : count);
        putShort(count > entryLimit ? 0xFFFF : count);
        putInt(directorySize > sizeLimit ? -1 : (int) directorySize);
        putInt(directoryStart > sizeLimit ? -1 : (int) directoryStart);
        putShort(0);
    }

    /** Releases the native compressor. The stream cannot be used afterwards. */
    public void end() {
        deflater.end();
    }

    private void reserve(int bytes) {
        if (out.length - outLength < bytes) {
            grow(bytes);
        }
    }

    private void grow(int extra) {
        long required = (long) outLength + extra;
        if (required > Integer.MAX_VALUE - 16) {
            throw new OutOfMemoryError("ZIP output buffer would exceed 2 GiB; drain it more often");
        }
        out = Arrays.copyOf(out, (int) Math.max(required, Math.min(out.length * 2L, Integer.MAX_VALUE - 16)));
    }

    private void putShort(int value) {
        out[outLength] = (byte) value;
        out[outLength + 1] = (byte) (value >>> 8);
        outLength += 2;
        position += 2;
    }

    private void putInt(int value) {
        putShort(value);
        putShort(value >>> 16);
    }

    private void putLong(long value) {
        putInt((int) value);
        putInt((int) (value >>> 32));
    }

    private void putBytes(byte[] bytes) {
        System.arraycopy(bytes, 0, out, outLength, bytes.length);
        outLength += bytes.length;
        position += bytes.length;
    }
}
