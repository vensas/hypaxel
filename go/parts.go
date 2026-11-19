package hypaxel

import "strings"

type cellRange struct {
	firstRow, firstColumn, lastRow, lastColumn uint32
}

type sheetMeta struct {
	name       string
	hidden     bool
	autoFilter *cellRange
}

func appendWorkbook(x []byte, sheets []sheetMeta, usesFormulas, date1904 bool) []byte {
	x = append(x, xmlDeclaration...)
	x = append(x, `<workbook xmlns="`+nsMain+`" xmlns:r="`+nsRel+`">`...)
	if date1904 {
		x = append(x, `<workbookPr date1904="1"/>`...)
	}
	active := 0
	for i, sheet := range sheets {
		if !sheet.hidden {
			active = i
			break
		}
	}
	x = append(x, `<bookViews><workbookView activeTab="`...)
	x = appendUint(x, uint64(active))
	x = append(x, `"/></bookViews><sheets>`...)
	for i, sheet := range sheets {
		x = append(x, `<sheet name="`...)
		x = appendEscaped(x, sheet.name)
		x = append(x, `" sheetId="`...)
		x = appendUint(x, uint64(i+1))
		x = append(x, '"')
		if sheet.hidden {
			x = append(x, ` state="hidden"`...)
		}
		x = append(x, ` r:id="rId`...)
		x = appendUint(x, uint64(i+1))
		x = append(x, `"/>`...)
	}
	x = append(x, "</sheets>"...)

	wroteNames := false
	for i, sheet := range sheets {
		f := sheet.autoFilter
		if f == nil {
			continue
		}
		if !wroteNames {
			x = append(x, "<definedNames>"...)
			wroteNames = true
		}
		x = append(x, `<definedName name="_xlnm._FilterDatabase" localSheetId="`...)
		x = appendUint(x, uint64(i))
		x = append(x, `" hidden="1">'`...)
		x = appendEscaped(x, strings.ReplaceAll(sheet.name, "'", "''"))
		x = append(x, "'!$"...)
		x = appendColumnName(x, f.firstColumn)
		x = append(x, '$')
		x = appendUint(x, uint64(f.firstRow))
		x = append(x, ":$"...)
		x = appendColumnName(x, f.lastColumn)
		x = append(x, '$')
		x = appendUint(x, uint64(f.lastRow))
		x = append(x, "</definedName>"...)
	}
	if wroteNames {
		x = append(x, "</definedNames>"...)
	}
	if usesFormulas {
		return append(x, `<calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>`...)
	}
	return append(x, `<calcPr calcId="191029"/></workbook>`...)
}

func appendWorkbookRels(x []byte, sheetCount int, sharedStrings bool) []byte {
	const relType = `" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/`
	x = append(x, xmlDeclaration...)
	x = append(x, `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`...)
	for i := 1; i <= sheetCount; i++ {
		x = append(x, `<Relationship Id="rId`...)
		x = appendUint(x, uint64(i))
		x = append(x, relType+`worksheet" Target="worksheets/sheet`...)
		x = appendUint(x, uint64(i))
		x = append(x, `.xml"/>`...)
	}
	x = append(x, `<Relationship Id="rId`...)
	x = appendUint(x, uint64(sheetCount+1))
	x = append(x, relType+`styles" Target="styles.xml"/>`...)
	if sharedStrings {
		x = append(x, `<Relationship Id="rId`...)
		x = appendUint(x, uint64(sheetCount+2))
		x = append(x, relType+`sharedStrings" Target="sharedStrings.xml"/>`...)
	}
	return append(x, "</Relationships>"...)
}

func appendContentTypes(x []byte, sheetCount, tableCount int, sharedStrings bool) []byte {
	const spreadsheet = `ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.`
	x = append(x, xmlDeclaration...)
	x = append(x, `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">`...)
	x = append(x, `<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>`...)
	x = append(x, `<Default Extension="xml" ContentType="application/xml"/>`...)
	x = append(x, `<Override PartName="/xl/workbook.xml" `+spreadsheet+`sheet.main+xml"/>`...)
	for i := 1; i <= sheetCount; i++ {
		x = append(x, `<Override PartName="/xl/worksheets/sheet`...)
		x = appendUint(x, uint64(i))
		x = append(x, `.xml" `+spreadsheet+`worksheet+xml"/>`...)
	}
	x = append(x, `<Override PartName="/xl/styles.xml" `+spreadsheet+`styles+xml"/>`...)
	for i := 1; i <= tableCount; i++ {
		x = append(x, `<Override PartName="/xl/tables/table`...)
		x = appendUint(x, uint64(i))
		x = append(x, `.xml" `+spreadsheet+`table+xml"/>`...)
	}
	if sharedStrings {
		x = append(x, `<Override PartName="/xl/sharedStrings.xml" `+spreadsheet+`sharedStrings+xml"/>`...)
	}
	return append(x, "</Types>"...)
}

func appendSheetRels(x []byte, tables []tableMeta) []byte {
	x = append(x, xmlDeclaration...)
	x = append(x, `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`...)
	for i := range tables {
		x = append(x, `<Relationship Id="rId`...)
		x = appendUint(x, uint64(i+1))
		x = append(x, `" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table`...)
		x = appendUint(x, uint64(tables[i].id))
		x = append(x, `.xml"/>`...)
	}
	return append(x, "</Relationships>"...)
}

func appendRootRels(x []byte) []byte {
	x = append(x, xmlDeclaration...)
	return append(x, `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`...)
}

type sharedStrings struct {
	index      map[string]uint32
	strings    []string
	references uint64
}

func (s *sharedStrings) indexOf(value string) uint32 {
	s.references++
	if i, ok := s.index[value]; ok {
		return i
	}
	i := uint32(len(s.strings))
	// Cloned so the table never pins a larger buffer the caller sliced the value from.
	value = strings.Clone(value)
	s.strings = append(s.strings, value)
	s.index[value] = i
	return i
}

func (s *sharedStrings) appendHeader(x []byte) []byte {
	x = append(x, xmlDeclaration...)
	x = append(x, `<sst xmlns="`+nsMain+`" count="`...)
	x = appendUint(x, s.references)
	x = append(x, `" uniqueCount="`...)
	x = appendUint(x, uint64(len(s.strings)))
	return append(x, `">`...)
}
