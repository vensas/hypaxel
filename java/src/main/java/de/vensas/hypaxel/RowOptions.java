package de.vensas.hypaxel;

import java.util.Objects;

/**
 * Settings for one row, used with {@link Worksheet#beginRow(RowOptions)}. Immutable; create
 * instances with {@link #builder()}.
 *
 * <pre>{@code
 * Worksheet sheet = writer.addWorksheet("Tree", SheetOptions.builder().rowOutlineLevels(1).outlineSummaryAbove().build());
 * sheet.addRow("Region North", 300);
 * sheet.beginRow(RowOptions.builder().outlineLevel(1).build()).addRow("Store A", 120);
 * }</pre>
 */
public final class RowOptions {
    /** A row with the default height and format, outside any group. */
    public static final RowOptions DEFAULT = builder().build();

    private final double height;
    private final StyleId style;
    private final int outlineLevel;
    private final boolean hidden;
    private final boolean collapsed;

    private RowOptions(Builder builder) {
        height = builder.height;
        style = builder.style;
        outlineLevel = builder.outlineLevel;
        hidden = builder.hidden;
        collapsed = builder.collapsed;
    }

    /** Returns a builder with the default settings. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns whether a height is set. */
    public boolean hasHeight() {
        return !Double.isNaN(height);
    }

    /** Returns the height in points, or NaN for the default height. */
    public double height() {
        return height;
    }

    /** Returns the format of the row's empty cells. */
    public StyleId style() {
        return style;
    }

    /** Returns the outline (grouping) level, 0 to 7. */
    public int outlineLevel() {
        return outlineLevel;
    }

    /** Returns whether the row is hidden. */
    public boolean hidden() {
        return hidden;
    }

    /** Returns whether the row is the summary row of a collapsed group. */
    public boolean collapsed() {
        return collapsed;
    }

    /** Builder for {@link RowOptions}. */
    public static final class Builder {
        private double height = Double.NaN;
        private StyleId style = StyleId.DEFAULT;
        private int outlineLevel;
        private boolean hidden;
        private boolean collapsed;

        private Builder() {
        }

        /** Sets the height in points, 0 to 409. */
        public Builder height(double points) {
            if (!(points >= 0 && points <= 409)) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "row height must be within 0..409 points");
            }
            height = points;
            return this;
        }

        /** Sets the format of the row's empty cells. */
        public Builder style(StyleId value) {
            style = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the outline (grouping) level, 0 to 7. It must not exceed
         * {@link SheetOptions#rowOutlineLevels()} of the worksheet.
         */
        public Builder outlineLevel(int level) {
            if (level < 0 || level > 7) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "row outline level must be within 0..7");
            }
            outlineLevel = level;
            return this;
        }

        /** Hides the row, e.g. a row inside a collapsed group. */
        public Builder hidden() {
            hidden = true;
            return this;
        }

        /** Marks the summary row of a collapsed group; the group's rows should be hidden. */
        public Builder collapsed() {
            collapsed = true;
            return this;
        }

        /** Creates the options. */
        public RowOptions build() {
            return new RowOptions(this);
        }
    }
}
