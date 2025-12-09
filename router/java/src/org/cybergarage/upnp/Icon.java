/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp;

import org.cybergarage.xml.Node;

/**
 * Represents a UPnP device icon with its associated metadata and image data.
 *
 * <p>This class encapsulates the properties of a UPnP device icon as defined in the UPnP Device
 * Architecture specification. Each icon has properties such as MIME type, width, height, color
 * depth, and URL. The class also supports storing the actual icon image data as a byte array.
 *
 * <p>Icons are used by UPnP devices to provide visual representations that can be displayed in
 * control point applications or user interfaces.
 *
 * @since 1.0
 * @author Satoshi Konno
 */
public class Icon {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    /** XML element name for icon nodes. */
    public static final String ELEM_NAME = "icon";

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    private Node iconNode;

    /**
     * Gets the underlying XML node for this icon.
     *
     * @return the XML node containing icon data
     */
    public Node getIconNode() {
        return iconNode;
    }

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Creates an Icon from an existing XML node.
     *
     * @param node the XML node containing icon data
     */
    public Icon(Node node) {
        iconNode = node;
    }

    /** Creates a new Icon with a default XML node structure. */
    public Icon() {
        this(new Node(ELEM_NAME));
    }

    ////////////////////////////////////////////////
    //	isIconNode
    ////////////////////////////////////////////////

    /**
     * Checks if the given XML node represents an icon.
     *
     * @param node the XML node to check
     * @return true if the node is an icon node, false otherwise
     */
    public static boolean isIconNode(Node node) {
        return Icon.ELEM_NAME.equals(node.getName());
    }

    ////////////////////////////////////////////////
    //	mimeType
    ////////////////////////////////////////////////

    private static final String MIME_TYPE = "mimeType";

    /**
     * Sets the MIME type of the icon.
     *
     * @param value the MIME type (e.g., "image/png", "image/jpeg")
     */
    public void setMimeType(String value) {
        getIconNode().setNode(MIME_TYPE, value);
    }

    /**
     * Gets the MIME type of the icon.
     *
     * @return the MIME type of the icon
     */
    public String getMimeType() {
        return getIconNode().getNodeValue(MIME_TYPE);
    }

    /**
     * Checks if the icon has a valid MIME type set.
     *
     * @return true if a MIME type is set and not empty, false otherwise
     */
    public boolean hasMimeType() {
        String iconMimeType = getMimeType();
        if (iconMimeType == null) return false;
        return (0 < iconMimeType.length()) ? true : false;
    }

    ////////////////////////////////////////////////
    //	width
    ////////////////////////////////////////////////

    private static final String WIDTH = "width";

    /**
     * Sets the width of the icon as a string.
     *
     * @param value the width as a string
     */
    public void setWidth(String value) {
        getIconNode().setNode(WIDTH, value);
    }

    /**
     * Sets the width of the icon as an integer.
     *
     * @param value the width in pixels
     */
    public void setWidth(int value) {
        try {
            setWidth(Integer.toString(value));
        } catch (Exception e) {
            // Ignore exception in setWidth
        }
    }

    /**
     * Gets the width of the icon.
     *
     * @return the width in pixels, or 0 if not set or invalid
     */
    public int getWidth() {
        try {
            return Integer.parseInt(getIconNode().getNodeValue(WIDTH));
        } catch (Exception e) {
        }
        ;
        return 0;
    }

    ////////////////////////////////////////////////
    //	height
    ////////////////////////////////////////////////

    private static final String HEIGHT = "height";

    /**
     * Sets the height of the icon as a string.
     *
     * @param value the height as a string
     */
    public void setHeight(String value) {
        getIconNode().setNode(HEIGHT, value);
    }

    /**
     * Sets the height of the icon as an integer.
     *
     * @param value the height in pixels
     */
    public void setHeight(int value) {
        try {
            setHeight(Integer.toString(value));
        } catch (Exception e) {
            // Ignore exception in setHeight
        }
    }

    /**
     * Gets the height of the icon.
     *
     * @return the height in pixels, or 0 if not set or invalid
     */
    public int getHeight() {
        try {
            return Integer.parseInt(getIconNode().getNodeValue(HEIGHT));
        } catch (Exception e) {
        }
        ;
        return 0;
    }

    ////////////////////////////////////////////////
    //	depth
    ////////////////////////////////////////////////

    private static final String DEPTH = "depth";

    /**
     * Sets the color depth of the icon as a string.
     *
     * @param value the color depth as a string
     */
    public void setDepth(String value) {
        getIconNode().setNode(DEPTH, value);
    }

    /**
     * Sets the color depth of the icon as an integer.
     *
     * @param value the color depth in bits per pixel
     */
    public void setDepth(int value) {
        try {
            setDepth(Integer.toString(value));
        } catch (Exception e) {
            // Ignore exception in setDepth
        }
    }

    /**
     * Gets the color depth of the icon.
     *
     * @return the color depth in bits per pixel, or 0 if not set or invalid
     */
    public int getDepth() {
        try {
            return Integer.parseInt(getIconNode().getNodeValue(DEPTH));
        } catch (Exception e) {
        }
        ;
        return 0;
    }

    ////////////////////////////////////////////////
    //	URL
    ////////////////////////////////////////////////

    private static final String URL = "url";

    /**
     * Sets the URL where the icon image can be retrieved.
     *
     * @param value the URL of the icon image
     */
    public void setURL(String value) {
        getIconNode().setNode(URL, value);
    }

    /**
     * Gets the URL where the icon image can be retrieved.
     *
     * @return the URL of the icon image
     */
    public String getURL() {
        return getIconNode().getNodeValue(URL);
    }

    /**
     * Checks if the icon has a valid URL set.
     *
     * @return true if a URL is set and not empty, false otherwise
     */
    public boolean hasURL() {
        String iconURL = getURL();
        if (iconURL == null) return false;
        return (0 < iconURL.length()) ? true : false;
    }

    /**
     * Checks if the given URL matches this icon's URL.
     *
     * @param url the URL to compare
     * @return true if the URLs match, false otherwise
     */
    public boolean isURL(String url) {
        if (url == null) return false;
        String iconURL = getURL();
        if (iconURL == null) return false;
        return iconURL.equals(url);
    }

    ////////////////////////////////////////////////
    //	userData
    ////////////////////////////////////////////////

    private Object userData = null;

    /**
     * Sets user-defined data associated with this icon.
     *
     * @param data the user data object to associate
     */
    public void setUserData(Object data) {
        userData = data;
    }

    /**
     * Gets the user-defined data associated with this icon.
     *
     * @return the user data object, or null if not set
     */
    public Object getUserData() {
        return userData;
    }

    ////////////////////////////////////////////////
    //	Bytes
    ////////////////////////////////////////////////

    private byte bytes[] = null;

    /**
     * Sets the raw image data for this icon.
     *
     * @param data the byte array containing the icon image data
     */
    public void setBytes(byte data[]) {
        bytes = data;
    }

    /**
     * Checks if this icon has raw image data set.
     *
     * @return true if image data is available, false otherwise
     */
    public boolean hasBytes() {
        return (bytes != null) ? true : false;
    }

    /**
     * Gets the raw image data for this icon.
     *
     * @return the byte array containing the icon image data, or null if not set
     */
    public byte[] getBytes() {
        return bytes;
    }
}
