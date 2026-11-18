package de.vensas.hypaxel;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Worksheet settings that precede the cell data in the file, so they are fixed when the worksheet
 * is created. Immutable; create instances with {@link #builder()}.
 */
public final class SheetOptions {
    /** The default options. */
    public static final SheetOptions DEFAULT = builder().build();

    private final SortedMap<Integer, Column> columns;
    private final int frozenRows;
    private final int frozenColumns;
    private final boolean showGridLines;
    private final boolean hidden;
    private final Color tabColor;

    private SheetOptions(Builder builder) {
        columns = Collections.unmodifiableSortedMap(new TreeMap<>(builder.columns));
        frozenRows = builder.frozenRows;
        frozenColumns = builder.frozenColumns;
        showGridLines = builder.showGridLines;
        hidden = builder.hidden;
        tabColor = builder.tabColor;
    }

    /** Returns a builder with the default options. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the column definitions keyed by 1-based column index. */
    public SortedMap<Integer, Column> columns() {
        return columns;
    }

    /** Returns the number of rows frozen at the top. */
    public int frozenRows() {
        return frozenRows;
    }

    /** Returns the number of columns frozen at the left. */
    public int frozenColumns() {
        return frozenColumns;
    }

    /** Returns whether grid lines are shown. */
    public boolean showGridLines() {
        return showGridLines;
    }

    /** Returns whether the worksheet is hidden. */
    public boolean hidden() {
        return hidden;
    }

    /** Returns the tab color, or null. */
    public Color tabColor() {
        return tabColor;
    }

    /** Builder for {@link SheetOptions}. */
    public static final class Builder {
        private final TreeMap<Integer, Column> columns = new TreeMap<>();
        private int frozenRows;
        private int frozenColumns;
        private boolean showGridLines = true;
        private boolean hidden;
        private Color tabColor;

        private Builder() {
        }

        /** Freezes rows at the top, e.g. 1 to pin a header. */
        public Builder freezeRows(int rows) {
            if (rows < 0 || rows >= XlsxWriter.MAX_ROWS) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "frozen rows must be within 0..1048575");
            }
            frozenRows = rows;
            return this;
        }

        /** Freezes columns at the left. */
        public Builder freezeColumns(int count) {
            if (count < 0 || count >= XlsxWriter.MAX_COLUMNS) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "frozen columns must be within 0..16383");
            }
            frozenColumns = count;
            return this;
        }

        /** Sets the widths of columns 1 to n in characters of the default font. */
        public Builder columnWidths(double... widths) {
            if (widths.length > XlsxWriter.MAX_COLUMNS) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "a worksheet has at most 16384 columns");
            }
            for (int i = 0; i < widths.length; i++) {
                columns.merge(i + 1, Column.ofWidth(widths[i]), (existing, added) -> existing.withWidth(added.width()));
            }
            return this;
        }

        /** Defines the column with the given 1-based index. */
        public Builder column(int index, Column column) {
            if (index < 1 || index > XlsxWriter.MAX_COLUMNS) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "column index must be within 1..16384");
            }
            columns.put(index, Objects.requireNonNull(column, "column"));
            return this;
        }

        /** Hides the grid lines. */
        public Builder hideGridLines() {
            showGridLines = false;
            return this;
        }

        /** Hides the worksheet. A workbook needs at least one visible worksheet. */
        public Builder hidden() {
            hidden = true;
            return this;
        }

        /** Sets the color of the worksheet tab. */
        public Builder tabColor(Color color) {
            tabColor = color;
            return this;
        }

        /** Creates the options. */
        public SheetOptions build() {
            return new SheetOptions(this);
        }
    }
}
