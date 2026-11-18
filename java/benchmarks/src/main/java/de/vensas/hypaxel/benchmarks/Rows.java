package de.vensas.hypaxel.benchmarks;

import java.util.AbstractList;
import java.util.List;

/** Row sources: held in memory for timing, or created on demand to observe the writers' own memory. */
final class Rows {
    private Rows() {
    }

    static List<Row> pregenerated(int count) {
        Row[] rows = new Row[count];
        for (int i = 0; i < count; i++) {
            rows[i] = Row.of(i);
        }
        return List.of(rows);
    }

    static List<Row> generatedOnDemand(int count) {
        return new AbstractList<>() {
            @Override
            public Row get(int index) {
                return Row.of(index);
            }

            @Override
            public int size() {
                return count;
            }
        };
    }
}
