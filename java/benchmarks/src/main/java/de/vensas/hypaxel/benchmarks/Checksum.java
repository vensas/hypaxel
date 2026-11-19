package de.vensas.hypaxel.benchmarks;

import java.util.Locale;

/**
 * What every reader must agree on: the number of cells with a value, the sum of all numbers
 * (dates as their serial), the total length of all text and the number of TRUE values.
 */
final class Checksum {
    private long cells;
    private double numbers;
    private long textLength;
    private long trues;

    void number(double value) {
        cells++;
        numbers += value;
    }

    void string(String value) {
        cells++;
        textLength += value.length();
    }

    void bool(boolean value) {
        cells++;
        if (value) {
            trues++;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "cells=%d sum=%.6f text=%d true=%d", cells, numbers, textLength, trues);
    }
}
