package de.vensas.hypaxel;

/** Border line styles. */
public enum BorderStyle {
    /** No line. */
    NONE("none"),
    /** Thin line. */
    THIN("thin"),
    /** Medium line. */
    MEDIUM("medium"),
    /** Dashed line. */
    DASHED("dashed"),
    /** Dotted line. */
    DOTTED("dotted"),
    /** Thick line. */
    THICK("thick"),
    /** Double line. */
    DOUBLE("double"),
    /** Hairline. */
    HAIR("hair"),
    /** Medium dashed line. */
    MEDIUM_DASHED("mediumDashed"),
    /** Dash-dot line. */
    DASH_DOT("dashDot"),
    /** Medium dash-dot line. */
    MEDIUM_DASH_DOT("mediumDashDot"),
    /** Dash-dot-dot line. */
    DASH_DOT_DOT("dashDotDot"),
    /** Medium dash-dot-dot line. */
    MEDIUM_DASH_DOT_DOT("mediumDashDotDot"),
    /** Slanted dash-dot line. */
    SLANT_DASH_DOT("slantDashDot");

    private final String xmlName;

    BorderStyle(String xmlName) {
        this.xmlName = xmlName;
    }

    /** Returns the value used in the file format. */
    public String xmlName() {
        return xmlName;
    }
}
