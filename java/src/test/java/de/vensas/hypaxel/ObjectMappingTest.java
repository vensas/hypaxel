package de.vensas.hypaxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ObjectMappingTest {
    enum Status {
        OPEN,
        SHIPPED
    }

    public record Order(
            @ExcelColumn(name = "Order #", order = 0, width = 10) int id,
            String customer,
            @ExcelColumn(format = "yyyy-mm-dd") LocalDate date,
            @ExcelColumn(name = "Amount", format = "#,##0.00") BigDecimal amount,
            @ExcelIgnore String internalNote,
            boolean paid,
            long quantity,
            double ratio,
            float weight,
            short priority,
            byte flags,
            Status status,
            LocalDateTime created,
            Integer optional) {
    }

    record PackagePrivate(@ExcelColumn(order = 2) String first, @ExcelColumn(order = 1) String second, String third) {
    }

    public static class Person {
        @ExcelColumn(name = "Full name", width = 300)
        private final String name;
        private final int age;
        @ExcelIgnore
        private final String password = "secret";
        private final boolean active;
        private final String noGetter = "hidden";
        static final String CONSTANT = "ignored";

        Person(String name, int age, boolean active) {
            this.name = name;
            this.age = age;
            this.active = active;
        }

        public String getName() {
            return name;
        }

        public int getAge() {
            return age;
        }

        public String getPassword() {
            return password;
        }

        public boolean isActive() {
            return active;
        }

        @ExcelColumn(name = "Initial", order = -1)
        public char getInitial() {
            return name.charAt(0);
        }

        @ExcelIgnore
        public String getSecretComputed() {
            return noGetter;
        }

        public String describe() {
            return name;
        }

        public static String getStatic() {
            return CONSTANT;
        }

        public String getWithArgument(int ignored) {
            return name;
        }
    }

    public static class Employee extends Person {
        private final UUID badge;
        private final Instant hired;

        Employee(String name, UUID badge, Instant hired) {
            super(name, 40, true);
            this.badge = badge;
            this.hired = hired;
        }

        public UUID badge() {
            return badge;
        }

        public Instant getHired() {
            return hired;
        }
    }

    public static class Failing {
        public String getValue() {
            throw new IllegalStateException("getter failed");
        }
    }

    public static class Checked {
        public String getChecked() throws Exception {
            throw new Exception("checked failure");
        }
    }

    public static class NoProperties {
        public void run() {
        }
    }

    private static final List<Order> ORDERS = List.of(
            new Order(1, "Ada", LocalDate.of(2024, 5, 1), new BigDecimal("1234.50"), "note", true, 3L, 0.5, 1.5f, (short) 2, (byte) 1,
                    Status.OPEN, LocalDateTime.of(2024, 5, 1, 9, 30), 7),
            new Order(2, null, null, null, null, false, 4L, 0.25, 2.5f, (short) 3, (byte) 0, null, null, null));

    @Test
    void writesRecordsAsAFormattedTable() throws Exception {
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet sheet = writer.writeWorksheet("Orders", Order.class, ORDERS);
            assertEquals("Orders", sheet.name());
            assertEquals(3, sheet.rowNumber());
        });
        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Orders");
            XSSFRow header = sheet.getRow(0);
            List<String> expected = List.of("Order #", "customer", "date", "Amount", "paid", "quantity", "ratio", "weight", "priority",
                    "flags", "status", "created", "optional");
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), header.getCell(i).getStringCellValue());
            }
            assertEquals(expected.size(), header.getLastCellNum());
            XSSFCellStyle headerStyle = header.getCell(0).getCellStyle();
            assertTrue(headerStyle.getFont().getBold());
            assertEquals("FFD9D9D9", headerStyle.getFillForegroundColorColor().getARGBHex());
            assertEquals(org.apache.poi.ss.usermodel.BorderStyle.THIN, headerStyle.getBorderBottom());

            XSSFRow first = sheet.getRow(1);
            assertEquals(1.0, first.getCell(0).getNumericCellValue());
            assertEquals("Ada", first.getCell(1).getStringCellValue());
            assertEquals(LocalDate.of(2024, 5, 1), first.getCell(2).getLocalDateTimeCellValue().toLocalDate());
            assertEquals("yyyy-mm-dd", first.getCell(2).getCellStyle().getDataFormatString());
            assertEquals(1234.5, first.getCell(3).getNumericCellValue());
            assertEquals("#,##0.00", first.getCell(3).getCellStyle().getDataFormatString());
            assertTrue(first.getCell(4).getBooleanCellValue());
            assertEquals(3.0, first.getCell(5).getNumericCellValue());
            assertEquals(0.5, first.getCell(6).getNumericCellValue());
            assertEquals(1.5, first.getCell(7).getNumericCellValue());
            assertEquals(2.0, first.getCell(8).getNumericCellValue());
            assertEquals(1.0, first.getCell(9).getNumericCellValue());
            assertEquals("OPEN", first.getCell(10).getStringCellValue());
            assertEquals(LocalDateTime.of(2024, 5, 1, 9, 30), first.getCell(11).getLocalDateTimeCellValue());
            assertEquals(7.0, first.getCell(12).getNumericCellValue());

            XSSFRow second = sheet.getRow(2);
            assertEquals(2.0, second.getCell(0).getNumericCellValue());
            assertNull(second.getCell(1));
            assertEquals(CellType.BLANK, second.getCell(2).getCellType());
            assertEquals(CellType.BLANK, second.getCell(3).getCellType());
            assertFalse(second.getCell(4).getBooleanCellValue());
            assertNull(second.getCell(10));
            assertNull(second.getCell(12));

            assertEquals(1, sheet.getPaneInformation().getHorizontalSplitPosition());
            assertEquals("A1:M3", sheet.getCTWorksheet().getAutoFilter().getRef());
            assertEquals(10 * 256, sheet.getColumnWidth(0));
            assertEquals(20 * 256, sheet.getColumnWidth(1));
            assertEquals(12 * 256, sheet.getColumnWidth(2));
            assertEquals(18 * 256, sheet.getColumnWidth(11));
        }
    }

    @Test
    void ordersColumnsByAnnotationThenDeclaration() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> writer.addWorksheet("S")
                .writeObjects(PackagePrivate.class, List.of(new PackagePrivate("1", "2", "3"))))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertEquals("second", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("first", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("third", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("2", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("1", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("3", sheet.getRow(1).getCell(2).getStringCellValue());
            assertNull(sheet.getCTWorksheet().getAutoFilter());
        }
    }

    @Test
    void writesJavaBeans() throws Exception {
        UUID badge = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        XlsxOptions options = XlsxOptions.builder().zone(java.time.ZoneOffset.UTC).build();
        try (XSSFWorkbook workbook = Xlsx.roundTrip(options, writer -> {
            writer.writeWorksheet("People", Person.class, Stream.of(new Person("Ada", 36, true), new Person("Linus", 54, false)));
            writer.writeWorksheet("Staff", Employee.class, List.of(new Employee("Grace", badge, Instant.parse("2024-01-01T12:00:00Z"))));
        })) {
            XSSFSheet people = workbook.getSheet("People");
            assertEquals(List.of("Initial", "Full name", "age", "active"), cells(people.getRow(0)));
            assertEquals("A", people.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Ada", people.getRow(1).getCell(1).getStringCellValue());
            assertEquals(36.0, people.getRow(1).getCell(2).getNumericCellValue());
            assertTrue(people.getRow(1).getCell(3).getBooleanCellValue());
            assertFalse(people.getRow(2).getCell(3).getBooleanCellValue());
            assertEquals(255 * 256, people.getColumnWidth(1));

            XSSFSheet staff = workbook.getSheet("Staff");
            assertEquals(List.of("Initial", "Full name", "age", "active", "badge", "hired"), cells(staff.getRow(0)));
            assertEquals(badge.toString(), staff.getRow(1).getCell(4).getStringCellValue());
            assertEquals(45292.5, staff.getRow(1).getCell(5).getNumericCellValue());
            assertEquals(38 * 256, staff.getColumnWidth(4));
        }
    }

    private static List<String> cells(XSSFRow row) {
        String[] values = new String[row.getLastCellNum()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row.getCell(i).getStringCellValue();
        }
        return Arrays.asList(values);
    }

    @Test
    void appliesObjectOptions() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            StyleId italic = writer.addStyle(Style.builder().italic().build());
            Worksheet sheet = writer.addWorksheet("S");
            sheet.addRow("Report");
            sheet.write("unfinished row");
            sheet.writeObjects(PackagePrivate.class, List.of(new PackagePrivate("a", "b", "c")),
                    ObjectOptions.builder().headerStyle(italic).autoFilter(true).build());
            sheet.writeObjects(PackagePrivate.class, Arrays.asList(new PackagePrivate("d", "e", "f"), null, new PackagePrivate("g", "h", "i")),
                    ObjectOptions.builder().header(false).autoFilter(true).build());
            sheet.writeObjects(PackagePrivate.class, Stream.of(new PackagePrivate("j", "k", "l")), ObjectOptions.builder().header(false).build());
            sheet.writeObjects(PackagePrivate.class, Stream.<PackagePrivate>empty());
        })) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertEquals("unfinished row", sheet.getRow(1).getCell(0).getStringCellValue());
            assertTrue(sheet.getRow(2).getCell(0).getCellStyle().getFont().getItalic());
            assertFalse(sheet.getRow(2).getCell(0).getCellStyle().getFont().getBold());
            assertEquals("b", sheet.getRow(3).getCell(0).getStringCellValue());
            assertEquals("A3:C4", sheet.getCTWorksheet().getAutoFilter().getRef());
            assertEquals("e", sheet.getRow(4).getCell(0).getStringCellValue());
            assertEquals(0, sheet.getRow(5).getPhysicalNumberOfCells());
            assertEquals("h", sheet.getRow(6).getCell(0).getStringCellValue());
            assertEquals("k", sheet.getRow(7).getCell(0).getStringCellValue());
            assertEquals("second", sheet.getRow(8).getCell(0).getStringCellValue());
            assertTrue(sheet.getRow(8).getCell(0).getCellStyle().getFont().getBold());
            assertEquals(9, sheet.getPhysicalNumberOfRows());
        }
        ObjectOptions options = ObjectOptions.builder().header(false).autoFilter(true).build();
        assertFalse(options.header());
        assertTrue(options.autoFilter());
        assertNull(options.headerStyle());
    }

    @Test
    void reportsUnmappableTypesAndFailingGetters() throws Exception {
        try (XlsxWriter writer = XlsxWriter.create(java.io.OutputStream.nullOutputStream())) {
            Worksheet sheet = writer.addWorksheet("S");
            XlsxException none = assertThrows(XlsxException.class, () -> sheet.writeObjects(NoProperties.class, List.of(new NoProperties())));
            assertEquals(XlsxException.Kind.UNSUPPORTED_TYPE, none.kind());
            assertTrue(none.getMessage().contains("has no readable properties"));
            assertThrows(XlsxException.class, () -> writer.writeWorksheet("T", NoProperties.class, List.of()));

            assertEquals("getter failed", assertThrows(IllegalStateException.class,
                    () -> sheet.writeObjects(Failing.class, List.of(new Failing()))).getMessage());
            XlsxException checked = assertThrows(XlsxException.class, () -> sheet.writeObjects(Checked.class, List.of(new Checked())));
            assertEquals("checked failure", checked.getCause().getMessage());
            sheet.write("still usable").endRow();
        }
    }
}
