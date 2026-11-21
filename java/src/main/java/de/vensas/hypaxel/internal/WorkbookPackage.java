package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.ExcelTable;
import de.vensas.hypaxel.SheetInfo;
import de.vensas.hypaxel.SheetKind;
import de.vensas.hypaxel.SheetVisibility;
import de.vensas.hypaxel.XlsxException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The structure of an XLSX package: its parts, sheets, date system and cell formats. */
public final class WorkbookPackage {
    /** Most tables read from one worksheet. */
    static final int MAX_TABLES = 4096;

    private static final String NOT_A_WORKBOOK = "the package has no workbook part; it may be a Word or PowerPoint file";

    private static final byte[] RELATIONSHIP = Ascii.bytes("Relationship");
    private static final byte[] ID = Ascii.bytes("Id");
    private static final byte[] TYPE = Ascii.bytes("Type");
    private static final byte[] TARGET = Ascii.bytes("Target");
    private static final byte[] TARGET_MODE = Ascii.bytes("TargetMode");
    private static final byte[] WORKBOOK = Ascii.bytes("workbook");
    private static final byte[] WORKBOOK_PR = Ascii.bytes("workbookPr");
    private static final byte[] DATE_1904 = Ascii.bytes("date1904");
    private static final byte[] SHEET = Ascii.bytes("sheet");
    private static final byte[] NAME = Ascii.bytes("name");
    private static final byte[] STATE = Ascii.bytes("state");
    private static final byte[] R_ID = Ascii.bytes("id");

    record Relationship(String id, String type, String target) {
        /**
         * Matches the last segment of the relationship type, so that both Transitional and
         * Strict Office Open XML namespaces are accepted.
         */
        boolean is(String kind) {
            return type.substring(type.lastIndexOf('/') + 1).equals(kind);
        }
    }

    private final ZipArchive archive;
    private final List<SheetInfo> sheets;
    private final List<String> sheetParts;
    private final DateStyles styles;
    private final String sharedStringsPart;
    private final boolean date1904;

    private WorkbookPackage(ZipArchive archive, List<SheetInfo> sheets, List<String> sheetParts, DateStyles styles,
            String sharedStringsPart, boolean date1904) {
        this.archive = archive;
        this.sheets = sheets;
        this.sheetParts = sheetParts;
        this.styles = styles;
        this.sharedStringsPart = sharedStringsPart;
        this.date1904 = date1904;
    }

    public static WorkbookPackage read(ReadSource source) throws IOException {
        ZipArchive archive = ZipArchive.read(source);

        String workbookPart = "xl/workbook.xml";
        List<Relationship> rootRels = relationships(archive, "_rels/.rels");
        if (rootRels != null) {
            for (Relationship r : rootRels) {
                if (r.is("officeDocument")) {
                    workbookPart = r.target;
                    break;
                }
            }
        }
        if (archive.find(workbookPart) == null) {
            throw new XlsxException(XlsxException.Kind.INVALID_FILE, NOT_A_WORKBOOK);
        }

        List<Relationship> rels = relationships(archive, relsPath(workbookPart));
        if (rels == null) {
            rels = List.of();
        }

        List<SheetInfo> sheets = new ArrayList<>();
        List<String> sheetParts = new ArrayList<>();
        boolean date1904 = readWorkbook(archive, workbookPart, rels, sheets, sheetParts);

        DateStyles styles = DateStyles.NONE;
        String sharedStringsPart = null;
        for (Relationship r : rels) {
            if (styles == DateStyles.NONE && r.is("styles") && archive.find(r.target) != null) {
                XmlReader xml = open(archive, r.target);
                try {
                    styles = DateStyles.parse(xml);
                } finally {
                    xml.close();
                }
            } else if (sharedStringsPart == null && r.is("sharedStrings") && archive.find(r.target) != null) {
                sharedStringsPart = r.target;
            }
        }
        return new WorkbookPackage(archive, Collections.unmodifiableList(sheets), sheetParts, styles, sharedStringsPart, date1904);
    }

    public List<SheetInfo> sheets() {
        return sheets;
    }

    public boolean date1904() {
        return date1904;
    }

    public DateStyles styles() {
        return styles;
    }

    public SharedStringTable readSharedStrings(long maxBytes) throws IOException {
        if (sharedStringsPart == null) {
            return SharedStringTable.EMPTY;
        }
        XmlReader xml = open(archive, sharedStringsPart);
        try {
            return SharedStringTable.parse(xml, maxBytes);
        } finally {
            xml.close();
        }
    }

    /** Opens the worksheet part of the sheet at {@code index}. */
    public XmlReader openSheet(int index) throws IOException {
        SheetInfo info = sheets.get(index);
        if (info.kind() == SheetKind.CHARTSHEET) {
            throw new XlsxException(XlsxException.Kind.UNSUPPORTED, "\"" + info.name() + "\" is a chart sheet and has no cells");
        }
        String part = sheetParts.get(index);
        if (part == null) {
            throw new XlsxException(XlsxException.Kind.MALFORMED, "xl/workbook.xml",
                    "sheet \"" + info.name() + "\" has no relationship to a worksheet part");
        }
        if (archive.find(part) == null) {
            throw new XlsxException(XlsxException.Kind.MALFORMED, part, "the worksheet part of sheet \"" + info.name() + "\" is missing");
        }
        return open(archive, part);
    }

