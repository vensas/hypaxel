package de.vensas.hypaxel.benchmarks;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** JMH benchmark: one operation writes a complete workbook to a discarding stream. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class WriteBenchmark {
    @Param("100000")
    public int rows;

    @Param
    public Variant variant;

    private List<Row> data;

    @Setup
    public void setup() {
        data = Rows.pregenerated(rows);
    }

    @Benchmark
    public long write() throws IOException {
        CountingSink sink = new CountingSink();
        variant.write(data, sink);
        return sink.count();
    }
}
