package de.vensas.hypaxel;

import java.util.Objects;

/** Cell borders. Immutable; create instances with {@link #all} or {@link #builder()}. */
public final class Border {
    private final BorderLine left;
    private final BorderLine right;
    private final BorderLine top;
    private final BorderLine bottom;

    private Border(Builder builder) {
        left = builder.left;
        right = builder.right;
        top = builder.top;
        bottom = builder.bottom;
    }

    /** Creates a border with the same line in the automatic color on all four edges. */
    public static Border all(BorderStyle style) {
        return all(style, null);
    }

    /** Creates a border with the same line on all four edges. */
    public static Border all(BorderStyle style, Color color) {
        BorderLine line = new BorderLine(style, color);
        return builder().left(line).right(line).top(line).bottom(line).build();
    }

    /** Returns a builder without any lines. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the left edge. */
    public BorderLine left() {
        return left;
    }

    /** Returns the right edge. */
    public BorderLine right() {
        return right;
    }

    /** Returns the top edge. */
    public BorderLine top() {
        return top;
    }

    /** Returns the bottom edge. */
    public BorderLine bottom() {
        return bottom;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Border b && left.equals(b.left) && right.equals(b.right) && top.equals(b.top) && bottom.equals(b.bottom);
    }

    @Override
    public int hashCode() {
        return Objects.hash(left, right, top, bottom);
    }

    @Override
    public String toString() {
        return "Border[left=" + left + ", right=" + right + ", top=" + top + ", bottom=" + bottom + "]";
    }

    /** Builder for {@link Border}. */
    public static final class Builder {
        private BorderLine left = BorderLine.NONE;
        private BorderLine right = BorderLine.NONE;
        private BorderLine top = BorderLine.NONE;
        private BorderLine bottom = BorderLine.NONE;

        private Builder() {
        }

        /** Sets the left edge. */
        public Builder left(BorderLine line) {
            left = Objects.requireNonNull(line, "line");
            return this;
        }

        /** Sets the right edge. */
        public Builder right(BorderLine line) {
            right = Objects.requireNonNull(line, "line");
            return this;
        }

        /** Sets the top edge. */
        public Builder top(BorderLine line) {
            top = Objects.requireNonNull(line, "line");
            return this;
        }

        /** Sets the bottom edge. */
        public Builder bottom(BorderLine line) {
            bottom = Objects.requireNonNull(line, "line");
            return this;
        }

        /** Creates the border. */
        public Border build() {
            return new Border(this);
        }
    }
}
