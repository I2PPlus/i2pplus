/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.device;

import java.io.*;

/**
 * Exception thrown when UPnP device description is invalid or cannot be parsed.
 *
 * <p>This exception is thrown during device description processing when the XML description is
 * malformed, missing required elements, or contains invalid data that prevents proper device
 * initialization.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Multiple constructor options for different error scenarios
 *   <li>File context inclusion for description errors
 *   <li>Exception chaining support
 *   <li>Detailed error message handling
 * </ul>
 *
 * <p>This exception is used by UPnP device parsing components to indicate failures in loading or
 * interpreting device description documents, helping developers identify and resolve description
 * format issues.
 *
 * @since 1.0
 */
public class InvalidDescriptionException extends Exception {
    /**
     * Creates a description exception with no detail message.
     */
    public InvalidDescriptionException() {
        super();
    }

    /**
     * Creates a description exception carrying a detail message.
     *
     * @param s the detail message describing why the description is invalid
     */
    public InvalidDescriptionException(String s) {
        super(s);
    }

    /**
     * Creates a description exception naming the offending file.
     *
     * @param s the detail message describing why the description is invalid
     * @param file the description file whose contents failed to parse, appended to the message
     */
    public InvalidDescriptionException(String s, File file) {
        super(s + " (" + file.toString() + ")");
    }

    /**
     * Creates a description exception reusing a parse failure's message.
     *
     * @param e the parse failure whose message is reused as the detail message
     */
    public InvalidDescriptionException(Exception e) {
        super(e.getMessage());
    }
}
