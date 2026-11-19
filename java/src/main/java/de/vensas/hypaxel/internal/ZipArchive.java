package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.XlsxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * ZIP archive reader: central directory (with ZIP64), stored and deflated entries.
 *
 * <p>Entries are decompressed as a stream. The declared uncompressed size and CRC-32 are enforced,
 * so a damaged archive or one that lies about its sizes (a "zip bomb") is reported as an error
 * instead of producing unbounded output.
 */
public final class ZipArchive {
    private static final int LOCAL_HEADER_SIGNATURE = 0x0403_4b50;
    private static final int CENTRAL_HEADER_SIGNATURE = 0x0201_4b50;
    private static final int END_SIGNATURE = 0x0605_4b50;
    private static final int ZIP64_END_SIGNATURE = 0x0606_4b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x0706_4b50;

    private static final int END_LENGTH = 22;
    private static final int MAX_COMMENT = 0xFFFF;
    private static final long MAX_CENTRAL_DIRECTORY = 64L * 1024 * 1024;
    private static final int CENTRAL_HEADER_LENGTH = 46;
    private static final int LOCAL_HEADER_LENGTH = 30;

    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATE = 8;
    private static final int FLAG_ENCRYPTED = 1;
    private static final int INPUT_BUFFER = 64 * 1024;

    private static final byte[] OLE_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    /** One file in the archive. */
    public record Entry(String name, int method, int flags, int crc, long compressed, long uncompressed, long headerOffset) {
    }

    private final ReadSource source;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;

    private ZipArchive(ReadSource source, List<Entry> entries) {
        this.source = source;
        this.entries = entries;
        this.byName = new HashMap<>(entries.size() * 2);
        for (Entry entry : entries) {
            byName.putIfAbsent(entry.name, entry);
        }
    }

    private static XlsxException invalid(String message) {
        return new XlsxException(XlsxException.Kind.INVALID_FILE, message);
    }

    static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    static int le32(byte[] b, int at) {
        return le16(b, at) | le16(b, at + 2) << 16;
    }

    static long le64(byte[] b, int at) {
        return (le32(b, at) & 0xFFFF_FFFFL) | (long) le32(b, at + 4) << 32;
    }

