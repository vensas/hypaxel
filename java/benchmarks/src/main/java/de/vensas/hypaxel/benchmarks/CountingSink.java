package de.vensas.hypaxel.benchmarks;

import java.io.OutputStream;

/** Discards the workbook and counts its bytes, so that only the writer's own memory is measured. */
final class CountingSink extends OutputStream {
    private long count;

    @Override
    public void write(int b) {
        count++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
        count += length;
    }

    long count() {
        return count;
    }
}
