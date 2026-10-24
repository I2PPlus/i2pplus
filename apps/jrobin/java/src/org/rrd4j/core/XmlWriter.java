package org.rrd4j.core;

import java.awt.*;
import java.io.File;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Locale;

/** Extremely simple utility class used to create XML documents. */
public class XmlWriter implements AutoCloseable {
    /** Indentation string for XML output */
    static final String INDENT_STR = "   ";
    /** Attribute name carrying the RRD tool style. */
    private static final String STYLE = "style";
    /** Timestamp format written into the XML, in UTC. */
    private static final DateTimeFormatter ISOLIKE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSZ")
                    .withLocale(Locale.ENGLISH)
                    .withZone(ZoneId.of("UTC"));
    /** How NaN is written when no other form is given. */
    private static final String DEFAULT_NAN_STRING = Double.toString(Double.NaN);

    /**
     * Functional interface for formatting double values in XML output.
     *
     * <p>This interface allows customization of how double values are formatted when written to
     * XML, including handling of NaN values.
     */
    @FunctionalInterface
    public interface DoubleFormater {
        /**
         * Formats a double value for XML output.
         *
         * @param value the double value to format
         * @param nanString the string to use for NaN values
         * @return the formatted string representation
         */
        String format(double value, String nanString);
    }
    /** Where the XML is written. */
    private final PrintWriter writer;
    /** Indentation of the element currently being written. */
    private final StringBuilder indent = new StringBuilder();
    /** Elements not yet closed, innermost last. */
    private final Deque<String> openTags = new LinkedList<>();
    /** Time formatter */
    private final DateTimeFormatter timeFormatter;
    /** Double formatter */
    private final DoubleFormater doubleFormatter;

    /**
     * Creates a writer for one XML document.
     *
     * @param writer where the XML is written
     * @param timeFormatter how timestamps are written
     * @param doubleFormatter how numbers are written
     */
    private XmlWriter(
            PrintWriter writer, DateTimeFormatter timeFormatter, DoubleFormater doubleFormatter) {
        this.writer = writer;
        this.timeFormatter = timeFormatter;
        this.doubleFormatter = doubleFormatter;
    }

