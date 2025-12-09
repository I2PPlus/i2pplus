/******************************************************************
 *
 *	CyberXML for Java
 *
 *	Copyright (C) Satoshi Konno 2002
 *
 *	File: Element.java
 *
 *	Revision;
 *
 *	11/27/02
 *		- first revision.
 *	11/01/03
 *		- Terje Bakken
 *		- fixed missing escaping of reserved XML characters
 *	11/19/04
 *		- Theo Beisch <theo.beisch@gmx.de>
 *		- Added "&" and "\"" "\\" to toXMLString().
 *	11/19/04
 *		- Theo Beisch <theo.beisch@gmx.de>
 *		- Changed XML::output() to use short notation when the tag value is null.
 *	12/02/04
 *		- Brian Owens <brian@b-owens.com>
 *		- Fixed toXMLString() to convert from "'" to "&apos;" instead of "\".
 *	11/07/05
 *		- Changed toString() to return as utf-8 string.
 *	02/08/08
 *		- Added addValue().
 *
 ******************************************************************/

package org.cybergarage.xml;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Represents an XML node with name, value, attributes, and child nodes.
 * 
 * <p>This class provides a tree-like structure for representing XML documents.
 * Each node can have a name, value, attributes, and child nodes. Nodes can be
 * created independently or as part of an XML document tree.</p>
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Node name and value management</li>
 *   <li>Attribute storage and retrieval</li>
 *   <li>Parent-child node relationships</li>
 *   <li>XML string output with proper escaping</li>
 *   <li>User data attachment for application-specific information</li>
 * </ul>
 * 
 * @author Satoshi Konno
 * @since 1.0
 */
public class Node {

    /** Creates a Node with empty UserData and no Parent Node. */
    public Node() {
        setUserData(null);
        setParentNode(null);
    }

    /**
     * Creates a Node with specified name.
     *
     * @param name name to set for the node
     */
    public Node(String name) {
        this();
        setName(name);
    }

    /**
     * Creates a Node with namespace and name.
     *
     * @param ns namespace prefix
     * @param name name to set for the node
     */
    public Node(String ns, String name) {
        this();
        setName(ns, name);
    }

    /**
     * Creates a copy of an existing node.
     *
     * @param otherNode node to copy
     */
    public Node(Node otherNode) {
        this();
        set(otherNode);
    }

    ////////////////////////////////////////////////
    //	parent node
    ////////////////////////////////////////////////

    private Node parentNode = null;

    /**
     * Sets the parent node of this node.
     *
     * @param node the parent node, or null if this node has no parent
     */
    public void setParentNode(Node node) {
        parentNode = node;
    }

    /**
     * Gets the parent node of this node.
     *
     * @return the parent node, or null if this node has no parent
     */
    public Node getParentNode() {
        return parentNode;
    }

    ////////////////////////////////////////////////
    //	root node
    ////////////////////////////////////////////////

    /**
     * Gets the root node of the tree this node belongs to.
     *
     * @return the root node, or this node if it has no parent
     */
    public Node getRootNode() {
        Node rootNode = null;
        Node parentNode = getParentNode();
        while (parentNode != null) {
            rootNode = parentNode;
            parentNode = rootNode.getParentNode();
        }
        return rootNode;
    }

    ////////////////////////////////////////////////
    //	name
    ////////////////////////////////////////////////

    private String name = new String();

    /**
     * Sets the name of this node.
     *
     * @param name name to set for the node
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Sets the namespace and name of this node.
     *
     * @param ns namespace prefix
     * @param name name to set for the node
     */
    public void setName(String ns, String name) {
        this.name = ns + ":" + name;
    }

    /**
     * Gets the name of this node.
     *
     * @return name of the node
     */
    public String getName() {
        return name;
    }

    /**
     * Checks if this node's name matches the specified value.
     *
     * @param value the name to compare against
     * @return true if the names match, false otherwise
     */
    public boolean isName(String value) {
        return name.equals(value);
    }

