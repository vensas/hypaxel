namespace Hypaxel.Internal;

/// <summary>Serializers for the small package-level parts written when the workbook completes.</summary>
internal static class WorkbookParts
{
    public static void WriteWorkbook(Utf8XmlBuffer x, List<Worksheet> sheets, bool usesFormulas, bool date1904)
    {
        x.WriteRaw(Xml.Declaration);
        x.WriteRaw("<workbook xmlns=\""u8);
        x.WriteRaw(Xml.SpreadsheetMlNamespace);
        x.WriteRaw("\" xmlns:r=\""u8);
        x.WriteRaw(Xml.RelationshipsNamespace);
        x.WriteRaw("\">"u8);
        if (date1904)
        {
            x.WriteRaw("<workbookPr date1904=\"1\"/>"u8);
        }

        x.WriteRaw("<bookViews><workbookView activeTab=\""u8);
        x.Write(sheets.FindIndex(s => !s.Hidden));
        x.WriteRaw("\"/></bookViews><sheets>"u8);
        for (var i = 0; i < sheets.Count; i++)
        {
            x.WriteRaw("<sheet name=\""u8);
            x.WriteEscaped(sheets[i].Name);
            x.WriteRaw("\" sheetId=\""u8);
            x.Write(i + 1);
            x.WriteByte((byte)'"');
            if (sheets[i].Hidden)
            {
                x.WriteRaw(" state=\"hidden\""u8);
            }

            x.WriteRaw(" r:id=\"rId"u8);
            x.Write(i + 1);
            x.WriteRaw("\"/>"u8);
        }

        x.WriteRaw("</sheets>"u8);

        var wroteDefinedNames = false;
        foreach (var sheet in sheets)
        {
            if (sheet.AutoFilter is not { } f)
            {
                continue;
            }

            if (!wroteDefinedNames)
            {
                x.WriteRaw("<definedNames>"u8);
                wroteDefinedNames = true;
            }

            x.WriteRaw("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\""u8);
            x.Write(sheet.Index);
            x.WriteRaw("\" hidden=\"1\">'"u8);
            x.WriteEscaped(sheet.Name.Replace("'", "''", StringComparison.Ordinal));
            x.WriteRaw("'!"u8);
            x.WriteEscaped(CellReference.ToAbsoluteRange(f.FirstRow, f.FirstColumn, f.LastRow, f.LastColumn));
            x.WriteRaw("</definedName>"u8);
        }

        if (wroteDefinedNames)
        {
            x.WriteRaw("</definedNames>"u8);
        }

        x.WriteRaw(usesFormulas
            ? "<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>"u8
            : "<calcPr calcId=\"191029\"/></workbook>"u8);
    }

    public static void WriteWorkbookRelationships(Utf8XmlBuffer x, int sheetCount, bool hasSharedStrings)
    {
        x.WriteRaw(Xml.Declaration);
        x.WriteRaw("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"u8);
        for (var i = 1; i <= sheetCount; i++)
        {
            x.WriteRaw("<Relationship Id=\"rId"u8);
            x.Write(i);
            x.WriteRaw("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet"u8);
            x.Write(i);
            x.WriteRaw(".xml\"/>"u8);
        }

        x.WriteRaw("<Relationship Id=\"rId"u8);
        x.Write(sheetCount + 1);
        x.WriteRaw("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"u8);
        if (hasSharedStrings)
        {
            x.WriteRaw("<Relationship Id=\"rId"u8);
            x.Write(sheetCount + 2);
            x.WriteRaw("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"u8);
        }

        x.WriteRaw("</Relationships>"u8);
    }

    public static void WriteContentTypes(Utf8XmlBuffer x, int sheetCount, int tableCount, bool hasSharedStrings)
    {
        x.WriteRaw(Xml.Declaration);
        x.WriteRaw("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"u8);
        x.WriteRaw("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"u8);
        x.WriteRaw("<Default Extension=\"xml\" ContentType=\"application/xml\"/>"u8);
        x.WriteRaw("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"u8);
        for (var i = 1; i <= sheetCount; i++)
        {
            x.WriteRaw("<Override PartName=\"/xl/worksheets/sheet"u8);
            x.Write(i);
            x.WriteRaw(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"u8);
        }

        x.WriteRaw("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"u8);
        for (var i = 1; i <= tableCount; i++)
        {
            x.WriteRaw("<Override PartName=\"/xl/tables/table"u8);
            x.Write(i);
            x.WriteRaw(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml\"/>"u8);
        }

        if (hasSharedStrings)
        {
            x.WriteRaw("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"u8);
        }

        x.WriteRaw("</Types>"u8);
    }

    public static void WriteWorksheetRelationships(Utf8XmlBuffer x, List<TableDefinition> tables)
    {
        x.WriteRaw(Xml.Declaration);
        x.WriteRaw("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"u8);
        for (var i = 0; i < tables.Count; i++)
        {
            x.WriteRaw("<Relationship Id=\"rId"u8);
            x.Write(i + 1);
            x.WriteRaw("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/table\" Target=\"../tables/table"u8);
            x.Write(tables[i].Id);
            x.WriteRaw(".xml\"/>"u8);
        }

        x.WriteRaw("</Relationships>"u8);
    }

    public static void WriteRootRelationships(Utf8XmlBuffer x)
    {
        x.WriteRaw(Xml.Declaration);
        x.WriteRaw("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"u8);
    }
}