    /** Reads the tables of the sheet at {@code index} from their own parts, in relationship order. */
    public List<ExcelTable> readTables(int index) throws IOException {
        String part = sheetParts.get(index);
        if (part == null) {
            return List.of();
        }
        List<Relationship> rels = relationships(archive, relsPath(part));
        if (rels == null) {
            return List.of();
        }
        List<ExcelTable> tables = new ArrayList<>();
        for (Relationship r : rels) {
            if (!r.is("table")) {
                continue;
            }
            if (tables.size() >= MAX_TABLES) {
                throw new XlsxException(XlsxException.Kind.LIMIT_EXCEEDED, part + " has more than " + MAX_TABLES + " tables");
            }
            XmlReader xml = open(archive, r.target);
            try {
                tables.add(TableParser.parse(xml));
            } finally {
                xml.close();
            }
        }
        return Collections.unmodifiableList(tables);
    }

    private static XmlReader open(ZipArchive archive, String part) throws IOException {
        ZipArchive.Entry entry = archive.find(part);
        if (entry == null) {
            throw new XlsxException(XlsxException.Kind.MALFORMED, part, "the part is missing");
        }
        return new XmlReader(archive.open(entry), part);
    }

    static List<Relationship> relationships(ZipArchive archive, String part) throws IOException {
        if (archive.find(part) == null) {
            return null;
        }
        String base = sourceDirectory(part);
        List<Relationship> rels = new ArrayList<>();
        XmlReader xml = open(archive, part);
        try {
            while (true) {
                int event = xml.next();
                if (event == XmlReader.EOF) {
                    break;
                }
                if ((event != XmlReader.START && event != XmlReader.EMPTY) || !xml.nameIs(RELATIONSHIP)) {
                    continue;
                }
                String mode = xml.attribute(TARGET_MODE);
                if (mode != null && mode.equalsIgnoreCase("External")) {
                    continue;
                }
                String id = xml.attribute(ID);
                String type = xml.attribute(TYPE);
                String target = xml.attribute(TARGET);
                if (id != null && type != null && target != null) {
                    rels.add(new Relationship(id, type, resolve(base, target)));
                }
            }
        } finally {
            xml.close();
        }
        return rels;
    }

    /** Reads the date system and the sheets; returns whether the 1904 date system is used. */
    private static boolean readWorkbook(ZipArchive archive, String part, List<Relationship> rels, List<SheetInfo> sheets,
            List<String> sheetParts) throws IOException {
        XmlReader xml = open(archive, part);
        boolean date1904 = false;
        boolean root = true;
        try {
            while (true) {
                int event = xml.next();
                if (event == XmlReader.EOF) {
                    break;
                }
                if (event != XmlReader.START && event != XmlReader.EMPTY) {
                    continue;
                }
                if (root) {
                    root = false;
                    if (!xml.nameIs(WORKBOOK)) {
                        throw new XlsxException(XlsxException.Kind.INVALID_FILE, NOT_A_WORKBOOK);
                    }
                }
                if (xml.nameIs(WORKBOOK_PR)) {
                    String value = xml.attribute(DATE_1904);
                    date1904 = "1".equals(value) || "true".equals(value);
                } else if (xml.nameIs(SHEET)) {
                    String name = xml.attribute(NAME);
                    if (name == null) {
                        throw xml.malformed("a sheet has no name");
                    }
                    String state = xml.attribute(STATE);
                    SheetVisibility visibility = "hidden".equals(state) ? SheetVisibility.HIDDEN
                            : "veryHidden".equals(state) ? SheetVisibility.VERY_HIDDEN : SheetVisibility.VISIBLE;
                    String relId = xml.attribute(R_ID);
                    Relationship rel = null;
                    for (Relationship r : rels) {
                        if (r.id.equals(relId)) {
                            rel = r;
                            break;
                        }
                    }
                    SheetKind kind = rel == null || rel.is("worksheet") ? SheetKind.WORKSHEET
                            : rel.is("chartsheet") ? SheetKind.CHARTSHEET : SheetKind.OTHER;
                    sheets.add(new SheetInfo(name, visibility, kind));
                    sheetParts.add(rel == null ? null : rel.target);
                }
            }
        } finally {
            xml.close();
        }
        return date1904;
    }

    /**
     * The directory of a relationship part's source, e.g. {@code xl/} for
     * {@code xl/_rels/workbook.xml.rels}: targets are relative to the source part, which lives one
     * level above {@code _rels/}.
     */
    static String sourceDirectory(String relsPart) {
        int slash = relsPart.lastIndexOf('/');
        String dir = slash < 0 ? "" : relsPart.substring(0, slash);
        return dir.endsWith("_rels") ? dir.substring(0, dir.length() - "_rels".length()) : dir;
    }

    static String relsPath(String part) {
        int slash = part.lastIndexOf('/');
        return slash < 0 ? "_rels/" + part + ".rels" : part.substring(0, slash) + "/_rels/" + part.substring(slash + 1) + ".rels";
    }

    /** Resolves a relationship target against the source part's directory. */
    static String resolve(String base, String target) {
        String joined = target.startsWith("/") ? target.substring(1) : base + target;
        List<String> parts = new ArrayList<>();
        for (String segment : joined.split("[/\\\\]")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else {
                parts.add(segment);
            }
        }
        return String.join("/", parts);
    }
}
