package org.rrd4j.core;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Class used as a base class for various XML template related classes. Class provides methods for
 * XML source parsing and XML tree traversing. XML source may have unlimited number of placeholders
 * (variables) in the format <code>${variable_name}</code>. Methods are provided to specify variable
 * values at runtime. Note that this class has limited functionality: XML source gets parsed, and
 * variable values are collected. You have to extend this class to do something more useful.
 */
public abstract class XmlTemplate {
    private static final String PATTERN_STRING = "\\$\\{(\\w+)\\}";
    private static final Pattern PATTERN = Pattern.compile(PATTERN_STRING);
    /** the parsed root element of the template */
    protected Element root;
    private final HashMap<String, Object> valueMap = new HashMap<>();
    private final HashSet<Node> validatedNodes = new HashSet<>();

    /**
     * Parse an XML template from a source.
     *
     * @param xmlSource the source to read the template from
     * @throws java.io.IOException if the source cannot be read or is not well-formed XML
     */
    protected XmlTemplate(InputSource xmlSource) throws IOException {
        root = Util.Xml.getRootElement(xmlSource);
    }

    /**
     * Parse an XML template from a string.
     *
     * @param xmlString the template text to parse
     * @throws java.io.IOException if the text is not well-formed XML
     */
    protected XmlTemplate(String xmlString) throws IOException {
        root = Util.Xml.getRootElement(xmlString);
    }

    /**
     * Parse an XML template from a file.
     *
     * @param xmlFile the file to read the template from
     * @throws java.io.IOException if the file cannot be read or does not hold well-formed XML
     */
    protected XmlTemplate(File xmlFile) throws IOException {
        root = Util.Xml.getRootElement(xmlFile);
    }

    /** Removes all placeholder-value mappings. */
    public void clearValues() {
        valueMap.clear();
    }

    /**
     * Sets value for a single XML template variable. Variable name should be specified without
     * leading '${' and ending '}' placeholder markers. For example, for a placeholder <code>
     * ${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
     *
     * @param name the placeholder name, without the leading '${' or trailing '}'
     * @param value the text substituted for the placeholder
     */
    public void setVariable(String name, String value) {
        valueMap.put(name, value);
    }

    /**
     * Sets value for a single XML template variable. Variable name should be specified without
     * leading '${' and ending '}' placeholder markers. For example, for a placeholder <code>
     * ${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
     *
     * @param name the placeholder name, without the leading '${' or trailing '}'
     * @param value the text substituted for the placeholder
     */
    public void setVariable(String name, int value) {
        valueMap.put(name, value);
    }

    /**
     * Sets value for a single XML template variable. Variable name should be specified without
     * leading '${' and ending '}' placeholder markers. For example, for a placeholder <code>
     * ${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
     *
     * @param name the placeholder name, without the leading '${' or trailing '}'
     * @param value the text substituted for the placeholder
     */
    public void setVariable(String name, long value) {
        valueMap.put(name, value);
    }

    /**
     * Sets value for a single XML template variable. Variable name should be specified without
     * leading '${' and ending '}' placeholder markers. For example, for a placeholder <code>
     * ${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
     *
     * @param name the placeholder name, without the leading '${' or trailing '}'
     * @param value the text substituted for the placeholder
     */
    public void setVariable(String name, double value) {
        valueMap.put(name, value);
    }

    /**
     * Sets value for a single XML template variable. Variable name should be specified without
     * leading '${' and ending '}' placeholder markers. For example, for a placeholder <code>
     * ${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
     *
     * @param name the placeholder name, without the leading '${' or trailing '}'
     * @param value the text substituted for the placeholder
     */
    public void setVariable(String name, Color value) {
        String r = byteToHex(value.getRed());
        String g = byteToHex(value.getGreen());
        String b = byteToHex(value.getBlue());
        String a = byteToHex(value.getAlpha());
        valueMap.put(name, "#" + r + g + b + a);
    }
    /**
     * Format one channel of a colour as two lowercase hex digits.
     *
     * @return the two-digit hex form of a colour channel, 0 to 255
     */
    private String byteToHex(int i) {
        StringBuilder s = new StringBuilder(Integer.toHexString(i));
        while (s.length() < 2) {
            s.insert(0, "0");
        }
        return s.toString();
    }

    /**
     * Sets value for a single XML template variable. Variable name should be specified without
     * leading '${' and ending '}' placeholder markers. For example, for a placeholder <code>
     * ${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
     *
     * @param name the placeholder name, without the leading '${' or trailing '}'
     * @param value the text substituted for the placeholder
     */
    public void setVariable(String name, Date value) {
        setVariable(name, Util.getTimestamp(value));
    }

