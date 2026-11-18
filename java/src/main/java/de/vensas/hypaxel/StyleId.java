package de.vensas.hypaxel;

/**
 * Handle to a cell format registered with {@link XlsxWriter#addStyle(Style)}. Only valid for the
 * writer that created it.
 */
public final class StyleId {
    /** The workbook's default format. */
    public static final StyleId DEFAULT = new StyleId(0);

    private final int index;

    StyleId(int index) {
        this.index = index;
    }

    /** Returns the index into the workbook's cell format table. */
    public int index() {
        return index;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StyleId id && id.index == index;
    }

    @Override
    public int hashCode() {
        return index;
    }

    @Override
    public String toString() {
        return "StyleId(" + index + ")";
    }
}
