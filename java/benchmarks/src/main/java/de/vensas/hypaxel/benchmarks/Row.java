package de.vensas.hypaxel.benchmarks;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One benchmark row: the same ten columns the other Hypaxel implementations are measured with. */
public record Row(
        int id,
        String name,
        String category,
        double price,
        BigDecimal amount,
        LocalDateTime created,
        boolean active,
        long quantity,
        String comment,
        double ratio) {

    private static final String[] CATEGORIES = {"Hardware", "Software", "Services", "Consulting", "Support", "Training"};
    private static final LocalDateTime START = LocalDateTime.of(2024, 1, 1, 0, 0);

    static Row of(int i) {
        return new Row(
                i,
                "Customer " + (i % 5000),
                CATEGORIES[i % CATEGORIES.length],
                i * 1.25,
                BigDecimal.valueOf(i, 2),
                START.plusMinutes(i),
                i % 2 == 0,
                i * 3L,
                "Unique comment #" + i,
                i / 7.0);
    }
}
