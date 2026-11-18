package de.vensas.hypaxel.internal;

import java.util.Arrays;

/**
 * Shared string table: each distinct string is stored once. An open-addressing table of indices
 * avoids the boxed keys and entry objects of a {@code HashMap}.
 */
public final class SharedStrings {
    private String[] strings = new String[256];
    private int count;
    private int[] slots = new int[1024];
    private long references;

    /** Returns the index of the string, adding it when it is new. */
    public int indexOf(String value) {
        references++;
        int mask = slots.length - 1;
        int slot = spread(value.hashCode()) & mask;
        while (true) {
            int entry = slots[slot];
            if (entry == 0) {
                break;
            }
            if (strings[entry - 1].equals(value)) {
                return entry - 1;
            }
            slot = (slot + 1) & mask;
        }
        if (count == strings.length) {
            strings = Arrays.copyOf(strings, count * 2);
        }
        strings[count] = value;
        slots[slot] = ++count;
        if (count * 2 > slots.length) {
            rehash();
        }
        return count - 1;
    }

    private void rehash() {
        int[] table = new int[slots.length * 2];
        int mask = table.length - 1;
        for (int i = 0; i < count; i++) {
            int slot = spread(strings[i].hashCode()) & mask;
            while (table[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            table[slot] = i + 1;
        }
        slots = table;
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    public int size() {
        return count;
    }

    public long references() {
        return references;
    }

    public String get(int index) {
        return strings[index];
    }
}
