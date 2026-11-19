package de.vensas.hypaxel.benchmarks;

import de.vensas.hypaxel.SheetReader;
import de.vensas.hypaxel.XlsxReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.stream.Stream;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The readers under test. Each reads every cell of the first worksheet with the library's
 * streaming API and folds the values into a {@link Checksum}, which must be identical for all.
 */
public enum ReadVariant {
    HYPAXEL {
        @Override
        Checksum read(Path file) throws IOException {
            Checksum sum = new Checksum();
            try (XlsxReader reader = XlsxReader.open(file); SheetReader rows = reader.worksheet(0)) {
                for (de.vensas.hypaxel.Row row = rows.nextRow(); row != null; row = rows.nextRow()) {
                    for (int i = 0, n = row.cellCount(); i < n; i++) {
                        int column = row.column(i);
                        switch (row.type(column)) {
                            case STRING -> sum.string(row.getString(column));
                            case NUMBER, DATE_TIME -> sum.number(row.getNumber(column));
                            case BOOLEAN -> sum.bool(row.getBoolean(column));
                            case ERROR -> sum.string(row.getError(column));
                            default -> {
                            }
                        }
                    }
                }
            }
            return sum;
        }
    },
    FASTEXCEL {
        @Override
        Checksum read(Path file) throws IOException {
            Checksum sum = new Checksum();
            try (org.dhatim.fastexcel.reader.ReadableWorkbook workbook = new org.dhatim.fastexcel.reader.ReadableWorkbook(file.toFile());
                    Stream<org.dhatim.fastexcel.reader.Row> rows = workbook.getFirstSheet().openStream()) {
                Iterator<org.dhatim.fastexcel.reader.Row> it = rows.iterator();
                while (it.hasNext()) {
                    for (org.dhatim.fastexcel.reader.Cell cell : it.next()) {
                        if (cell == null) {
                            continue;
                        }
                        switch (cell.getType()) {
                            case STRING, ERROR -> sum.string(cell.asString());
                            case NUMBER -> sum.number(((BigDecimal) cell.getValue()).doubleValue());
                            case BOOLEAN -> sum.bool(cell.asBoolean());
                            default -> {
                            }
                        }
                    }
                }
            }
            return sum;
        }
    },
    POI_SAX {
        @Override
        Checksum read(Path file) throws IOException {
            try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
                XSSFReader reader = new XSSFReader(pkg);
                ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
                SaxHandler handler = new SaxHandler(strings, reader.getStylesTable());
                XMLReader parser = XMLHelper.newXMLReader();
                parser.setContentHandler(handler);
                try (InputStream sheet = reader.getSheetsData().next()) {
                    parser.parse(new InputSource(sheet));
                }
                return handler.sum;
            } catch (OpenXML4JException | SAXException | ParserConfigurationException e) {
                throw new IOException(e);
            }
        }
    };

    abstract Checksum read(Path file) throws IOException;

    /**
     * Apache POI's event API as its documentation shows it: a SAX handler over the raw sheet XML
     * with the shared string table and the styles (for date detection) loaded up front.
     */
    private static final class SaxHandler extends DefaultHandler {
        final Checksum sum = new Checksum();
        private final ReadOnlySharedStringsTable strings;
        private final StylesTable styles;
        private final StringBuilder text = new StringBuilder();
        private Boolean[] dateStyles = new Boolean[64];
        private String type;
        private int style;
        private boolean inValue;
        private boolean inInlineText;

        SaxHandler(ReadOnlySharedStringsTable strings, StylesTable styles) {
            this.strings = strings;
            this.styles = styles;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (localName) {
                case "c" -> {
                    type = attributes.getValue("t");
                    String s = attributes.getValue("s");
                    style = s == null ? 0 : Integer.parseInt(s);
                }
                case "v" -> {
                    inValue = true;
                    text.setLength(0);
                }
                case "t" -> {
                    if ("inlineStr".equals(type)) {
                        inInlineText = true;
                        text.setLength(0);
                    }
                }
                default -> {
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inValue || inInlineText) {
                text.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (localName.equals("t") && inInlineText) {
                inInlineText = false;
                sum.string(text.toString());
            } else if (localName.equals("v")) {
                inValue = false;
                if (type == null || type.equals("n")) {
                    double value = Double.parseDouble(text.toString());
                    isDate(style);
                    sum.number(value);
                } else if (type.equals("s")) {
                    sum.string(strings.getItemAt(Integer.parseInt(text.toString())).getString());
                } else if (type.equals("b")) {
                    sum.bool(text.charAt(0) == '1');
                } else {
                    sum.string(text.toString());
                }
            }
        }

        /** Whether a cell format shows dates, cached per format as a real application would. */
        private boolean isDate(int index) {
            if (index >= dateStyles.length) {
                dateStyles = java.util.Arrays.copyOf(dateStyles, index * 2);
            }
            Boolean date = dateStyles[index];
            if (date == null) {
                XSSFCellStyle cellStyle = styles.getStyleAt(index);
                date = cellStyle != null && DateUtil.isADateFormat(cellStyle.getDataFormat(), cellStyle.getDataFormatString());
                dateStyles[index] = date;
            }
            return date;
        }
    }
}
