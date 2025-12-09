/******************************************************************
 *
 *	CyberHTTP for Java
 *
 *	Copyright (C) Satoshi Konno 2002-2004
 *
 *	File: Parameter.java
 *
 *	Revision;
 *
 *	02/01/04
 *		- first revision.
 *
 ******************************************************************/

package org.cybergarage.http;

/**
 * Represents a name-value parameter pair used in HTTP requests and responses.
 * This class provides a simple container for storing parameter names and their corresponding values,
 * commonly used for query parameters, form data, and HTTP headers.
 */
public class Parameter {
    private String name = new String();
    private String value = new String();

    /**
     * Creates a new empty Parameter with null name and value.
     */
    public Parameter() {}

    /**
     * Creates a new Parameter with the specified name and value.
     *
     * @param name the parameter name
     * @param value the parameter value
     */
    public Parameter(String name, String value) {
        setName(name);
        setValue(value);
    }

    ////////////////////////////////////////////////
    //	name
    ////////////////////////////////////////////////

    /**
     * Sets the parameter name.
     *
     * @param name the parameter name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Gets the parameter name.
     *
     * @return the parameter name
     */
    public String getName() {
        return name;
    }

    ////////////////////////////////////////////////
    //	value
    ////////////////////////////////////////////////

    /**
     * Sets the parameter value.
     *
     * @param value the parameter value to set
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * Gets the parameter value.
     *
     * @return the parameter value
     */
    public String getValue() {
        return value;
    }
}
