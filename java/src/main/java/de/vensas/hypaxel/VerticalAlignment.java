package de.vensas.hypaxel;

/** Vertical text alignment. */
public enum VerticalAlignment {
    /** Bottom aligned (the default). */
    BOTTOM("bottom"),
    /** Top aligned. */
    TOP("top"),
    /** Centered. */
    CENTER("center"),
    /** Justified. */
    JUSTIFY("justify"),
    /** Distributed. */
    DISTRIBUTED("distributed");

    private final String xmlName;

    VerticalAlignment(String xmlName) {
        this.xmlName = xmlName;
    }

    /** Returns the value used in the file format. */
    public String xmlName() {
        return xmlName;
    }
}
