package de.vensas.hypaxel.benchmarks;

import de.vensas.hypaxel.SheetOptions;
import de.vensas.hypaxel.StringStorage;
import de.vensas.hypaxel.Worksheet;
import de.vensas.hypaxel.XlsxOptions;
import de.vensas.hypaxel.XlsxWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.xssf.streaming.SXSSFRow;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** The writers under test. Every variant writes the same ten columns per row to one worksheet. */
public enum Variant {
    HYPAXEL_TYPED {
        @Override
        void write(List<Row> rows, OutputStream out) throws IOException {
            try (XlsxWriter writer = XlsxWriter.create(out)) {
                writeTyped(writer.addWorksheet("Data"), rows);
            }
        }
    },
    HYPAXEL_ROW_WRITER {
        @Override
        void write(List<Row> rows, OutputStream out) throws IOException {
            try (XlsxWriter writer = XlsxWriter.create(out)) {
                writer.addWorksheet("Data").writeRows(rows, (sheet, row) -> sheet
                        .write(row.id()).write(row.name()).write(row.category()).write(row.price()).write(row.amount())
                        .write(row.created()).write(row.active()).write(row.quantity()).write(row.comment()).write(row.ratio()));
            }
        }
    },
    HYPAXEL_OBJECTS {
        @Override
        void write(List<Row> rows, OutputStream out) throws IOException {
            try (XlsxWriter writer = XlsxWriter.create(out)) {
                writer.writeWorksheet("Data", Row.class, rows);
            }
        }
    },
    HYPAXEL_BOXED_ROWS {
        @Override
        void write(List<Row> rows, OutputStream out) throws IOException {
            try (XlsxWriter writer = XlsxWriter.create(out)) {
                Worksheet sheet = writer.addWorksheet("Data");
                for (Row row : rows) {
                    sheet.addRow(row.id(), row.name(), row.category(), row.price(), row.amount(), row.created(), row.active(),
                            row.quantity(), row.comment(), row.ratio());
                }
            }
        }
    },
    HYPAXEL_SHARED_STRINGS {
        @Override
        void write(List<Row> rows, OutputStream out) throws IOException {
            XlsxOptions options = XlsxOptions.builder().stringStorage(StringStorage.SHARED).build();
            try (XlsxWriter writer = XlsxWriter.create(out, options)) {
                writeTyped(writer.addWorksheet("Data"), rows);
            }
        }
    },
    HYPAXEL_LEVEL_6 {
        @Override
        void write(List<Row> rows, OutputStream out) throws IOException {
            try (XlsxWriter writer = XlsxWriter.create(out, XlsxOptions.builder().compressionLevel(6).build())) {
                writeTyped(writer.addWorksheet("Data"), rows);
            }
        }
    },
    HYPAXEL_AUTOFIT {
        @Override
        void write(List<Row> rows, OutputStream out) throws IOException {
            try (XlsxWriter writer = XlsxWriter.create(out)) {
                writeTyped(writer.addWorksheet("Data", SheetOptions.builder().autoFitColumns().build()), rows);
            }
        }
    },
    FASTEXCEL {
        @Override
        void write(List<Row> rows, OutputStream out) throws IOException {
            writeFastExcel(rows, out, FASTEXCEL_DEFAULT_LEVEL);
        }
    },
    FASTEXCEL_LEVEL_1 {
        @Override
        void write(List<Row> rows, OutputStream out) throws IOException {
            writeFastExcel(rows, out, 1);
        }
    },
    POI_SXSSF {
        @Override
        void write(List<Row> rows, OutputStream out) throws IOException {
            try (SXSSFWorkbook workbook = new SXSSFWorkbook(POI_WINDOW_ROWS)) {
                CellStyle dateTime = workbook.createCellStyle();
                dateTime.setDataFormat((short) 22);
                SXSSFSheet sheet = workbook.createSheet("Data");
                int index = 0;
                for (Row row : rows) {
                    SXSSFRow target = sheet.createRow(index++);
                    target.createCell(0).setCellValue(row.id());
                    target.createCell(1).setCellValue(row.name());
                    target.createCell(2).setCellValue(row.category());
                    target.createCell(3).setCellValue(row.price());
                    target.createCell(4).setCellValue(row.amount().doubleValue());
                    var created = target.createCell(5);
                    created.setCellValue(row.created());
                    created.setCellStyle(dateTime);
                    target.createCell(6).setCellValue(row.active());
                    target.createCell(7).setCellValue(row.quantity());
                    target.createCell(8).setCellValue(row.comment());
                    target.createCell(9).setCellValue(row.ratio());
                }
                workbook.write(out);
            }
        }
    };

    /** FastExcel keeps rows in memory until they are flushed; this matches POI's window. */
    private static final int FASTEXCEL_FLUSH_ROWS = 100;
    private static final int FASTEXCEL_DEFAULT_LEVEL = -1;
    private static final int POI_WINDOW_ROWS = 100;

    abstract void write(List<Row> rows, OutputStream out) throws IOException;

    private static void writeTyped(Worksheet sheet, List<Row> rows) throws IOException {
        for (Row row : rows) {
            sheet.write(row.id());
            sheet.write(row.name());
            sheet.write(row.category());
            sheet.write(row.price());
            sheet.write(row.amount());
            sheet.write(row.created());
            sheet.write(row.active());
            sheet.write(row.quantity());
            sheet.write(row.comment());
            sheet.write(row.ratio());
            sheet.endRow();
        }
    }

    private static void writeFastExcel(List<Row> rows, OutputStream out, int level) throws IOException {
        org.dhatim.fastexcel.Workbook workbook = new org.dhatim.fastexcel.Workbook(out, "benchmark", "1.0");
        if (level != FASTEXCEL_DEFAULT_LEVEL) {
            workbook.setCompressionLevel(level);
        }
        org.dhatim.fastexcel.Worksheet sheet = workbook.newWorksheet("Data");
        int index = 0;
        for (Row row : rows) {
            sheet.value(index, 0, row.id());
            sheet.value(index, 1, row.name());
            sheet.value(index, 2, row.category());
            sheet.value(index, 3, row.price());
            sheet.value(index, 4, row.amount());
            sheet.value(index, 5, row.created());
            sheet.style(index, 5).format("yyyy-mm-dd hh:mm:ss").set();
            sheet.value(index, 6, row.active());
            sheet.value(index, 7, row.quantity());
            sheet.value(index, 8, row.comment());
            sheet.value(index, 9, row.ratio());
            if (++index % FASTEXCEL_FLUSH_ROWS == 0) {
                sheet.flush();
            }
        }
        workbook.finish();
    }
}
