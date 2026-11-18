package de.vensas.hypaxel.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SharedStringsTest {
    @Test
    void assignsStableIndicesAcrossGrowth() {
        SharedStrings table = new SharedStrings();
        for (int i = 0; i < 20_000; i++) {
            assertEquals(i, table.indexOf("value " + i));
        }
        for (int i = 0; i < 20_000; i += 7) {
            assertEquals(i, table.indexOf(new String("value " + i)));
        }
        assertEquals(20_000, table.size());
        assertEquals(20_000 + 2858, table.references());
        assertEquals("value 19999", table.get(19_999));
    }

    @Test
    void separatesCollidingHashes() {
        SharedStrings table = new SharedStrings();
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertEquals(0, table.indexOf("Aa"));
        assertEquals(1, table.indexOf("BB"));
        assertEquals(0, table.indexOf("Aa"));
        assertEquals(1, table.indexOf("BB"));
        assertEquals(2, table.indexOf(""));
        assertEquals(3, table.size());
    }
}