    ////////////////////////////////////////////////
    //	value
    ////////////////////////////////////////////////

    private String value = new String();

    /**
     * Sets the value of this node.
     *
     * @param value value to set for the node
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * Sets the value of this node as an integer.
     *
     * @param value integer value to set for the node
     */
    public void setValue(int value) {
        setValue(Integer.toString(value));
    }

    /**
     * Adds a value to this node's existing value.
     *
     * @param value value to append to existing value
     */
    public void addValue(String value) {
        if (this.value == null) {
            this.value = value;
            return;
        }
        if (value != null) this.value += value;
    }

    /**
     * Gets the value of this node.
     *
     * @return value of the node
     */
    public String getValue() {
        return value;
    }

    ////////////////////////////////////////////////
    //	Attribute (Basic)
    ////////////////////////////////////////////////

    private AttributeList attrList = new AttributeList();

    /**
     * Gets the number of attributes this node has.
     *
     * @return the number of attributes
     */
    public int getNAttributes() {
        return attrList.size();
    }

    /**
     * Gets the attribute at the specified index.
     *
     * @param index the index of the attribute to retrieve
     * @return the attribute at the specified index
     */
    public Attribute getAttribute(int index) {
        return attrList.getAttribute(index);
    }

    /**
     * Gets the attribute with the specified name.
     *
     * @param name the name of the attribute to retrieve
     * @return the attribute with the specified name, or null if not found
     */
    public Attribute getAttribute(String name) {
        return attrList.getAttribute(name);
    }

    /**
     * Adds an attribute to this node.
     *
     * @param attr the attribute to add
     */
    public void addAttribute(Attribute attr) {
        attrList.add(attr);
    }

    /**
     * Inserts an attribute at the specified index.
     *
     * @param attr the attribute to insert
     * @param index the index at which to insert the attribute
     */
    public void insertAttributeAt(Attribute attr, int index) {
        attrList.insertElementAt(attr, index);
    }

    /**
     * Adds an attribute with the specified name and value.
     *
     * @param name the attribute name
     * @param value the attribute value
     */
    public void addAttribute(String name, String value) {
        Attribute attr = new Attribute(name, value);
        addAttribute(attr);
    }

    /**
     * Removes the specified attribute from this node.
     *
     * @param attr the attribute to remove
     * @return true if the attribute was removed, false if not found
     */
    public boolean removeAttribute(Attribute attr) {
        return attrList.remove(attr);
    }

    /**
     * Removes the attribute with the specified name from this node.
     *
     * @param name the name of the attribute to remove
     * @return true if the attribute was removed, false if not found
     */
    public boolean removeAttribute(String name) {
        return removeAttribute(getAttribute(name));
    }

    /**
     * Removes all attributes from this node.
     */
    public void removeAllAttributes() {
        attrList.clear();
    }

    /**
     * Checks if this node has any attributes.
     *
     * @return true if this node has attributes, false otherwise
     */
    public boolean hasAttributes() {
        if (0 < getNAttributes()) return true;
        return false;
    }

    ////////////////////////////////////////////////
    //	Attribute (Extention)
    ////////////////////////////////////////////////

    /**
     * Sets an attribute value. If the attribute already exists, its value is updated.
     * If it doesn't exist, a new attribute is created.
     *
     * @param name the attribute name
     * @param value the attribute value
     */
    public void setAttribute(String name, String value) {
        Attribute attr = getAttribute(name);
        if (attr != null) {
            attr.setValue(value);
            return;
        }
        attr = new Attribute(name, value);
        addAttribute(attr);
    }

    /**
     * Sets an attribute value as an integer. If the attribute already exists, its value is updated.
     * If it doesn't exist, a new attribute is created.
     *
     * @param name the attribute name
     * @param value the integer attribute value
     */
    public void setAttribute(String name, int value) {
        setAttribute(name, Integer.toString(value));
    }

