package de.vensas.hypaxel.internal;

import java.util.Arrays;

/**
 * The cells of one row in parallel arrays, reused from row to row. Text of inline strings,
 * formula results and errors is kept as UTF-8 in {@link #text} and only turned into a
 * {@code String} when a caller asks for it.
 */
public final class RowBuffer {
    public static final byte SHARED = 1;
    public static final byte TEXT = 2;
    public static final byte NUMBER = 3;
    public static final byte BOOL = 4;
    public static final byte DATE = 5;
    public static final byte ERROR = 6;

    private static final int INITIAL_CELLS = 16;

    /** 1-based row number. */
    public int number;
    public int count;
    /** 1-based column of each cell, ascending. */
    public int[] columns;
    public byte[] types;
    /** Number, date serial or boolean (0 or 1). */
    public double[] numbers;
    /** Shared string index, or the start of the cell's text in {@link #text}. */
    public int[] starts;
    /** The end of the cell's text in {@link #text}. */
    public int[] ends;
    /** {@code DateKind.ordinal() + 1} of date cells. */
    public byte[] kinds;
    public final ByteBuilder text;
    public final String[] sharedStrings;
    public final boolean date1904;

    public RowBuffer(String[] sharedStrings, boolean date1904) {
        this(INITIAL_CELLS, new ByteBuilder(1024), sharedStrings, date1904);
    }

    private RowBuffer(int capacity, ByteBuilder text, String[] sharedStrings, boolean date1904) {
        columns = new int[capacity];
        types = new byte[capacity];
        numbers = new double[capacity];
        starts = new int[capacity];
        ends = new int[capacity];
        kinds = new byte[capacity];
        this.text = text;
        this.sharedStrings = sharedStrings;
        this.date1904 = date1904;
    }

    /** Index of the cell in a 1-based column, or -1. */
    public int indexOf(int column) {
        int n = count;
        if (column >= 1 && column <= n && columns[column - 1] == column) {
            return column - 1;
        }
        int index = Arrays.binarySearch(columns, 0, n, column);
        return index >= 0 ? index : -1;
    }

    /** Text of a cell of type {@link #SHARED}, {@link #TEXT} or {@link #ERROR}. */
    public String string(int index) {
        if (types[index] == SHARED) {
            return sharedStrings[starts[index]];
        }
        return text.toString(starts[index], ends[index]);
    }

    /** UTF-8 length of a text cell's content without creating a String; -1 for shared strings. */
    public int textLength(int index) {
        return types[index] == SHARED ? -1 : ends[index] - starts[index];
    }

    int add(int column, byte type) {
        int i = count;
        if (i == columns.length) {
            grow();
        }
        columns[i] = column;
        types[i] = type;
        count = i + 1;
        return i;
    }

    private void grow() {
        int capacity = columns.length * 2;
        columns = Arrays.copyOf(columns, capacity);
        types = Arrays.copyOf(types, capacity);
        numbers = Arrays.copyOf(numbers, capacity);
        starts = Arrays.copyOf(starts, capacity);
        ends = Arrays.copyOf(ends, capacity);
        kinds = Arrays.copyOf(kinds, capacity);
    }

    /** Sorts the cells by column; of cells with the same column the last one wins. */
    void sortByColumn() {
        int n = count;
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            keys[i] = (long) columns[i] << 32 | i;
        }
        Arrays.sort(keys);
        int[] newColumns = new int[columns.length];
        byte[] newTypes = new byte[columns.length];
        double[] newNumbers = new double[columns.length];
        int[] newStarts = new int[columns.length];
        int[] newEnds = new int[columns.length];
        byte[] newKinds = new byte[columns.length];
        int w = 0;
        for (int k = 0; k < n; k++) {
            int column = (int) (keys[k] >>> 32);
            if (k + 1 < n && (int) (keys[k + 1] >>> 32) == column) {
                continue;
            }
            int i = (int) keys[k];
            newColumns[w] = column;
            newTypes[w] = types[i];
            newNumbers[w] = numbers[i];
            newStarts[w] = starts[i];
            newEnds[w] = ends[i];
            newKinds[w] = kinds[i];
            w++;
        }
        columns = newColumns;
        types = newTypes;
        numbers = newNumbers;
        starts = newStarts;
        ends = newEnds;
        kinds = newKinds;
        count = w;
    }

    /** An independent copy that later rows do not overwrite. */
    public RowBuffer copy() {
        int n = Math.max(1, count);
        ByteBuilder textCopy = new ByteBuilder(text.length());
        textCopy.append(text.data, 0, text.length());
        RowBuffer copy = new RowBuffer(n, textCopy, sharedStrings, date1904);
        copy.number = number;
        copy.count = count;
        System.arraycopy(columns, 0, copy.columns, 0, count);
        System.arraycopy(types, 0, copy.types, 0, count);
        System.arraycopy(numbers, 0, copy.numbers, 0, count);
        System.arraycopy(starts, 0, copy.starts, 0, count);
        System.arraycopy(ends, 0, copy.ends, 0, count);
        System.arraycopy(kinds, 0, copy.kinds, 0, count);
        return copy;
    }
}
