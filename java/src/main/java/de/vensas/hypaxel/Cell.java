package de.vensas.hypaxel;

import java.util.Objects;

/**
 * A cell value with its own style, a formula or a styled blank, for use in rows passed to
 * {@link Worksheet#addRow(Object...)} and friends.
 *
 * <pre>{@code
 * sheet.addRow("Total", Cell.blank(border), Cell.formula("SUM(C2:C9)", money));
 * }</pre>
 */
public final class Cell {
    private final Object value;
    private final StyleId style;
    private final boolean formula;

    private Cell(Object value, StyleId style, boolean formula) {
        this.value = value;
        this.style = style;
        this.formula = formula;
    }

    /** Creates a value with its own style. */
    public static Cell styled(Object value, StyleId style) {
        return new Cell(value, Objects.requireNonNull(style, "style"), false);
    }

    /** Creates a formula such as {@code "SUM(A1:A9)"}; a leading {@code =} is optional. */
    public static Cell formula(String formula) {
        return new Cell(Objects.requireNonNull(formula, "formula"), null, true);
    }

    /** Creates a formula with a style. */
    public static Cell formula(String formula, StyleId style) {
        return new Cell(Objects.requireNonNull(formula, "formula"), Objects.requireNonNull(style, "style"), true);
    }

    /** Creates an empty cell that carries a format, e.g. a border or fill. */
    public static Cell blank(StyleId style) {
        return new Cell(null, Objects.requireNonNull(style, "style"), false);
    }

    /** Returns the value, or the formula text for a formula. */
    public Object value() {
        return value;
    }

    /** Returns the cell's own style, or null when it uses the row's style. */
    public StyleId style() {
        return style;
    }

    /** Returns whether the cell is a formula. */
    public boolean isFormula() {
        return formula;
    }
}