    /** Reads the central directory. */
    public static ZipArchive read(ReadSource source) throws IOException {
        long length = source.size();
        if (length >= OLE_MAGIC.length) {
            byte[] magic = new byte[OLE_MAGIC.length];
            source.readFully(0, magic, 0, magic.length);
            if (java.util.Arrays.equals(magic, OLE_MAGIC)) {
                throw invalid("this is an OLE compound file: a legacy .xls workbook or a password-protected (encrypted) workbook");
            }
        }
        if (length < END_LENGTH) {
            throw invalid("the input is too small to be a ZIP archive");
        }

        int tailLength = (int) Math.min(length, END_LENGTH + MAX_COMMENT);
        long tailStart = length - tailLength;
        byte[] tail = new byte[tailLength];
        source.readFully(tailStart, tail, 0, tailLength);
        int end = -1;
        for (int i = tailLength - END_LENGTH; i >= 0; i--) {
            if (le32(tail, i) == END_SIGNATURE) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw invalid("no end of central directory record found; the input is not a ZIP archive");
        }
        long endOffset = tailStart + end;

        long count = le16(tail, end + 10);
        long directorySize = le32(tail, end + 12) & 0xFFFF_FFFFL;
        long directoryOffset = le32(tail, end + 16) & 0xFFFF_FFFFL;

        if ((count == 0xFFFF || directorySize == 0xFFFF_FFFFL || directoryOffset == 0xFFFF_FFFFL) && endOffset >= 20) {
            byte[] locator = new byte[20];
            source.readFully(endOffset - 20, locator, 0, 20);
            if (le32(locator, 0) == ZIP64_LOCATOR_SIGNATURE) {
                long recordOffset = le64(locator, 8);
                if (recordOffset < 0 || recordOffset > length - 56) {
                    throw invalid("the ZIP64 end of central directory record lies outside the archive");
                }
                byte[] record = new byte[56];
                source.readFully(recordOffset, record, 0, 56);
                if (le32(record, 0) != ZIP64_END_SIGNATURE) {
                    throw invalid("the ZIP64 end of central directory record is missing");
                }
                count = le64(record, 32);
                directorySize = le64(record, 40);
                directoryOffset = le64(record, 48);
            }
        }

        if (directorySize < 0 || directorySize > MAX_CENTRAL_DIRECTORY) {
            throw new XlsxException(XlsxException.Kind.LIMIT_EXCEEDED,
                    "the ZIP central directory is " + Long.toUnsignedString(directorySize) + " bytes; the limit is " + MAX_CENTRAL_DIRECTORY);
        }
        if (directoryOffset < 0 || directoryOffset + directorySize > endOffset) {
            throw invalid("the ZIP central directory lies outside the archive");
        }

        byte[] directory = new byte[(int) directorySize];
        source.readFully(directoryOffset, directory, 0, directory.length);

        List<Entry> entries = new ArrayList<>((int) Math.max(0, Math.min(count, directorySize / CENTRAL_HEADER_LENGTH)));
        int at = 0;
        while (at + CENTRAL_HEADER_LENGTH <= directory.length && le32(directory, at) == CENTRAL_HEADER_SIGNATURE) {
            int flags = le16(directory, at + 8);
            int method = le16(directory, at + 10);
            int crc = le32(directory, at + 16);
            long compressed = le32(directory, at + 20) & 0xFFFF_FFFFL;
            long uncompressed = le32(directory, at + 24) & 0xFFFF_FFFFL;
            int nameLength = le16(directory, at + 28);
            int extraLength = le16(directory, at + 30);
            int commentLength = le16(directory, at + 32);
            long headerOffset = le32(directory, at + 42) & 0xFFFF_FFFFL;
            int next = at + CENTRAL_HEADER_LENGTH + nameLength + extraLength + commentLength;
            if (next > directory.length) {
                throw invalid("a ZIP central directory entry is truncated");
            }
            int nameStart = at + CENTRAL_HEADER_LENGTH;
            String name = new String(directory, nameStart, nameLength, StandardCharsets.UTF_8).replace('\\', '/');

            int extra = nameStart + nameLength;
            int extraEnd = extra + extraLength;
            while (extra + 4 <= extraEnd) {
                int id = le16(directory, extra);
                int size = Math.min(le16(directory, extra + 2), extraEnd - extra - 4);
                if (id == 0x0001) {
                    int field = extra + 4;
                    int fieldEnd = field + size;
                    if (uncompressed == 0xFFFF_FFFFL && field + 8 <= fieldEnd) {
                        uncompressed = le64(directory, field);
                        field += 8;
                    }
                    if (compressed == 0xFFFF_FFFFL && field + 8 <= fieldEnd) {
                        compressed = le64(directory, field);
                        field += 8;
                    }
                    if (headerOffset == 0xFFFF_FFFFL && field + 8 <= fieldEnd) {
                        headerOffset = le64(directory, field);
                    }
                }
                extra += 4 + size;
            }

            entries.add(new Entry(name, method, flags, crc, compressed, uncompressed, headerOffset));
            at = next;
        }

        if (entries.isEmpty()) {
            throw invalid("the ZIP archive has no entries");
        }
        return new ZipArchive(source, entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Finds an entry by its part name, ignoring a leading {@code /} and, as a fallback, ASCII case. */
    public Entry find(String name) {
        int start = 0;
        while (start < name.length() && name.charAt(start) == '/') {
            start++;
        }
        String key = name.substring(start);
        Entry entry = byName.get(key);
        if (entry != null) {
            return entry;
        }
        for (Entry candidate : entries) {
            if (candidate.name.equalsIgnoreCase(key)) {
                return candidate;
            }
        }
        return null;
    }

    /** Opens an entry for streaming decompression. */
    public EntryInput open(Entry entry) throws IOException {
        if ((entry.flags & FLAG_ENCRYPTED) != 0) {
            throw new XlsxException(XlsxException.Kind.UNSUPPORTED, entry.name, "the ZIP entry is encrypted");
        }
        if (entry.method != METHOD_STORED && entry.method != METHOD_DEFLATE) {
            throw new XlsxException(XlsxException.Kind.UNSUPPORTED, entry.name, "the ZIP entry uses compression method " + entry.method);
        }
        if (entry.headerOffset < 0 || entry.compressed < 0 || entry.uncompressed < 0) {
            throw new XlsxException(XlsxException.Kind.MALFORMED, entry.name, "the ZIP entry has an invalid size or offset");
        }
        byte[] header = new byte[LOCAL_HEADER_LENGTH];
        source.readFully(entry.headerOffset, header, 0, header.length);
        if (le32(header, 0) != LOCAL_HEADER_SIGNATURE) {
            throw invalid("the local header of ZIP entry " + entry.name + " is missing");
        }
        long dataStart = entry.headerOffset + LOCAL_HEADER_LENGTH + le16(header, 26) + le16(header, 28);
        return new EntryInput(source, entry, dataStart);
    }

    /**
     * Decompressed contents of one entry. Fails if the entry produces more bytes than declared,
     * ends early, or its CRC-32 does not match.
     */
    public static final class EntryInput implements ByteInput {
        private final ReadSource source;
        private final Entry entry;
        private final CRC32 crc = new CRC32();
        private Inflater inflater;
        private byte[] input;
        private long position;
        private long compressedLeft;
        private long produced;
        private boolean done;

        EntryInput(ReadSource source, Entry entry, long dataStart) {
            this.source = source;
            this.entry = entry;
            this.position = dataStart;
            this.compressedLeft = entry.compressed;
            if (entry.method == METHOD_DEFLATE) {
                inflater = new Inflater(true);
                input = new byte[(int) Math.min(INPUT_BUFFER, Math.max(1, entry.compressed))];
            }
        }

        public String name() {
            return entry.name;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (done || length == 0) {
                return done ? -1 : 0;
            }
            long remaining = entry.uncompressed - produced;
            // Ask for one byte more than declared so that oversized output is detected.
            int limit = (int) Math.min(length, remaining + 1);
            int n = inflater == null ? readStored(buffer, offset, limit) : inflate(buffer, offset, limit);
            if (n > remaining) {
                throw corrupt("the entry is larger than its declared size");
            }
            if (n <= 0) {
                close();
                if (produced != entry.uncompressed) {
                    throw corrupt("the entry is shorter than its declared size");
                }
                if ((int) crc.getValue() != entry.crc) {
                    throw corrupt("CRC-32 mismatch");
                }
                return -1;
            }
            produced += n;
            crc.update(buffer, offset, n);
            return n;
        }

        private int readStored(byte[] buffer, int offset, int length) throws IOException {
            if (compressedLeft == 0) {
                return -1;
            }
            int n = source.read(position, buffer, offset, (int) Math.min(length, compressedLeft));
            if (n <= 0) {
                throw corrupt("the entry is truncated");
            }
            position += n;
            compressedLeft -= n;
            return n;
        }

        private int inflate(byte[] buffer, int offset, int length) throws IOException {
            try {
                int stalls = 0;
                while (true) {
                    int n = inflater.inflate(buffer, offset, length);
                    if (n > 0) {
                        return n;
                    }
                    if (inflater.finished()) {
                        return -1;
                    }
                    if (inflater.needsDictionary()) {
                        throw corrupt("invalid deflate data (a preset dictionary is required)");
                    }
                    if (!inflater.needsInput()) {
                        if (++stalls > 16) {
                            throw corrupt("invalid deflate data (no progress)");
                        }
                        continue;
                    }
                    if (compressedLeft == 0) {
                        throw corrupt("invalid deflate data (the compressed data ends early)");
                    }
                    int read = source.read(position, input, 0, (int) Math.min(input.length, compressedLeft));
                    if (read <= 0) {
                        throw corrupt("the entry is truncated");
                    }
                    position += read;
                    compressedLeft -= read;
                    inflater.setInput(input, 0, read);
                }
            } catch (DataFormatException e) {
                throw corrupt("invalid deflate data (" + e.getMessage() + ")");
            }
        }

        private XlsxException corrupt(String message) {
            close();
            return new XlsxException(XlsxException.Kind.MALFORMED, entry.name, message);
        }

        @Override
        public void close() {
            done = true;
            if (inflater != null) {
                inflater.end();
                inflater = null;
            }
        }
    }
}
