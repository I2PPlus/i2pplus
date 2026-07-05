package i2p.susi.util;

import java.io.Writer;

/**
 * Writer implementation backed by StringBuilder.
 *
 * @since 0.9.35
 */
public class StringBuilderWriter extends Writer {

    private final StringBuilder buf;

    /**
     * Create a StringBuilderWriter with default capacity.
     */
    public StringBuilderWriter() { this(128); }

    /**
     * Create a StringBuilderWriter with specified capacity.
     *
     * @param capacity the initial capacity
     */
    public StringBuilderWriter(int capacity) {
        super();
        buf = new StringBuilder(capacity);
    }

    /**
     * Append a character.
     *
     * @param c the character to append
     * @return this writer
     */
    @Override
    public Writer append(char c) {
        buf.append(c);
        return this;
    }

    /**
     * Append a character sequence.
     *
     * @param str the character sequence to append
     * @return this writer
     */
    @Override
    public Writer append(CharSequence str) {
        buf.append(str);
        return this;
    }

    /**
     * Append a portion of a character sequence.
     *
     * @param str the character sequence
     * @param off the start offset
     * @param len the number of characters
     * @return this writer
     */
    @Override
    public Writer append(CharSequence str, int off, int len) {
        buf.append(str, off, len);
        return this;
    }

    /**
     * Write a character array.
     *
     * @param cbuf the characters to write
     */
    @Override
    public void write(char[] cbuf) {
        buf.append(cbuf);
    }

    /**
     * Write a portion of a character array.
     *
     * @param cbuf the characters to write
     * @param off the start offset
     * @param len the number of characters
     */
    public void write(char[] cbuf, int off, int len) {
        buf.append(cbuf, off, len);
    }

    /**
     * Write a single character.
     *
     * @param c the character to write
     */
    @Override
    public void write(int c) {
        buf.append((char) c);
    }

    /**
     * Write a string.
     *
     * @param str the string to write
     */
    @Override
    public void write(String str) {
        buf.append(str);
    }

    /**
     * Write a portion of a string.
     *
     * @param str the string
     * @param off the start offset
     * @param len the number of characters
     */
    @Override
    public void write(String str, int off, int len) {
        buf.append(str, off, len);
    }

    /**
     *  Does nothing.
     */
    public void close() {}

    /**
     *  Does nothing.
     */
    public void flush() {}

    /**
     * Get the underlying StringBuilder.
     *
     * @return the StringBuilder
     */
    public StringBuilder getBuilder() { return buf; }

    @Override
    public String toString() { return buf.toString(); }
}
