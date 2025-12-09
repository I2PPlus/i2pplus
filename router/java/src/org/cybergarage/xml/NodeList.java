/*
 * CyberXML for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.xml;

import java.util.Vector;

/**
 * A vector-based collection of XML nodes.
 *
 * <p>This class extends Vector to provide specialized methods for managing collections of Node
 * objects. It includes methods for finding nodes by name or by name suffix.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class NodeList extends Vector<Node> {
    /** Creates an empty NodeList. */
    public NodeList() {}

    /**
     * Gets the node at the specified index.
     *
     * @param n the index of the node to retrieve
     * @return the node at the specified index
     */
    public Node getNode(int n) {
        return get(n);
    }

    /**
     * Gets the first node with the specified name.
     *
     * @param name the name of the node to find
     * @return the first node with the specified name, or null if not found
     */
    public Node getNode(String name) {
        if (name == null) return null;

        int nLists = size();
        for (int n = 0; n < nLists; n++) {
            Node node = getNode(n);
            String nodeName = node.getName();
            if (name.compareTo(nodeName) == 0) return node;
        }
        return null;
    }

    /**
     * Gets the first node whose name ends with the specified string.
     *
     * @param name the suffix to match
     * @return the first node with name ending with the specified string, or null if not found
     */
    public Node getEndsWith(String name) {
        if (name == null) return null;

        int nLists = size();
        for (int n = 0; n < nLists; n++) {
            Node node = getNode(n);
            String nodeName = node.getName();
            if (nodeName == null) continue;
            if (nodeName.endsWith(name) == true) return node;
        }
        return null;
    }
}
