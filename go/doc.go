// Package hypaxel writes XLSX (Excel) workbooks as a forward-only stream.
//
// Every cell is serialized to XML the moment it is written and deflated into a small buffer
// that is flushed to the destination, so memory stays flat regardless of the number of rows
// and the first bytes reach the reader while later rows are still being produced. The
// destination is any [io.Writer]; it does not need to be seekable.
//
//	w := hypaxel.NewWriter(dst)
//	bold := w.AddStyle(hypaxel.Style{Font: hypaxel.Font{Bold: true}})
//	sheet, err := w.AddSheet("Sales")
//	if err != nil {
//		return err
//	}
//	sheet.WriteString("Product", bold)
//	sheet.WriteString("Units", bold)
//	if err := sheet.EndRow(); err != nil {
//		return err
//	}
//	if err := sheet.WriteRow("Widget", 3); err != nil {
//		return err
//	}
//	return w.Close()
//
// Rows go top to bottom, cells left to right, one worksheet at a time. The typed cell methods
// (WriteString, WriteInt, WriteFloat, ...) do not allocate and report problems through the next
// call that returns an error. A Writer is not safe for concurrent use.
package hypaxel
