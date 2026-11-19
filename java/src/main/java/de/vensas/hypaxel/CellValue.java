package de.vensas.hypaxel;

import java.util.Objects;

/**
 * A cell that has a value, with its 1-based column.
 *
 * @param column the 1-based column (A = 1)
 * @param value the value; never {@link Value#EMPTY}
 */
public record CellValue(int column, Value value) {
    /** Validates the components. */
    public CellValue {
        Objects.requireNonNull(value, "value");
    }
}
