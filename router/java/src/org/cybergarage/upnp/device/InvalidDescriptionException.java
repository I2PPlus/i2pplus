/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

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
 * @author Satoshi Konno
 * @since 1.0
 */
public class InvalidDescriptionException extends Exception {
    public InvalidDescriptionException() {
        super();
    }

    public InvalidDescriptionException(String s) {
        super(s);
    }

    public InvalidDescriptionException(String s, File file) {
        super(s + " (" + file.toString() + ")");
    }

    public InvalidDescriptionException(Exception e) {
        super(e.getMessage());
    }
}