    /**
     * Sets value for a single XML template variable. Variable name should be specified without
     * leading '${' and ending '}' placeholder markers. For example, for a placeholder <code>
     * ${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
     *
     * @param name the placeholder name, without the leading '${' or trailing '}'
     * @param value the text substituted for the placeholder
     */
    public void setVariable(String name, Calendar value) {
        setVariable(name, Util.getTimestamp(value));
    }

    /**
     * Sets value for a single XML template variable. Variable name should be specified without
     * leading '${' and ending '}' placeholder markers. For example, for a placeholder <code>
     * ${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
     *
     * @param name the placeholder name, without the leading '${' or trailing '}'
     * @param value the text substituted for the placeholder
     */
    public void setVariable(String name, boolean value) {
        valueMap.put(name, Boolean.toString(value));
    }

    /**
     * Searches the XML template to see if there are variables in there that will need to be set.
     *
     * @return True if variables were detected, false if not.
     */
    public boolean hasVariables() {
        return PATTERN.matcher(root.toString()).find();
    }

    /**
     * Returns the list of variables that should be set in this template.
     *
     * @return Array of variable names as an array of strings.
     */
    public String[] getVariables() {
        ArrayList<String> list = new ArrayList<>();
        Matcher m = PATTERN.matcher(root.toString());

        while (m.find()) {
            String var = m.group(1);
            if (!list.contains(var)) {
                list.add(var);
            }
        }

        return list.toArray(new String[0]);
    }

    /**
     * Find the child nodes of the given name.
     *
     * @param parentNode the node whose children to search
     * @param childName the element name to match
     * @return an array of the matching child nodes, empty if there are none
     */
    protected static Node[] getChildNodes(Node parentNode, String childName) {
        return Util.Xml.getChildNodes(parentNode, childName);
    }

    /**
     * Find every child node of the given node.
     *
     * @param parentNode the node whose children to return
     * @return an array of all child nodes, empty if there are none
     */
    protected static Node[] getChildNodes(Node parentNode) {
        return Util.Xml.getChildNodes(parentNode, null);
    }

    /**
     * Find the first child node of the given name.
     *
     * @param parentNode the node whose children to search
     * @param childName the element name to match
     * @return the first matching child node, or null if there is none
     */
    protected static Node getFirstChildNode(Node parentNode, String childName) {
        return Util.Xml.getFirstChildNode(parentNode, childName);
    }

    /**
     * Test whether a node has a child of the given name.
     *
     * @param parentNode the node whose children to search
     * @param childName the element name to match
     * @return true if a matching child exists, false otherwise
     */
    protected boolean hasChildNode(Node parentNode, String childName) {
        return Util.Xml.hasChildNode(parentNode, childName);
    }

    /**
     * Read a named child element's text with its placeholders resolved.
     *
     * @param parentNode the node whose child to read
     * @param childName the element name to match
     * @return the resolved text, or null if no such child exists
     */
    protected String getChildValue(Node parentNode, String childName) {
        return getChildValue(parentNode, childName, true);
    }

    /**
     * Read a named child element's text with its placeholders resolved.
     *
     * @param parentNode the node whose child to read
     * @param childName the element name to match
     * @param trim when true, strip leading and trailing whitespace from the text
     * @return the resolved text, or null if no such child exists
     */
    protected String getChildValue(Node parentNode, String childName, boolean trim) {
        String value = Util.Xml.getChildValue(parentNode, childName, trim);
        return resolveMappings(value);
    }

    /**
     * Read a node's own text with its placeholders resolved.
     *
     * @param parentNode the node whose text to read
     * @return the resolved text, or null if the node has none
     */
    protected String getValue(Node parentNode) {
        return getValue(parentNode, true);
    }

    /**
     * Read a node's own text with its placeholders resolved.
     *
     * @param parentNode the node whose text to read
     * @param trim when true, strip leading and trailing whitespace from the text
     * @return the resolved text, or null if the node has none
     */
    protected String getValue(Node parentNode, boolean trim) {
        String value = Util.Xml.getValue(parentNode, trim);
        return resolveMappings(value);
    }

    /**
     * Substitute every ${name} placeholder in a value from the recorded mappings.
     *
     * @param templateValue the raw text to resolve, or null
     * @return the resolved text, or null if templateValue was null
     */
    private String resolveMappings(String templateValue) {
        if (templateValue == null) {
            return null;
        }
        Matcher matcher = PATTERN.matcher(templateValue);
        StringBuilder result = new StringBuilder();
        int lastMatchEnd = 0;
        while (matcher.find()) {
            String var = matcher.group(1);
            if (valueMap.containsKey(var)) {
                // mapping found
                result.append(templateValue, lastMatchEnd, matcher.start());
                result.append(valueMap.get(var).toString());
                lastMatchEnd = matcher.end();
            } else {
                // no mapping found - this is illegal
                // throw runtime exception
                throw new IllegalArgumentException(
                        "No mapping found for template variable ${" + var + "}");
            }
        }
        result.append(templateValue.substring(lastMatchEnd));
        return result.toString();
    }

