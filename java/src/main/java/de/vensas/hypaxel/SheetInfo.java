package de.vensas.hypaxel;

import java.util.Objects;

/**
 * A sheet listed in a workbook that is being read.
 *
 * @param name the sheet name as shown on its tab
 * @param visibility whether the sheet is shown in the tab bar
 * @param kind whether the sheet holds cells or a chart
 */
public record SheetInfo(String name, SheetVisibility visibility, SheetKind kind) {
    /** Validates the components. */
    public SheetInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(kind, "kind");
    }
}
