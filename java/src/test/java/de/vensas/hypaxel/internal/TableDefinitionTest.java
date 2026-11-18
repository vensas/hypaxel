package de.vensas.hypaxel.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.vensas.hypaxel.XlsxException;
import org.junit.jupiter.api.Test;

class TableDefinitionTest {
    @Test
    void validatesNames() {
        for (String name : new String[] {"Orders", "_t", "Sales.2024", "Résumé", "R1C1x", "XFE1", "ABCD1", "\\name", "A0", "XFD1048577", "A12345678901"}) {
            assertDoesNotThrow(() -> TableDefinition.validateName(name), name);
        }
        for (String name : new String[] {"", "1abc", "A1", "xfd1048576", "R", "C", "r1c1", "RC", "R12", "C3", "has space", "a-b", "a:b",
            "x".repeat(256)}) {
            XlsxException e = assertThrows(XlsxException.class, () -> TableDefinition.validateName(name), name);
            assertEquals(XlsxException.Kind.INVALID_TABLE_NAME, e.kind());
        }
    }

    @Test
    void detectsOverlap() {
        assertTrue(TableDefinition.overlaps(new int[] {1, 1, 5, 5}, new int[] {5, 5, 6, 6}));
        assertFalse(TableDefinition.overlaps(new int[] {1, 1, 5, 5}, new int[] {6, 1, 9, 5}));
        assertFalse(TableDefinition.overlaps(new int[] {1, 1, 5, 5}, new int[] {1, 6, 5, 9}));
    }
}