    /**
     * Read a named child element's text as an int.
     *
     * @param parentNode the node whose child to read
     * @param childName the element name to match
     * @return the parsed integer
     */
    protected int getChildValueAsInt(Node parentNode, String childName) {
        String valueStr = getChildValue(parentNode, childName);
        return Integer.parseInt(valueStr);
    }

    /**
     * Read a node's own text as an int.
     *
     * @param parentNode the node whose text to read
     * @return the parsed integer
     */
    protected int getValueAsInt(Node parentNode) {
        String valueStr = getValue(parentNode);
        return Integer.parseInt(valueStr);
    }

    /**
     * Read a named child element's text as a long.
     *
     * @param parentNode the node whose child to read
     * @param childName the element name to match
     * @return the parsed long
     */
    protected long getChildValueAsLong(Node parentNode, String childName) {
        String valueStr = getChildValue(parentNode, childName);
        return Long.parseLong(valueStr);
    }

    /**
     * Read a node's own text as a long.
     *
     * @param parentNode the node whose text to read
     * @return the parsed long
     */
    protected long getValueAsLong(Node parentNode) {
        String valueStr = getValue(parentNode);
        return Long.parseLong(valueStr);
    }

    /**
     * Read a named child element's text as a double.
     *
     * @param parentNode the node whose child to read
     * @param childName the element name to match
     * @return the parsed double
     */
    protected double getChildValueAsDouble(Node parentNode, String childName) {
        String valueStr = getChildValue(parentNode, childName);
        return Util.parseDouble(valueStr);
    }

    /**
     * Read a node's own text as a double.
     *
     * @param parentNode the node whose text to read
     * @return the parsed double
     */
    protected double getValueAsDouble(Node parentNode) {
        String valueStr = getValue(parentNode);
        return Util.parseDouble(valueStr);
    }

    /**
     * Read a named child element's text as a boolean.
     *
     * @param parentNode the node whose child to read
     * @param childName the element name to match
     * @return the parsed boolean
     */
    protected boolean getChildValueAsBoolean(Node parentNode, String childName) {
        String valueStr = getChildValue(parentNode, childName);
        return Util.parseBoolean(valueStr);
    }

    /**
     * Read a node's own text as a boolean.
     *
     * @param parentNode the node whose text to read
     * @return the parsed boolean
     */
    protected boolean getValueAsBoolean(Node parentNode) {
        String valueStr = getValue(parentNode);
        return Util.parseBoolean(valueStr);
    }

    /**
     * Read a node's own text as a colour.
     *
     * @param parentNode the node whose text to read
     * @return the parsed colour, or null if the text does not name one
     */
    protected Paint getValueAsColor(Node parentNode) {
        String rgbStr = getValue(parentNode);
        return Util.parseColor(rgbStr);
    }

    /**
     * Test whether a node carries no renderable content.
     *
     * @param node the node to test
     * @return true if the node is a comment or is whitespace-only text, false otherwise
     */
    protected boolean isEmptyNode(Node node) {
        // comment node or empty text node
        return node.getNodeName().equals("#comment")
                || (node.getNodeName().equals("#text") && node.getNodeValue().trim().length() == 0);
    }

    /**
     * Reject any child element not named in the allowed list, once per node.
     *
     * @param parentNode the node whose children to check
     * @param allowedChildNames the permitted element names; a name suffixed with "*" permits repeats, and the array is consumed as names are matched
     */
    protected void validateTagsOnlyOnce(Node parentNode, String[] allowedChildNames) {
        // validate node only once
        if (validatedNodes.contains(parentNode)) {
            return;
        }
        Node[] childs = getChildNodes(parentNode);
        main:
        for (Node child : childs) {
            String childName = child.getNodeName();
            for (int j = 0; j < allowedChildNames.length; j++) {
                if (allowedChildNames[j].equals(childName)) {
                    // only one such tag is allowed
                    allowedChildNames[j] = "<--removed-->";
                    continue main;
                } else if (allowedChildNames[j].equals(childName + "*")) {
                    // several tags allowed
                    continue main;
                }
            }
            if (!isEmptyNode(child)) {
                throw new IllegalArgumentException(
                        "Unexpected tag encountered: <" + childName + ">");
            }
        }
        // everything is OK
        validatedNodes.add(parentNode);
    }
}
