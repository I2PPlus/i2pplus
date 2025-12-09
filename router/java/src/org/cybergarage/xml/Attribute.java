/*
 * CyberXML for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.xml;

/**
 * Represents an XML attribute with name and value.
 *
 * <p>This class provides a simple container for XML attribute name-value pairs. Attributes can be
 * created independently or copied from existing attributes.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class Attribute {
    private String name = new String();
    private String value = new String();

    /** Creates an empty Attribute with empty name and value. */
    public Attribute() {}

    /**
     * Creates an Attribute with the specified name and value.
     *
     * @param name the attribute name
     * @param value the attribute value
     */
    public Attribute(String name, String value) {
        this();
        setName(name);
        setValue(value);
    }

    /**
     * Creates a copy of an existing Attribute.
     *
     * @param otherAttr the attribute to copy
     */
    public Attribute(Attribute otherAttr) {
        this();
        set(otherAttr);
    }

    ////////////////////////////////////////////////
    //	name
    ////////////////////////////////////////////////

    /**
     * Sets the name of this attribute.
     *
     * @param name the attribute name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Gets the name of this attribute.
     *
     * @return the attribute name
     */
    public String getName() {
        return name;
    }

    ////////////////////////////////////////////////
    //	value
    ////////////////////////////////////////////////

    /**
     * Sets the value of this attribute.
     *
     * @param value the attribute value
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * Gets the value of this attribute.
     *
     * @return the attribute value
     */
    public String getValue() {
        return value;
    }

    ////////////////////////////////////////////////
    //	set
    ////////////////////////////////////////////////

    /**
     * Copies the name and value from another attribute to this attribute.
     *
     * @param otherAttr the attribute to copy from
     */
    public void set(Attribute otherAttr) {
        setName(otherAttr.getName());
        setValue(otherAttr.getValue());
    }
}
