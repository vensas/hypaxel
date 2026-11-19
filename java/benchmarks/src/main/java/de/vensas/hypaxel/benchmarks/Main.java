package de.vensas.hypaxel.benchmarks;

import de.vensas.hypaxel.StringStorage;
import de.vensas.hypaxel.XlsxOptions;
import de.vensas.hypaxel.XlsxWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Entry point of the benchmark jar.
 *
 * <pre>
 * java -jar benchmarks.jar jmh [JMH options]         JMH run (time and allocation per workbook)
 * java -jar benchmarks.jar time VARIANT ROWS         stopwatch run: 2 warm-ups, 5 timed runs
 * java -jar benchmarks.jar once VARIANT ROWS         one run with rows created on demand
 * java -jar benchmarks.jar generate FILE ROWS inline|shared   write the read benchmark's input
 * java -jar benchmarks.jar readtime READER FILE      stopwatch run of a reader: 2 warm-ups, 5 timed runs
 * java -jar benchmarks.jar read READER FILE          one cold read, e.g. for peak memory
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
        } else if (args.length == 4 && args[0].equals("generate")) {
            generate(Path.of(args[1]), Integer.parseInt(args[2]), StringStorage.valueOf(args[3].toUpperCase(Locale.ROOT)));
        } else if (args.length == 3 && args[0].equals("readtime")) {
            readTime(ReadVariant.valueOf(args[1]), Path.of(args[2]));
        } else if (args.length == 3 && args[0].equals("read")) {
            readOnce(ReadVariant.valueOf(args[1]), Path.of(args[2]));
        } else {
            System.err.println("usage: jmh [options] | time VARIANT ROWS | once VARIANT|BASELINE ROWS");
            System.err.println("       generate FILE ROWS inline|shared | readtime READER FILE | read READER FILE");
            System.err.println("variants: " + List.of(Variant.values()));
            System.err.println("readers: " + List.of(ReadVariant.values()));
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

    /** Writes the write benchmark's ten columns with Hypaxel, as input for the read benchmark. */
    private static void generate(Path file, int rowCount, StringStorage storage) throws IOException {
        XlsxOptions options = XlsxOptions.builder().stringStorage(storage).build();
        try (XlsxWriter writer = XlsxWriter.create(file, options)) {
            de.vensas.hypaxel.Worksheet sheet = writer.addWorksheet("Data");
            for (int i = 0; i < rowCount; i++) {
                Row row = Row.of(i);
                sheet.write(row.id()).write(row.name()).write(row.category()).write(row.price()).write(row.amount())
                        .write(row.created()).write(row.active()).write(row.quantity()).write(row.comment()).write(row.ratio());
                sheet.endRow();
            }
        }
        System.out.printf(Locale.ROOT, "%s rows=%d storage=%s bytes=%d%n", file, rowCount, storage, Files.size(file));
    }

    private static void readTime(ReadVariant variant, Path file) throws IOException {
        Checksum expected = null;
        for (int i = 0; i < WARMUPS; i++) {
            expected = variant.read(file);
        }
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long totalNanos = 0;
        long bestNanos = Long.MAX_VALUE;
        long allocated = 0;
        for (int i = 0; i < RUNS; i++) {
            long allocatedBefore = threads.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            Checksum sum = variant.read(file);
            long elapsed = System.nanoTime() - start;
            allocated += threads.getThreadAllocatedBytes(thread) - allocatedBefore;
            totalNanos += elapsed;
            bestNanos = Math.min(bestNanos, elapsed);
            if (!sum.toString().equals(expected.toString())) {
                throw new IllegalStateException("checksum changed between runs: " + sum + " vs " + expected);
            }
        }
        System.out.printf(Locale.ROOT, "%s file=%s mean_ms=%.0f best_ms=%.0f allocated_mb=%.0f %s%n",
                variant, file.getFileName(), totalNanos / (double) RUNS / 1e6, bestNanos / 1e6, allocated / (double) RUNS / (1 << 20), expected);
    }

    /** A single cold read; the caller measures the process's peak memory, e.g. with /usr/bin/time -l. */
    private static void readOnce(ReadVariant variant, Path file) throws IOException {
        long start = System.nanoTime();
        Checksum sum = variant.read(file);
        System.out.printf(Locale.ROOT, "%s file=%s ms=%.0f %s%n", variant, file.getFileName(), (System.nanoTime() - start) / 1e6, sum);
    }
}
