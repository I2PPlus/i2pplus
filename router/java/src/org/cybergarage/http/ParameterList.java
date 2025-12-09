/******************************************************************
 *
 *	CyberHTTP for Java
 *
 *	Copyright (C) Satoshi Konno 2002-2004
 *
 *	File: ParameterList.java
 *
 *	Revision;
 *
 *	02/01/04
 *		- first revision.
 *
 ******************************************************************/

package org.cybergarage.http;

import java.util.Vector;

/**
 * A collection of Parameter objects that extends Vector.
 * This class provides methods to manage and retrieve parameters by name or index,
 * commonly used for handling HTTP query parameters, form data, and headers.
 */
public class ParameterList extends Vector<Parameter> {
    /**
     * Creates a new empty ParameterList.
     */
    public ParameterList() {}

    /**
     * Gets the Parameter at the specified index.
     *
     * @param n the index of the parameter to retrieve
     * @return the Parameter at the specified index
     */
    public Parameter at(int n) {
        return get(n);
    }

    /**
     * Gets the Parameter at the specified index.
     *
     * @param n the index of the parameter to retrieve
     * @return the Parameter at the specified index
     */
    public Parameter getParameter(int n) {
        return get(n);
    }

    /**
     * Gets the Parameter with the specified name.
     *
     * @param name the name of the parameter to find
     * @return the Parameter with the specified name, or null if not found
     */
    public Parameter getParameter(String name) {
        if (name == null) return null;

        int nLists = size();
        for (int n = 0; n < nLists; n++) {
            Parameter param = at(n);
            if (name.compareTo(param.getName()) == 0) return param;
        }
        return null;
    }

    /**
     * Gets the value of the Parameter with the specified name.
     *
     * @param name the name of the parameter to find
     * @return the value of the parameter, or empty string if not found
     */
    public String getValue(String name) {
        Parameter param = getParameter(name);
        if (param == null) return "";
        return param.getValue();
    }
}
