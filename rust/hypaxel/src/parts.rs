//! Package-level parts written when the workbook completes.

use crate::engine::{SheetMeta, push_column_name};
use crate::table::TableMeta;
use crate::xml;

pub(crate) fn workbook(x: &mut Vec<u8>, sheets: &[SheetMeta], uses_formulas: bool, date_1904: bool) {
    x.extend_from_slice(xml::DECLARATION);
    x.extend_from_slice(b"<workbook xmlns=\"");
    x.extend_from_slice(xml::NS_MAIN);
    x.extend_from_slice(b"\" xmlns:r=\"");
    x.extend_from_slice(xml::NS_REL);
    x.extend_from_slice(b"\">");
    if date_1904 {
        x.extend_from_slice(b"<workbookPr date1904=\"1\"/>");
    }
    x.extend_from_slice(b"<bookViews><workbookView activeTab=\"");
    xml::push_int(x, sheets.iter().position(|s| !s.hidden).unwrap_or(0));
    x.extend_from_slice(b"\"/></bookViews><sheets>");
    for (i, sheet) in sheets.iter().enumerate() {
        x.extend_from_slice(b"<sheet name=\"");
        xml::escape_into(x, &sheet.name);
        x.extend_from_slice(b"\" sheetId=\"");
        xml::push_int(x, i + 1);
        x.push(b'"');
        if sheet.hidden {
            x.extend_from_slice(b" state=\"hidden\"");
        }
        x.extend_from_slice(b" r:id=\"rId");
        xml::push_int(x, i + 1);
        x.extend_from_slice(b"\"/>");
    }
    x.extend_from_slice(b"</sheets>");

    let mut wrote_names = false;
    for (i, sheet) in sheets.iter().enumerate() {
        let Some((r1, c1, r2, c2)) = sheet.autofilter else { continue };
        if !wrote_names {
            x.extend_from_slice(b"<definedNames>");
            wrote_names = true;
        }
        x.extend_from_slice(b"<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"");
        xml::push_int(x, i);
        x.extend_from_slice(b"\" hidden=\"1\">'");
        xml::escape_into(x, &sheet.name.replace('\'', "''"));
        x.extend_from_slice(b"'!$");
        push_column_name(x, c1);
        x.push(b'$');
        xml::push_int(x, r1);
        x.extend_from_slice(b":$");
        push_column_name(x, c2);
        x.push(b'$');
        xml::push_int(x, r2);
        x.extend_from_slice(b"</definedName>");
    }
    if wrote_names {
        x.extend_from_slice(b"</definedNames>");
    }
    x.extend_from_slice(if uses_formulas {
        b"<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>"
    } else {
        b"<calcPr calcId=\"191029\"/></workbook>"
    });
}

pub(crate) fn workbook_rels(x: &mut Vec<u8>, sheet_count: usize, shared_strings: bool) {
    x.extend_from_slice(xml::DECLARATION);
    x.extend_from_slice(b"<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
    for i in 1..=sheet_count {
        x.extend_from_slice(b"<Relationship Id=\"rId");
        xml::push_int(x, i);
        x.extend_from_slice(
            b"\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet",
        );
        xml::push_int(x, i);
        x.extend_from_slice(b".xml\"/>");
    }
    x.extend_from_slice(b"<Relationship Id=\"rId");
    xml::push_int(x, sheet_count + 1);
    x.extend_from_slice(b"\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
    if shared_strings {
        x.extend_from_slice(b"<Relationship Id=\"rId");
        xml::push_int(x, sheet_count + 2);
        x.extend_from_slice(
            b"\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>",
        );
    }
    x.extend_from_slice(b"</Relationships>");
}

pub(crate) fn content_types(x: &mut Vec<u8>, sheet_count: usize, table_count: u32, shared_strings: bool) {
    x.extend_from_slice(xml::DECLARATION);
    x.extend_from_slice(b"<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
    x.extend_from_slice(b"<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
    x.extend_from_slice(b"<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
    x.extend_from_slice(b"<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
    for i in 1..=sheet_count {
        x.extend_from_slice(b"<Override PartName=\"/xl/worksheets/sheet");
        xml::push_int(x, i);
        x.extend_from_slice(b".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
    }
    x.extend_from_slice(
        b"<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>",
    );
    for i in 1..=table_count {
        x.extend_from_slice(b"<Override PartName=\"/xl/tables/table");
        xml::push_int(x, i);
        x.extend_from_slice(b".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml\"/>");
    }
    if shared_strings {
        x.extend_from_slice(b"<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>");
    }
    x.extend_from_slice(b"</Types>");
}

pub(crate) fn sheet_rels(x: &mut Vec<u8>, tables: &[TableMeta]) {
    x.extend_from_slice(xml::DECLARATION);
    x.extend_from_slice(b"<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
    for (i, table) in tables.iter().enumerate() {
        x.extend_from_slice(b"<Relationship Id=\"rId");
        xml::push_int(x, i + 1);
        x.extend_from_slice(
            b"\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/table\" Target=\"../tables/table",
        );
        xml::push_int(x, table.id);
        x.extend_from_slice(b".xml\"/>");
    }
    x.extend_from_slice(b"</Relationships>");
}

pub(crate) fn root_rels(x: &mut Vec<u8>) {
    x.extend_from_slice(xml::DECLARATION);
    x.extend_from_slice(b"<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
}
