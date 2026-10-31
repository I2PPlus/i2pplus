package i2p.susi.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * OutputStream that performs case-insensitive regex replacement during streaming.
 *
 * @since 0.9.62
 */
public class RegexOutputStream extends FilterOutputStream {

    /** Pattern string to match, lowercased. */
    private final String match;
    /** Replacement string. */
    private final String repl;
    /** Parse in progress. */
    private final StringBuilder buf;
    /** On-no-match fallback string. */
    private final String noMatch;
    /** Logger. */
    private final Log _log;
    /** Whether a match was found. */
    private boolean found;
    /** Match position within the pattern. */
    int idx;

    /**
     * Construct with a literal pattern that may contain a single '*' wildcard.
     *
     * @param out MUST be buffered because this writes one byte at a time
     * @param pattern the only special char recognized is '*' and cannot be at the beginning or end
     * @param replace the replacement string, ASCII-only, no UTF-8
     * @param onNoMatch force output of this at the end if no replacement made, or null
     */
    public RegexOutputStream(OutputStream out, String pattern, String replace, String onNoMatch) {
        super(out);
        if (pattern.isEmpty() || pattern.startsWith("*") || pattern.endsWith("*") || pattern.contains("**"))
            throw new IllegalArgumentException();
        match = pattern.toLowerCase(Locale.US);
        repl = replace;
        noMatch = onNoMatch;
        buf = new StringBuilder(64);
        _log = I2PAppContext.getGlobalContext().logManager().getLog(RegexOutputStream.class);
    }

        /**
         * Write a single byte, with streaming pattern matching.
         */
    @Override
    public void write(int val) throws IOException {
        char c = (char) val;
        char m = match.charAt(idx);
        if (c == '\r' || c == '\n') {
            flushit();
            out.write(val);
        } else if (m == '*') {
            pushit(c);
            char d = match.charAt(idx + 1);
            if (c == d) {
                idx += 2;
                if (idx == match.length()) {
                    replaceit();
                    found = true;
                }
            }
        } else if (m == Character.toLowerCase(c)) {
            pushit(c);
            idx++;
            if (idx == match.length()) {
                replaceit();
                found = true;
            }
        } else {
            flushit();
            out.write(val);
        }
    }


    /**
     * Put in the pending parse buf.
     *
     * @param c the character to append to the parse buffer
     */
    private void pushit(char c) {
        buf.append(c);
    }

    /**
     * Flush buf to out, start over.
     *
     * @throws IOException if writing to the underlying writer fails
     */
    private void flushit() throws IOException {
        int len = buf.length();
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                out.write(buf.charAt(i));
            }
            clearit();
        }
    }
    /**
     * Throw out inbuf, output replacement, start over.
     *
     * @throws IOException if writing the replacement to the underlying writer fails
     */
    private void replaceit() throws IOException {
        int len = repl.length();
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                out.write(repl.charAt(i));
            }
        }
        if (_log.shouldDebug()) {
            _log.debug("Replaced the matched text -> Pattern: " + logText(match) + " * Replacement: " + logText(repl) +
                       " * Buffer: " + logText(buf.toString()));
        }
        clearit();
    }

    /**
     * Keeps a pattern, replacement or buffer readable in the log. These values
     * are HTML fragments of several hundred characters, and one of them per
     * replacement would bury the message the reader is scanning for.
     *
     * @param s the value to shorten, never null
     * @return the value, or its head with an ellipsis when it is long
     * @since 0.9.72+
     */
    static String logText(String s) {
        if (s.length() <= 40) {return s;}
        return s.substring(0, 40) + "...";
    }

    /**
     * Start over.
     */
    private void clearit() {
        buf.setLength(0);
        idx = 0;
    }

        /**
         * Flush pending output and close.
         */
    @Override
    public void close() throws IOException {
        flushit();
        if (!found && noMatch != null) {
            if (_log.shouldDebug()) {
                _log.debug("Pattern not found -> Appending the fallback text\n* Pattern: " + logText(match) +
                           " * Fallback: " + logText(noMatch));
            }
            out.write(DataHelper.getASCII(noMatch));
        }
        super.close();
    }

}