    /**
     * Creates XmlWriter with the specified {@link OutputStream} to send XML code to.
     *
     * @param stream {@link OutputStream} which receives XML code
     */
    public XmlWriter(OutputStream stream) {
        this(
                new PrintWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), true),
                ISOLIKE,
                (d, n) -> Util.formatDouble(d, n, true));
    }

    /**
     * Creates XmlWriter with the specified output stream to send XML code to.
     *
     * @param stream Output stream which receives XML code
     * @param autoFlush is the stream to be flushed automatically
     */
    public XmlWriter(OutputStream stream, boolean autoFlush) {
        this(
                new PrintWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), autoFlush),
                ISOLIKE,
                (d, n) -> Util.formatDouble(d, n, true));
    }

    /**
     * Creates XmlWriter with the specified {@link PrintWriter} to send XML code to.
     *
     * @param stream {@link PrintWriter} which receives XML code
     */
    public XmlWriter(PrintWriter stream) {
        writer = stream;
        timeFormatter = ISOLIKE;
        this.doubleFormatter = (d, n) -> Util.formatDouble(d, n, true);
    }

    /**
     * Return a new {@link XmlWriter} that will format time stamp as ISO 8601 with this explicit
     * time zone {@link ZoneId}
     *
     * @param zid the time zone the returned writer renders time stamps in
     * @return a new writer sharing this one's stream, unused otherwise
     */
    public XmlWriter withTimeZone(ZoneId zid) {
        if (indent.length() != 0 || !openTags.isEmpty()) {
            throw new IllegalStateException("Can't be used on a already used XmlWriter");
        }
        DateTimeFormatter dtf = this.timeFormatter.withZone(zid);
        return new XmlWriter(writer, dtf, doubleFormatter);
    }

    /**
     * Return a new {@link XmlWriter} that will format time stamp using this {@link ZoneId}
     *
     * @param doubleFormatter the formatter the returned writer renders DS values with
     * @return a new writer sharing this one's stream, unused otherwise
     */
    public XmlWriter withDoubleFormatter(DoubleFormater doubleFormatter) {
        if (indent.length() != 0 || !openTags.isEmpty()) {
            throw new IllegalStateException("Can't be used on a already used XmlWriter");
        }
        return new XmlWriter(writer, timeFormatter, doubleFormatter);
    }

    /**
     * Opens XML tag
     *
     * @param tag XML tag name
     */
    public void startTag(String tag) {
        writer.println(indent + "<" + tag + ">");
        openTags.push(tag);
        indent.append(INDENT_STR);
    }

    /** Closes the corresponding XML tag */
    public void closeTag() {
        String tag = openTags.pop();
        indent.setLength(indent.length() - INDENT_STR.length());
        writer.println(indent + "</" + tag + ">");
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;
     *              </code>
     */
    public void writeTag(String tag, Object value) {
        if (value != null) {
            writer.println(indent + "<" + tag + ">" + escape(value.toString()) + "</" + tag + ">");
        } else {
            writer.println(indent + "<" + tag + "></" + tag + ">");
        }
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;
     *              </code>
     */
    public void writeTag(String tag, int value) {
        writeTag(tag, Integer.toString(value));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;
     *              </code>
     */
    public void writeTag(String tag, long value) {
        writeTag(tag, Long.toString(value));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;
     *              </code>
     * @param nanString a {@link java.lang.String} object.
     */
    public void writeTag(String tag, double value, String nanString) {
        writeTag(tag, doubleFormatter.format(value, nanString));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;
     *              </code>
     */
    public void writeTag(String tag, double value) {
        writeTag(tag, doubleFormatter.format(value, DEFAULT_NAN_STRING));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;
     *              </code>
     */
    public void writeTag(String tag, boolean value) {
        writeTag(tag, Boolean.toString(value));
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;
     *              </code>
     */
    public void writeTag(String tag, Color value) {
        int rgb = value.getRGB() & 0xFFFFFF;
        writeTag(tag, "#" + Integer.toHexString(rgb).toUpperCase());
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;
     *              </code>
     */
    public void writeTag(String tag, Font value) {
        startTag(tag);
        writeTag("name", value.getName());
        int style = value.getStyle();
        if ((style & Font.BOLD) != 0 && (style & Font.ITALIC) != 0) {
            writeTag(STYLE, "BOLDITALIC");
        } else if ((style & Font.BOLD) != 0) {
            writeTag(STYLE, "BOLD");
        } else if ((style & Font.ITALIC) != 0) {
            writeTag(STYLE, "ITALIC");
        } else {
            writeTag(STYLE, "PLAIN");
        }
        writeTag("size", value.getSize());
        closeTag();
    }

    /**
     * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
     *
     * @param tag XML tag name
     * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;
     *              </code>
     */
    public void writeTag(String tag, File value) {
        writeTag(tag, value.getPath());
    }

    /** Flushes the output stream */
    public void flush() {
        writer.flush();
    }

    /**
     * Writes XML comment to output stream
     *
     * @param comment comment string
     */
    public void writeComment(Object comment) {
        writer.println(indent + "<!-- " + escape(comment.toString()) + " -->");
    }

    /**
     * Writes a timestamp using the configured {@link DateTimeFormatter} as an XML comment to output
     * stream.
     *
     * @param timestamp the timestamp to write
     */
    public void writeComment(long timestamp) {
        writer.println(indent + "<!-- " + escape(formatTimestamp(timestamp)) + " -->");
    }

    /**
     * Formats a timestamp using the configured {@link DateTimeFormatter}.
     *
     * @param timestamp the timestamp to format
     * @return the formatted timestamp
     */
    public String formatTimestamp(long timestamp) {
        return timeFormatter.format(Instant.ofEpochSecond(timestamp));
    }

    /**
     * Escapes XML special characters in a string.
     *
     * @param s the string to escape
     * @return the escaped string
     */
    private static String escape(String s) {
        return s.replace("<", "&lt;").replace(">", "&gt;");
    }
    /**
     * Flushes the underlying writer, then closes it.
     */

    @Override
    public void close() {
        writer.flush();
        writer.close();
    }
}
