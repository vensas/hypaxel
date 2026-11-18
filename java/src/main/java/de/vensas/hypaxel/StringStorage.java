package de.vensas.hypaxel;

/** How string cell values are stored. */
public enum StringStorage {
    /** Strings are written inline in each cell: constant memory and the fastest option. */
    INLINE,
    /**
     * Strings are de-duplicated into a shared string table: smaller files for repetitive text,
     * but memory grows with the number of distinct strings.
     */
    SHARED
}
