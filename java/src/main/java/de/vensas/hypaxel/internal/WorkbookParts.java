package de.vensas.hypaxel.internal;

import java.util.List;

/** Package-level parts written when the workbook completes. */
final class WorkbookParts {
    static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final String NS_PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String CT_PREFIX = "application/vnd.openxmlformats-officedocument.spreadsheetml.";

    private WorkbookParts() {
    }

    static void workbook(XmlBuffer x, List<SheetInfo> sheets, boolean usesFormulas, boolean date1904) {
        x.raw(DECLARATION).raw("<workbook xmlns=\"").raw(NS_MAIN).raw("\" xmlns:r=\"").raw(NS_REL).raw("\">");
        if (date1904) {
            x.raw("<workbookPr date1904=\"1\"/>");
        }
        int activeTab = 0;
        for (int i = 0; i < sheets.size(); i++) {
            if (!sheets.get(i).hidden) {
                activeTab = i;
                break;
            }
        }
        x.raw("<bookViews><workbookView activeTab=\"").putLong(activeTab).raw("\"/></bookViews><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            SheetInfo sheet = sheets.get(i);
            x.raw("<sheet name=\"").putText(sheet.name).raw("\" sheetId=\"").putLong(i + 1).raw('"');
            if (sheet.hidden) {
                x.raw(" state=\"hidden\"");
            }
            x.raw(" r:id=\"rId").putLong(i + 1).raw("\"/>");
        }
        x.raw("</sheets>");

        boolean wroteNames = false;
        for (int i = 0; i < sheets.size(); i++) {
            SheetInfo sheet = sheets.get(i);
            int[] range = sheet.autoFilter;
            if (range == null) {
                continue;
            }
            if (!wroteNames) {
                x.raw("<definedNames>");
                wroteNames = true;
            }
            x.raw("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").putLong(i).raw("\" hidden=\"1\">'");
            x.putText(sheet.name.replace("'", "''")).raw("'!$");
            x.putColumnName(range[1]).raw('$').putLong(range[0]).raw(":$");
            x.putColumnName(range[3]).raw('$').putLong(range[2]).raw("</definedName>");
        }
        if (wroteNames) {
            x.raw("</definedNames>");
        }
        x.raw(usesFormulas ? "<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>" : "<calcPr calcId=\"191029\"/></workbook>");
    }

    static void workbookRels(XmlBuffer x, int sheetCount, boolean sharedStrings) {
        x.raw(DECLARATION).raw("<Relationships xmlns=\"").raw(NS_PACKAGE_REL).raw("\">");
        for (int i = 1; i <= sheetCount; i++) {
            x.raw("<Relationship Id=\"rId").putLong(i).raw("\" Type=\"").raw(NS_REL);
            x.raw("/worksheet\" Target=\"worksheets/sheet").putLong(i).raw(".xml\"/>");
        }
        x.raw("<Relationship Id=\"rId").putLong(sheetCount + 1).raw("\" Type=\"").raw(NS_REL);
        x.raw("/styles\" Target=\"styles.xml\"/>");
        if (sharedStrings) {
            x.raw("<Relationship Id=\"rId").putLong(sheetCount + 2).raw("\" Type=\"").raw(NS_REL);
            x.raw("/sharedStrings\" Target=\"sharedStrings.xml\"/>");
        }
        x.raw("</Relationships>");
    }

    static void contentTypes(XmlBuffer x, int sheetCount, int tableCount, boolean sharedStrings) {
        x.raw(DECLARATION).raw("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        x.raw("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        x.raw("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        x.raw("<Override PartName=\"/xl/workbook.xml\" ContentType=\"").raw(CT_PREFIX).raw("sheet.main+xml\"/>");
        for (int i = 1; i <= sheetCount; i++) {
            x.raw("<Override PartName=\"/xl/worksheets/sheet").putLong(i);
            x.raw(".xml\" ContentType=\"").raw(CT_PREFIX).raw("worksheet+xml\"/>");
        }
        x.raw("<Override PartName=\"/xl/styles.xml\" ContentType=\"").raw(CT_PREFIX).raw("styles+xml\"/>");
        for (int i = 1; i <= tableCount; i++) {
            x.raw("<Override PartName=\"/xl/tables/table").putLong(i);
            x.raw(".xml\" ContentType=\"").raw(CT_PREFIX).raw("table+xml\"/>");
        }
        if (sharedStrings) {
            x.raw("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"").raw(CT_PREFIX).raw("sharedStrings+xml\"/>");
        }
        x.raw("</Types>");
    }

    static void sheetRels(XmlBuffer x, List<TableDefinition> tables) {
        x.raw(DECLARATION).raw("<Relationships xmlns=\"").raw(NS_PACKAGE_REL).raw("\">");
        for (int i = 0; i < tables.size(); i++) {
            x.raw("<Relationship Id=\"rId").putLong(i + 1).raw("\" Type=\"").raw(NS_REL);
            x.raw("/table\" Target=\"../tables/table").putLong(tables.get(i).id).raw(".xml\"/>");
        }
        x.raw("</Relationships>");
    }

    static void rootRels(XmlBuffer x) {
        x.raw(DECLARATION).raw("<Relationships xmlns=\"").raw(NS_PACKAGE_REL).raw("\"><Relationship Id=\"rId1\" Type=\"");
        x.raw(NS_REL).raw("/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
    }
}
