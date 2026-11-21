package de.vensas.hypaxel;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An Excel table (a {@code ListObject}): a named, styled range with a header row and filter
 * buttons, added with {@link Worksheet#addTable(ExcelTable)}. Immutable; create instances with
 * {@link #builder(int, int, int, int)}.
 *
 * <p>With a header row (the default), {@link Builder#columns(String...)} must list the header
 * texts exactly as written in the range's first row: Excel identifies table columns by those names
 * and repairs the file otherwise.
 *
 * <pre>{@code
 * sheet.addRow("Product", "Units");
 * sheet.addRow("Widget", 3);
 * sheet.addRow("Gadget", 5);
 * sheet.addTable(ExcelTable.builder(1, 1, sheet.rowNumber(), 2).name("Orders").columns("Product", "Units").build());
 * }</pre>
 */
public final class ExcelTable {
    private final int firstRow;
    private final int firstColumn;
    private final int lastRow;
    private final int lastColumn;
    private final String name;
    private final List<String> columns;
    private final TableStyle style;
    private final boolean headerRow;
    private final boolean autoFilter;
    private final boolean rowStripes;
    private final boolean columnStripes;
    private final boolean highlightFirstColumn;
    private final boolean highlightLastColumn;
    private final boolean totalsRow;

    private ExcelTable(Builder builder) {
        firstRow = builder.firstRow;
        firstColumn = builder.firstColumn;
        lastRow = builder.lastRow;
        lastColumn = builder.lastColumn;
        name = builder.name;
        columns = builder.columns;
        style = builder.style;
        headerRow = builder.headerRow;
        autoFilter = builder.autoFilter;
        rowStripes = builder.rowStripes;
        columnStripes = builder.columnStripes;
        highlightFirstColumn = builder.highlightFirstColumn;
        highlightLastColumn = builder.highlightLastColumn;
        totalsRow = builder.totalsRow;
    }

    /** Returns a builder for a table over a range (1-based, inclusive) whose first row is the header. */
    public static Builder builder(int firstRow, int firstColumn, int lastRow, int lastColumn) {
        return new Builder(firstRow, firstColumn, lastRow, lastColumn);
    }

    /** Returns the first row of the range, 1-based. */
    public int firstRow() {
        return firstRow;
    }

    /** Returns the first column of the range, 1-based. */
    public int firstColumn() {
        return firstColumn;
    }

    /** Returns the last row of the range, 1-based and inclusive. */
    public int lastRow() {
        return lastRow;
    }

    /** Returns the last column of the range, 1-based and inclusive. */
    public int lastColumn() {
        return lastColumn;
    }

    /** Returns the table name, or null for {@code Table1}, {@code Table2}, ... */
    public String name() {
        return name;
    }

    /** Returns the column names; empty for {@code Column1}, {@code Column2}, ... */
    public List<String> columns() {
        return columns;
    }

    /** Returns the table style. */
    public TableStyle style() {
        return style;
    }

    /** Returns whether the first row of the range holds the column names. */
    public boolean headerRow() {
        return headerRow;
    }

    /** Returns whether the header row has filter buttons. */
    public boolean autoFilter() {
        return autoFilter;
    }

    /** Returns whether rows are banded. */
    public boolean rowStripes() {
        return rowStripes;
    }

    /** Returns whether columns are banded. */
    public boolean columnStripes() {
        return columnStripes;
    }

    /** Returns whether the first column is highlighted. */
    public boolean highlightFirstColumn() {
        return highlightFirstColumn;
    }

    /** Returns whether the last column is highlighted. */
    public boolean highlightLastColumn() {
        return highlightLastColumn;
    }

    /**
     * Returns whether the last row of the range is the table's totals row. Reported by
     * {@link XlsxReader#tables(String)}; a table that has one cannot be written.
     */
    public boolean totalsRow() {
        return totalsRow;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExcelTable table
                && firstRow == table.firstRow
                && firstColumn == table.firstColumn
                && lastRow == table.lastRow
                && lastColumn == table.lastColumn
                && Objects.equals(name, table.name)
                && columns.equals(table.columns)
                && style.equals(table.style)
                && headerRow == table.headerRow
                && autoFilter == table.autoFilter
                && rowStripes == table.rowStripes
                && columnStripes == table.columnStripes
                && highlightFirstColumn == table.highlightFirstColumn
                && highlightLastColumn == table.highlightLastColumn
                && totalsRow == table.totalsRow;
    }

    @Override
    public int hashCode() {
        return Objects.hash(firstRow, firstColumn, lastRow, lastColumn, name, columns, style, headerRow, autoFilter,
                rowStripes, columnStripes, highlightFirstColumn, highlightLastColumn, totalsRow);
    }

    /** Returns the name, range and options, e.g. {@code Orders A1:C10 [Product, Units] TableStyleMedium2}. */
    @Override
    public String toString() {
        return (name == null ? "(unnamed)" : name) + " " + CellReference.range(firstRow, firstColumn, lastRow, lastColumn)
                + " " + columns + " " + style
                + " headerRow=" + headerRow + " autoFilter=" + autoFilter
                + " rowStripes=" + rowStripes + " columnStripes=" + columnStripes
                + " highlightFirstColumn=" + highlightFirstColumn + " highlightLastColumn=" + highlightLastColumn
                + " totalsRow=" + totalsRow;
    }

    /** Builder for {@link ExcelTable}. */
    public static final class Builder {
        private final int firstRow;
        private final int firstColumn;
        private final int lastRow;
        private final int lastColumn;
        private String name;
        private List<String> columns = List.of();
        private TableStyle style = TableStyle.DEFAULT;
        private boolean headerRow = true;
        private boolean autoFilter = true;
        private boolean rowStripes = true;
        private boolean columnStripes;
        private boolean highlightFirstColumn;
        private boolean highlightLastColumn;
        private boolean totalsRow;

        private Builder(int firstRow, int firstColumn, int lastRow, int lastColumn) {
            this.firstRow = firstRow;
            this.firstColumn = firstColumn;
            this.lastRow = lastRow;
            this.lastColumn = lastColumn;
        }

        /**
         * Sets the workbook-unique name used in structured references such as {@code Orders[Units]}.
         * Names start with a letter, {@code _} or {@code \}, contain only letters, digits, {@code _},
         * {@code .} and {@code \}, and cannot look like a cell reference. Default: {@code TableN}.
         */
        public Builder name(String value) {
            name = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the column names: required with a header row, otherwise {@code Column1}, {@code Column2}, ... by default. */
        public Builder columns(String... names) {
            return columns(Arrays.asList(names));
        }

        /** Sets the column names from a list. */
        public Builder columns(List<String> names) {
            columns = Collections.unmodifiableList(Arrays.asList(names.toArray(new String[0])));
            return this;
        }

        /** Sets the table style. Default: {@link TableStyle#DEFAULT}. */
        public Builder style(TableStyle value) {
            style = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets whether the first row of the range holds the column names. Default: true. */
        public Builder headerRow(boolean enabled) {
            headerRow = enabled;
            return this;
        }

        /** Sets whether the header row has filter buttons. Default: true. */
        public Builder autoFilter(boolean enabled) {
            autoFilter = enabled;
            return this;
        }

        /** Sets whether rows are banded. Default: true. */
        public Builder rowStripes(boolean enabled) {
            rowStripes = enabled;
            return this;
        }

        /** Sets whether columns are banded. Default: false. */
        public Builder columnStripes(boolean enabled) {
            columnStripes = enabled;
            return this;
        }

        /** Sets whether the first column is highlighted. Default: false. */
        public Builder highlightFirstColumn(boolean enabled) {
            highlightFirstColumn = enabled;
            return this;
        }

        /** Sets whether the last column is highlighted. Default: false. */
        public Builder highlightLastColumn(boolean enabled) {
            highlightLastColumn = enabled;
            return this;
        }

        /**
         * Marks the last row of the range as the table's totals row. Default: false. The reader sets
         * this for tables that have one; {@link Worksheet#addTable(ExcelTable)} rejects such a table,
         * because totals rows cannot be written.
         */
        public Builder totalsRow(boolean enabled) {
            totalsRow = enabled;
            return this;
        }

        /** Creates the table definition. It is validated when added to a worksheet. */
        public ExcelTable build() {
            return new ExcelTable(this);
        }
    }
}