    /**
     * Gets the value of an attribute with the specified name.
     *
     * @param name the attribute name
     * @return the attribute value, or empty string if not found
     */
    public String getAttributeValue(String name) {
        Attribute attr = getAttribute(name);
        if (attr != null) return attr.getValue();
        return "";
    }

    /**
     * Gets the integer value of an attribute with the specified name.
     *
     * @param name the attribute name
     * @return the attribute value as integer, or 0 if not found or invalid
     */
    public int getAttributeIntegerValue(String name) {
        String val = getAttributeValue(name);
        try {
            return Integer.parseInt(val);
        } catch (Exception e) {
        }
        return 0;
    }

    ////////////////////////////////////////////////
    //	Attribute (xmlns)
    ////////////////////////////////////////////////

    /**
     * Sets a namespace attribute for this node.
     *
     * @param ns the namespace prefix
     * @param value the namespace URI
     */
    public void setNameSpace(String ns, String value) {
        setAttribute("xmlns:" + ns, value);
    }

    ////////////////////////////////////////////////
    //	set
    ////////////////////////////////////////////////

    /**
     * Copies all properties from another node to this node.
     * This includes name, value, attributes, and child nodes.
     *
     * @param otherNode the node to copy from
     * @return true if the copy was successful, false if otherNode is null
     */
    public boolean set(Node otherNode) {
        if (otherNode == null) return false;

        setName(otherNode.getName());
        setValue(otherNode.getValue());

        removeAllAttributes();
        int nOtherAttributes = otherNode.getNAttributes();
        for (int n = 0; n < nOtherAttributes; n++) {
            Attribute otherAttr = otherNode.getAttribute(n);
            Attribute thisAttr = new Attribute(otherAttr);
            addAttribute(thisAttr);
        }

        removeAllNodes();
        int nOtherChildNodes = otherNode.getNNodes();
        for (int n = 0; n < nOtherChildNodes; n++) {
            Node otherChildNode = otherNode.getNode(n);
            Node thisChildNode = new Node();
            thisChildNode.set(otherChildNode);
            addNode(thisChildNode);
        }

        return true;
    }

    ////////////////////////////////////////////////
    //	equals
    ////////////////////////////////////////////////
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if (!(o instanceof Node)) return false;

        Node otherNode = (Node) o;
        String thisNodeString = toString();
        String otherNodeString = otherNode.toString();

        return thisNodeString.equals(otherNodeString);
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    ////////////////////////////////////////////////
    //	Child node
    ////////////////////////////////////////////////

    private NodeList nodeList = new NodeList();

    /**
     * Gets the number of child nodes this node has.
     *
     * @return the number of child nodes
     */
    public int getNNodes() {
        return nodeList.size();
    }

    /**
     * Gets the child node at the specified index.
     *
     * @param index the index of the child node to retrieve
     * @return the child node at the specified index
     */
    public Node getNode(int index) {
        return nodeList.getNode(index);
    }

    /**
     * Gets the first child node with the specified name.
     *
     * @param name the name of the child node to retrieve
     * @return the child node with the specified name, or null if not found
     */
    public Node getNode(String name) {
        return nodeList.getNode(name);
    }

    /**
     * Gets the first child node whose name ends with the specified string.
     *
     * @param name the suffix to match
     * @return the first child node with name ending with the specified string, or null if not found
     */
    public Node getNodeEndsWith(String name) {
        return nodeList.getEndsWith(name);
    }

    /**
     * Adds a child node to this node.
     *
     * @param node child node to add
     */
    public void addNode(Node node) {
        node.setParentNode(this);
        nodeList.add(node);
    }

    /**
     * Inserts a child node at the specified index.
     *
     * @param node the child node to insert
     * @param index the index at which to insert the node
     */
    public void insertNode(Node node, int index) {
        node.setParentNode(this);
        nodeList.insertElementAt(node, index);
    }

