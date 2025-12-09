/*
 * CyberHTTP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.http;

import org.cybergarage.util.Debug;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Represents an HTTP header field with a name and value. This class provides methods to parse,
 * manipulate, and extract HTTP headers from various data sources including strings, byte arrays,
 * and readers.
 */
public class HTTPHeader {
    /**
     * Maximum length for HTTP header lines to prevent excessive memory usage. Headers longer than
     * this limit will be truncated during parsing.
     */
    private static int MAX_LENGTH = 1024;

    private String name;
    private String value;

    /**
     * Creates a new HTTPHeader with the specified name and value.
     *
     * @param name the header name
     * @param value the header value
     */
    public HTTPHeader(String name, String value) {
        setName(name);
        setValue(value);
    }

    /**
     * Creates a new HTTPHeader by parsing a header line string. The line should be in the format
     * "Name: Value".
     *
     * @param lineStr the header line to parse
     */
    public HTTPHeader(String lineStr) {
        setName("");
        setValue("");
        if (lineStr == null) return;
        int colonIdx = lineStr.indexOf(':');
        if (colonIdx < 0) return;
        String name =
                new String(
                        lineStr.getBytes(StandardCharsets.UTF_8),
                        0,
                        colonIdx,
                        StandardCharsets.UTF_8);
        String value =
                new String(
                        lineStr.getBytes(StandardCharsets.UTF_8),
                        colonIdx + 1,
                        lineStr.length() - colonIdx - 1,
                        StandardCharsets.UTF_8);
        setName(name.trim());
        setValue(value.trim());
    }

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    /**
     * Sets the header name.
     *
     * @param name the header name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Sets the header value.
     *
     * @param value the header value to set
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * Gets the header name.
     *
     * @return the header name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the header value.
     *
     * @return the header value
     */
    public String getValue() {
        return value;
    }

    /**
     * Checks if this header has a valid name.
     *
     * @return true if the header has a non-null, non-empty name
     */
    public boolean hasName() {
        if (name == null || name.length() <= 0) return false;
        return true;
    }

    ////////////////////////////////////////////////
    //	static methods
    ////////////////////////////////////////////////

    /**
     * Reads from a LineNumberReader to find the value of a header with the specified name. The
     * search is case-insensitive.
     *
     * @param reader the LineNumberReader to read from
     * @param name the name of the header to find
     * @return the header value, or empty string if not found
     */
    public static final String getValue(LineNumberReader reader, String name) {
        String bigName = name.toUpperCase(Locale.US);
        try {
            String lineStr = reader.readLine();
            while (lineStr != null && 0 < lineStr.length()) {
                HTTPHeader header = new HTTPHeader(lineStr);
                if (header.hasName() == false) {
                    lineStr = reader.readLine();
                    continue;
                }
                String bigLineHeaderName = header.getName().toUpperCase(Locale.US);
                // Thanks for Jan Newmarch <jan.newmarch@infotech.monash.edu.au> (05/26/04)
                if (bigLineHeaderName.equals(bigName) == false) {
                    lineStr = reader.readLine();
                    continue;
                }
                return header.getValue();
            }
        } catch (IOException e) {
            Debug.warning(e);
            return "";
        }
        return "";
    }

    /**
     * Extracts the value of a header with the specified name from a string. The search is
     * case-insensitive.
     *
     * @param data the string containing HTTP headers
     * @param name the name of the header to find
     * @return the header value, or empty string if not found
     */
    public static final String getValue(String data, String name) {
        // I2P #1480 avoid IAE
        if (data.length() <= 0) return "";
        /* Thanks for Stephan Mehlhase (2010-10-26) */
        StringReader strReader = new StringReader(data);
        LineNumberReader lineReader =
                new LineNumberReader(strReader, Math.min(data.length(), MAX_LENGTH));
        return getValue(lineReader, name);
    }

    /**
     * Extracts the value of a header with the specified name from a byte array. The search is
     * case-insensitive.
     *
     * @param data the byte array containing HTTP headers
     * @param name the name of the header to find
     * @return the header value, or empty string if not found
     */
    public static final String getValue(byte[] data, String name) {
        return getValue(new String(data, StandardCharsets.UTF_8), name);
    }

    /**
     * Extracts the integer value of a header with the specified name from a string.
     *
     * @param data the string containing HTTP headers
     * @param name the name of the header to find
     * @return the header value as an integer, or 0 if not found or invalid
     */
    public static final int getIntegerValue(String data, String name) {
        try {
            return Integer.parseInt(getValue(data, name));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Extracts the integer value of a header with the specified name from a byte array.
     *
     * @param data the byte array containing HTTP headers
     * @param name the name of the header to find
     * @return the header value as an integer, or 0 if not found or invalid
     */
    public static final int getIntegerValue(byte[] data, String name) {
        try {
            return Integer.parseInt(getValue(data, name));
        } catch (Exception e) {
            return 0;
        }
    }
}
