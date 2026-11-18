package de.vensas.hypaxel;

/** Horizontal text alignment. */
public enum HorizontalAlignment {
    /** Text left, numbers right (the default). */
    GENERAL("general"),
    /** Left aligned. */
    LEFT("left"),
    /** Centered. */
    CENTER("center"),
    /** Right aligned. */
    RIGHT("right"),
    /** Repeats the content to fill the cell. */
    FILL("fill"),
    /** Justified. */
    JUSTIFY("justify"),
    /** Centered across adjacent empty cells. */
    CENTER_CONTINUOUS("centerContinuous"),
    /** Distributed. */
    DISTRIBUTED("distributed");

    private final String xmlName;

    HorizontalAlignment(String xmlName) {
        this.xmlName = xmlName;
    }

    /** Returns the value used in the file format. */
    public String xmlName() {
        return xmlName;
    }
}