    /**
     * Gets the index of the first child node with the specified name.
     *
     * @param name the name of the child node to find
     * @return the index of the child node, or -1 if not found
     */
    public int getIndex(String name) {
        int index = -1;
        for (Iterator<Node> i = nodeList.iterator(); i.hasNext(); ) {
            index++;
            Node n = i.next();
            if (n.getName().equals(name)) return index;
        }
        return index;
    }

    /**
     * Removes a child node from this node.
     *
     * @param node child node to remove
     * @return true if node was removed, false if not found
     */
    public boolean removeNode(Node node) {
        node.setParentNode(null);
        return nodeList.remove(node);
    }

    /**
     * Removes the first child node with the specified name.
     *
     * @param name the name of the child node to remove
     * @return true if the node was removed, false if not found
     */
    public boolean removeNode(String name) {
        return nodeList.remove(getNode(name));
    }

    /**
     * Removes all child nodes from this node.
     */
    public void removeAllNodes() {
        nodeList.clear();
    }

    /**
     * Checks if this node has any child nodes.
     *
     * @return true if this node has child nodes, false otherwise
     */
    public boolean hasNodes() {
        if (0 < getNNodes()) return true;
        return false;
    }

    ////////////////////////////////////////////////
    //	Element (Child Node)
    ////////////////////////////////////////////////

    /**
     * Checks if this node has a child node with the specified name.
     *
     * @param name the name of the child node to check for
     * @return true if a child node with the specified name exists, false otherwise
     */
    public boolean hasNode(String name) {
        Node node = getNode(name);
        if (node != null) {
            return true;
        }
        return false;
    }

    /**
     * Ensures a child node with the specified name exists.
     * If a child node with the name already exists, this method does nothing.
     * If not, a new child node with the specified name is created and added.
     *
     * @param name the name of the child node to ensure exists
     */
    public void setNode(String name) {
        if (hasNode(name)) {
            return;
        }
        Node node = new Node(name);
        addNode(node);
    }

    /**
     * Ensures a child node with the specified name exists and sets its value.
     * If a child node with the name already exists, its value is updated.
     * If not, a new child node with the specified name and value is created and added.
     *
     * @param name the name of the child node
     * @param value the value to set for the child node
     */
    public void setNode(String name, String value) {
        Node node = getNode(name);
        if (node == null) {
            node = new Node(name);
            addNode(node);
        }
        node.setValue(value);
    }

    /**
     * Gets the value of the first child node with the specified name.
     *
     * @param name the name of the child node
     * @return the value of the child node, or empty string if not found
     */
    public String getNodeValue(String name) {
        Node node = getNode(name);
        if (node != null) return node.getValue();
        return "";
    }

    ////////////////////////////////////////////////
    //	userData
    ////////////////////////////////////////////////

    private Object userData = null;

    /**
     * Sets user data for this node.
     *
     * @param data user data object to associate with this node
     */
    public void setUserData(Object data) {
        userData = data;
    }

    /**
     * Gets the user data associated with this node.
     *
     * @return user data object, or null if not set
     */
    public Object getUserData() {
        return userData;
    }

    ////////////////////////////////////////////////
    //	toString
    ////////////////////////////////////////////////

    /**
     * Inovoke {@link #getIndentLevelString(int, String)} with <code>"   "</code> as String
     *
     * @see #getIndentLevelString(int, String)
     */
    public String getIndentLevelString(int nIndentLevel) {
        return getIndentLevelString(nIndentLevel, "   ");
    }

    /**
     * @param nIndentLevel the level of indentation to produce
     * @param space the String to use for the intendation
     * @since 1.8.0
     * @return an indentation String
     */
    public String getIndentLevelString(int nIndentLevel, String space) {
        StringBuffer indentString = new StringBuffer(nIndentLevel * space.length());
        for (int n = 0; n < nIndentLevel; n++) {
            indentString.append(space);
        }
        return indentString.toString();
    }

