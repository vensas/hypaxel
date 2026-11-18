package de.vensas.hypaxel.benchmarks;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/**
 * Entry point of the benchmark jar.
 *
 * <pre>
 * java -jar benchmarks.jar jmh [JMH options]         JMH run (time and allocation per workbook)
 * java -jar benchmarks.jar time VARIANT ROWS         stopwatch run: 2 warm-ups, 5 timed runs
 * java -jar benchmarks.jar once VARIANT ROWS         one run with rows created on demand
 * </pre>
 */
public final class Main {
    private static final int WARMUPS = 2;
    private static final int RUNS = 5;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && args[0].equals("jmh")) {
            org.openjdk.jmh.Main.main(java.util.Arrays.copyOfRange(args, 1, args.length));
        } else if (args.length == 3 && args[0].equals("time")) {
            time(Variant.valueOf(args[1]), Integer.parseInt(args[2]));
        } else if (args.length == 3 && args[0].equals("once")) {
            once(args[1], Integer.parseInt(args[2]));
        } else {
            System.err.println("usage: jmh [options] | time VARIANT ROWS | once VARIANT|BASELINE ROWS");
            System.err.println("variants: " + List.of(Variant.values()));
            System.exit(2);
        }
    }

    private static void time(Variant variant, int rowCount) throws IOException {
        List<Row> rows = Rows.pregenerated(rowCount);
        for (int i = 0; i < WARMUPS; i++) {
            variant.write(rows, new CountingSink());
        }
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long totalNanos = 0;
        long bestNanos = Long.MAX_VALUE;
        long allocated = 0;
        long size = 0;
        for (int i = 0; i < RUNS; i++) {
            CountingSink sink = new CountingSink();
            long allocatedBefore = threads.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            variant.write(rows, sink);
            long elapsed = System.nanoTime() - start;
            allocated += threads.getThreadAllocatedBytes(thread) - allocatedBefore;
            totalNanos += elapsed;
            bestNanos = Math.min(bestNanos, elapsed);
            size = sink.count();
        }
        System.out.printf(Locale.ROOT, "%s rows=%d mean_ms=%.0f best_ms=%.0f allocated_mb=%.2f file_mb=%.1f%n",
                variant, rowCount, totalNanos / (double) RUNS / 1e6, bestNanos / 1e6, allocated / (double) RUNS / (1 << 20),
                size / (double) (1 << 20));
    }

    /** A single cold run; the caller measures the process's peak memory, e.g. with /usr/bin/time -l. */
    private static void once(String name, int rowCount) throws IOException {
        List<Row> rows = Rows.generatedOnDemand(rowCount);
        long start = System.nanoTime();
        long size = 0;
        if (name.equals("BASELINE")) {
            for (Row row : rows) {
                size += row.id();
            }
        } else {
            CountingSink sink = new CountingSink();
            Variant.valueOf(name).write(rows, sink);
            size = sink.count();
        }
        System.out.printf(Locale.ROOT, "%s rows=%d ms=%.0f bytes=%d%n", name, rowCount, (System.nanoTime() - start) / 1e6, size);
    }
}
