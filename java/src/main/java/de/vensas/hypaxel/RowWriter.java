package de.vensas.hypaxel;

/**
 * Writes the cells of one row for an item. The row is started and ended by the caller of this
 * function, so an implementation only calls the {@code write} methods:
 *
 * <pre>{@code
 * sheet.writeRows(orders, (row, order) -> row.write(order.id()).write(order.customer()).write(order.total(), money));
 * }</pre>
 *
 * @param <T> the item type
 */
@FunctionalInterface
public interface RowWriter<T> {
    /** Writes the cells for {@code item} into the current row of {@code row}. */
    void write(Worksheet row, T item);
}
