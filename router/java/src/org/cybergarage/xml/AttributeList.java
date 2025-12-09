/******************************************************************
 * CyberXML for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.xml;

import java.util.Vector;

/**
 * A vector-based collection of XML attributes.
 *
 * <p>This class extends Vector to provide specialized methods for managing collections of Attribute
 * objects. It includes methods for finding attributes by name.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class AttributeList extends Vector<Attribute> {
    /** Creates an empty AttributeList. */
    public AttributeList() {}

    /**
     * Gets the attribute at the specified index.
     *
     * @param n the index of the attribute to retrieve
     * @return the attribute at the specified index
     */
    public Attribute getAttribute(int n) {
        return get(n);
    }

    /**
     * Gets the first attribute with the specified name.
     *
     * @param name the name of the attribute to find
     * @return the first attribute with the specified name, or null if not found
     */
    public Attribute getAttribute(String name) {
        if (name == null) return null;

        int nLists = size();
        for (int n = 0; n < nLists; n++) {
            Attribute elem = getAttribute(n);
            if (name.compareTo(elem.getName()) == 0) return elem;
        }
        return null;
    }
}
