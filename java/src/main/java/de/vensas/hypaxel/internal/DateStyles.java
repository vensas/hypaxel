package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.DateKind;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Which cell formats ({@code cellXfs} entries) show numbers as dates, times or durations. */
public final class DateStyles {
    public static final DateStyles NONE = new DateStyles(new byte[0]);

    private static final DateKind[] KINDS = DateKind.values();
    private static final byte[] NUM_FMT = Ascii.bytes("numFmt");
    private static final byte[] NUM_FMT_ID = Ascii.bytes("numFmtId");
    private static final byte[] FORMAT_CODE = Ascii.bytes("formatCode");
    private static final byte[] CELL_XFS = Ascii.bytes("cellXfs");
    private static final byte[] XF = Ascii.bytes("xf");
    private static final byte[] STYLE_SHEET = Ascii.bytes("styleSheet");
    private static final byte[] NUM_FMTS = Ascii.bytes("numFmts");

    /** Per cell format: 0 for numbers, otherwise {@link DateKind#ordinal()} + 1. */
    private final byte[] kinds;

    private DateStyles(byte[] kinds) {
        this.kinds = kinds;
    }

    /** Returns {@code DateKind.ordinal() + 1} for a date format, 0 otherwise. */
    public int kindCode(int style) {
        return style >= 0 && style < kinds.length ? kinds[style] : 0;
    }

    public DateKind kind(int style) {
        int code = kindCode(style);
        return code == 0 ? null : KINDS[code - 1];
    }

    /** Classifies a built-in number format id. */
    public static DateKind builtinKind(int id) {
        if ((id >= 14 && id <= 17) || (id >= 27 && id <= 31) || (id >= 34 && id <= 36) || (id >= 50 && id <= 54) || id == 57 || id == 58) {
            return DateKind.DATE;
        }
        if (id == 22) {
            return DateKind.DATE_TIME;
        }
        if ((id >= 18 && id <= 21) || id == 32 || id == 33 || id == 45 || id == 47 || id == 55 || id == 56) {
            return DateKind.TIME;
        }
        if (id == 46) {
            return DateKind.DURATION;
        }
        return null;
    }

    /**
     * Classifies a format code such as {@code yyyy-mm-dd}, {@code [h]:mm} or {@code #,##0.00 "days"}.
     *
     * <p>Only the first section (positive numbers) is considered. Quoted text, escaped characters,
     * padding/fill characters and bracketed colors, conditions and locales are ignored.
     */
    public static DateKind formatKind(String code) {
        boolean date = false;
        boolean time = false;
        boolean minuteOrMonth = false;
        boolean elapsed = false;
        int n = code.length();
        int i = 0;
        loop:
        while (i < n) {
            char c = code.charAt(i);
            switch (c) {
                case ';':
                    break loop;
                case '"':
                    i++;
                    while (i < n && code.charAt(i) != '"') {
                        i++;
                    }
                    break;
                case '\\':
                case '_':
                case '*':
                    i++;
                    break;
                case '[': {
                    int close = code.indexOf(']', i);
                    if (close < 0) {
                        close = n;
                    }
                    boolean all = close > i + 1;
                    for (int j = i + 1; j < close; j++) {
                        char l = Character.toLowerCase(code.charAt(j));
                        all &= l == 'h' || l == 'm' || l == 's';
                    }
                    elapsed |= all;
                    i = close;
                    break;
                }
                case 'y', 'Y', 'd', 'D', 'e':
                    date = true;
                    break;
                case 'h', 'H', 's', 'S':
                    time = true;
                    break;
                case 'm', 'M':
                    minuteOrMonth = true;
                    break;
                default:
                    if ((c == 'g' || c == 'G') && code.regionMatches(true, i, "general", 0, 7)) {
                        i += 6;
                    }
                    break;
            }
            i++;
        }
        // `m` is a month next to dates and a minute next to hours or seconds; alone it is a month.
        date |= minuteOrMonth && !time;
        if (elapsed) {
            return DateKind.DURATION;
        } else if (date && time) {
            return DateKind.DATE_TIME;
        } else if (date) {
            return DateKind.DATE;
        } else if (time) {
            return DateKind.TIME;
        }
        return null;
    }

    public static DateStyles parse(XmlReader xml) throws IOException {
        Map<Integer, DateKind> custom = new HashMap<>();
        byte[] kinds = new byte[16];
        int count = 0;
        boolean inCellXfs = false;
        while (true) {
            int event = xml.next();
            if (event == XmlReader.EOF) {
                break;
            }
            if (event == XmlReader.END && xml.nameIs(CELL_XFS)) {
                inCellXfs = false;
                continue;
            }
            if (event != XmlReader.START && event != XmlReader.EMPTY) {
                continue;
            }
            boolean start = event == XmlReader.START;
            if (xml.nameIs(NUM_FMT)) {
                int id = Ascii.parseUnsigned(xml.attribute(NUM_FMT_ID));
                String code = xml.attribute(FORMAT_CODE);
                if (id >= 0 && code != null) {
                    custom.put(id, formatKind(code));
                }
            } else if (xml.nameIs(CELL_XFS)) {
                inCellXfs |= start;
            } else if (inCellXfs && xml.nameIs(XF)) {
                int id = Math.max(0, Ascii.parseUnsigned(xml.attribute(NUM_FMT_ID)));
                DateKind kind = custom.containsKey(id) ? custom.get(id) : builtinKind(id);
                if (count == kinds.length) {
                    kinds = Arrays.copyOf(kinds, count * 2);
                }
                kinds[count++] = (byte) (kind == null ? 0 : kind.ordinal() + 1);
            }
            if (start && !xml.nameIs(CELL_XFS) && !xml.nameIs(STYLE_SHEET) && !xml.nameIs(NUM_FMTS)) {
                xml.skipElement();
            }
        }
        return new DateStyles(Arrays.copyOf(kinds, count));
    }
}