    /**
     * Outputs this node's attributes to the specified print writer.
     *
     * @param ps the print writer to output to
     */
    public void outputAttributes(PrintWriter ps) {
        int nAttributes = getNAttributes();
        for (int n = 0; n < nAttributes; n++) {
            Attribute attr = getAttribute(n);
            ps.print(" " + attr.getName() + "=\"" + XML.escapeXMLChars(attr.getValue()) + "\"");
        }
    }

    /**
     * Outputs this node as XML to the specified print writer with indentation.
     *
     * @param ps the print writer to output to
     * @param indentLevel the indentation level for this node
     * @param hasChildNode whether to treat this node as having child nodes
     */
    public void output(PrintWriter ps, int indentLevel, boolean hasChildNode) {
        String indentString = getIndentLevelString(indentLevel);

        String name = getName();
        String value = getValue();

        if (hasNodes() == false || hasChildNode == false) {
            ps.print(indentString + "<" + name);
            outputAttributes(ps);
            // Thnaks for Tho Beisch (11/09/04)
            if (value == null || value.length() == 0) {
                // Not using the short notation <node /> because it cause compatibility trouble
                ps.println("></" + name + ">");
            } else {
                ps.println(">" + XML.escapeXMLChars(value) + "</" + name + ">");
            }

            return;
        }

        ps.print(indentString + "<" + name);
        outputAttributes(ps);
        ps.println(">");

        int nChildNodes = getNNodes();
        for (int n = 0; n < nChildNodes; n++) {
            Node cnode = getNode(n);
            cnode.output(ps, indentLevel + 1, true);
        }

        ps.println(indentString + "</" + name + ">");
    }

    /**
     * Gets the XML string representation of this node with the specified encoding.
     *
     * @param enc the character encoding to use, or null for default
     * @param hasChildNode whether to treat this node as having child nodes
     * @return the XML string representation
     */
    public String toString(String enc, boolean hasChildNode) {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        PrintWriter pr =
                new PrintWriter(new OutputStreamWriter(byteOut, StandardCharsets.UTF_8), false);
        output(pr, 0, hasChildNode);
        pr.flush();
        if (enc != null && 0 < enc.length()) {
            try {
                return byteOut.toString(enc);
            } catch (UnsupportedEncodingException e) {
                // Fall through to UTF-8
            }
        }
        // Always use UTF-8 charset to avoid encoding issues
        return new String(byteOut.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Gets the XML string representation of this node.
     *
     * @return XML string using UTF-8 encoding
     */
    public String toString() {
        return toString(XML.CHARSET_UTF8, true);
    }

    /**
     * Gets the XML string representation with all XML characters escaped.
     * This is useful for embedding XML within XML.
     *
     * @param hasChildNode whether to treat this node as having child nodes
     * @return the escaped XML string
     */
    public String toXMLString(boolean hasChildNode) {
        String xmlStr = toString();
        xmlStr = xmlStr.replaceAll("<", "&lt;");
        xmlStr = xmlStr.replaceAll(">", "&gt;");
        // Thanks for Theo Beisch (11/09/04)
        xmlStr = xmlStr.replaceAll("&", "&amp;");
        xmlStr = xmlStr.replaceAll("\"", "&quot;");
        // Thanks for Brian Owens (12/02/04)
        xmlStr = xmlStr.replaceAll("'", "&apos;");
        return xmlStr;
    }

    /**
     * Gets the XML string representation with all XML characters escaped.
     * This is useful for embedding XML within XML.
     *
     * @return the escaped XML string
     */
    public String toXMLString() {
        return toXMLString(true);
    }

    /**
     * Prints this node as XML to standard output.
     *
     * @param hasChildNode whether to treat this node as having child nodes
     */
    public void print(boolean hasChildNode) {
        PrintWriter pr =
                new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), false);
        output(pr, 0, hasChildNode);
        pr.flush();
    }

    /**
     * Prints this node as XML to standard output.
     */
    public void print() {
        print(true);
    }
}
