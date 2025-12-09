/*
 * CyberXML for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.xml;

/**
 * Exception thrown when XML parsing fails.
 *
 * <p>This exception is used throughout the XML parsing framework to indicate errors that occur
 * during XML document parsing, including I/O errors, malformed XML, and security violations.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class ParserException extends Exception {
    /**
     * Creates a ParserException wrapping another exception.
     *
     * @param e the underlying exception that caused this parser exception
     */
    public ParserException(Exception e) {
        super(e);
    }

    /**
     * Creates a ParserException with the specified detail message.
     *
     * @param s the detail message explaining the parsing error
     */
    public ParserException(String s) {
        super(s);
    }
}
