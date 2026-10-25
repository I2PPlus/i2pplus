package net.i2p.util;

/**
 * The single source of truth for output escaping, one method per output
 * context. Escaping is context-dependent: what is safe as HTML text or inside
 * a quoted attribute is not safe inside a script block or an unquoted
 * attribute, so pick the method for the context the value is written into,
 * not the shortest one.
 *
 * <p>Every method returns the input unchanged (the same instance, no
 * allocation) when it holds nothing to escape, which is the common case for
 * console output, and returns null for null input. Percent-encoding URLs is
 * deliberately not here - it is a different job with different callers, and
 * the existing per-app urlEncode helpers stay where they are.
 *
 * <p>Do not add escapers elsewhere; the audit tooling
 * (tools/scripts/xss-audit.py) flags new escape*() definitions outside this
 * class and DataHelper's delegating wrappers.
 *
 * @since 0.9.72+
 */
public class Escapes {

    /** Entity for an ampersand, emitted first so entities are never double-encoded. */
    private static final String AMP = "&amp;";

    /** Entity for a double quote. */
    private static final String QUOT = "&quot;";

    /** Entity for a less-than sign. */
    private static final String LT = "&lt;";

    /** Entity for a greater-than sign. */
    private static final String GT = "&gt;";

    /** Entity for an apostrophe. */
    private static final String APOS = "&apos;";

    /** Entity for a backtick, only significant in HTML (it opens an unquoted attribute). */
    private static final String BACKTICK = "&#96;";

    /**
     * JS hex escape prefix for the C0 controls, indexed by the character
     * itself; only entries 0x00 through 0x1f are ever read, the rest are
     * placeholders for characters emitted as short escapes or entities instead.
     */
    private static final String[] JS_HEX = new String[0x20];

    /** JS escape for a backslash, emitted as the two-character sequence. */
    private static final String JS_BSLASH = "\\\\";

    /** JS short escape for a line feed. */
    private static final String JS_NL = "\\n";

    /** JS short escape for a carriage return. */
    private static final String JS_CR = "\\r";

    /** JS hex escape for a vertical tab. */
    private static final String JS_VT = "\\x0b";

    /** JS hex escape for a form feed. */
    private static final String JS_FF = "\\x0c";

    /** JS unicode escape for the line separator U+2028, a line terminator in JS string literals. */
    private static final String JS_LS = "\\u2028";

    /** JS unicode escape for the paragraph separator U+2029, a line terminator in JS string literals. */
    private static final String JS_PS = "\\u2029";

    /** JS hex escape for a less-than sign. */
    private static final String JS_LT = "\\x3c";

    /** JS hex escape for a greater-than sign. */
    private static final String JS_GT = "\\x3e";

    /** JS hex escape for a double quote. */
    private static final String JS_QUOT = "\\x22";

    /** JS hex escape for an apostrophe. */
    private static final String JS_APOS = "\\x27";

    /** JS hex escape for an ampersand. */
    private static final String JS_AMP = "\\x26";

    static {
        for (int i = 0; i < JS_HEX.length; i++) {
            JS_HEX[i] = "\\x" + (i < 0x10 ? "0" : "") + Integer.toHexString(i);
        }
    }

    /**
     * No instances, this is a static utility.
     */
    private Escapes() {}

    /**
     * Escape a string for HTML text content or for a quoted attribute value.
     * Encodes the five markup metacharacters plus the backtick, so the result
     * is also safe where an attribute might be written unquoted (a backtick
     * opens an unquoted attribute in HTML5). The backtick renders identically
     * in text content.
     *
     * <p>Byte-compatible with the historical DataHelper.escapeHTML chain for
     * the five original characters, ampersand first so existing entities are
     * encoded, not re-expanded. Not for script blocks, event handlers, URLs or
     * CSS - use jsString() there and percent-encode URLs.
     *
     * @param s the string to escape, null passes through
     * @return the escaped string, the same instance if nothing needed
     *         escaping, or null if the input was null
     * @since 0.9.72+
     */
    public static String html(String s) {
        return s == null ? null : markup(s, true);
    }

    /**
     * Escape a string for XML (including SVG) character data and quoted
     * attribute values. Encodes the five XML metacharacters; the backtick is
     * not special in XML, so it is left alone, unlike html().
     *
     * @param s the string to escape, null passes through
     * @return the escaped string, the same instance if nothing needed
     *         escaping, or null if the input was null
     * @since 0.9.72+
     */
    public static String xml(String s) {
        return s == null ? null : markup(s, false);
    }

    /**
     * Escape a string for embedding in a JavaScript string literal, e.g. a
     * value written into a script block or into an attribute that page
     * scripts read back. A backslash is escaped first so the sequences
     * inserted here are never re-escaped; quotes, angle brackets and the
     * ampersand become two-digit hex escapes so the result cannot close the
     * literal or open markup.
     *
     * <p>Everything that can terminate a JS string literal line is encoded:
     * CR and LF as short escapes, U+2028 and U+2029 (legal in JSON, line
     * terminators in JS) as unicode escapes, and all remaining C0 controls,
     * including vertical tab and form feed, as hex escapes. Non-ASCII text
     * otherwise passes through unchanged, so the output is not ASCII-only.
     *
     * <p>Not for URL or CSS contexts.
     *
     * @param s the string to escape, null passes through
     * @return the escaped string, the same instance if nothing needed
     *         escaping, or null if the input was null
     * @since 0.9.72+
     */
    public static String jsString(String s) {
        if (s == null) {
            return null;
        }
        int n = s.length();
        int i = 0;
        while (i < n && !_needsJsEscape(s.charAt(i))) {
            i++;
        }
        if (i == n) {
            return s;
        }
        StringBuilder buf = new StringBuilder(n + 16);
        buf.append(s, 0, i);
        do {
            char c = s.charAt(i);
            if (c == '\n') {
                buf.append(JS_NL);
            } else if (c == '\r') {
                buf.append(JS_CR);
            } else if (c < JS_HEX.length) {
                buf.append(JS_HEX[c]);
            } else {
                switch (c) {
                    case '\\':
                        buf.append(JS_BSLASH);
                        break;
                    case '<':
                        buf.append(JS_LT);
                        break;
                    case '>':
                        buf.append(JS_GT);
                        break;
                    case '"':
                        buf.append(JS_QUOT);
                        break;
                    case '\'':
                        buf.append(JS_APOS);
                        break;
                    case '&':
                        buf.append(JS_AMP);
                        break;
                    case '\u2028':
                        buf.append(JS_LS);
                        break;
                    case '\u2029':
                        buf.append(JS_PS);
                        break;
                    default:
                        buf.append(c);
                        break;
                }
            }
            i++;
            int start = i;
            while (i < n && !_needsJsEscape(s.charAt(i))) {
                i++;
            }
            buf.append(s, start, i);
        } while (i < n);
        return buf.toString();
    }

    /**
     * Shared single-pass implementation of html() and xml(). Scans for the
     * first escapable character so clean input is returned untouched; from
     * there one pass copies runs and substitutes entities. The ampersand case
     * comes first in the switch so an entity inserted here is never re-encoded
     * by a later case, matching the historical sequential-replace order.
     *
     * @param s the string to escape, not null
     * @param html true to also encode the backtick (HTML contexts), false for
     *              the XML character set
     * @return the escaped string, the same instance if nothing needed escaping
     */
    private static String markup(String s, boolean html) {
        int n = s.length();
        int i = 0;
        while (i < n && !_needsMarkupEscape(s.charAt(i), html)) {
            i++;
        }
        if (i == n) {
            return s;
        }
        StringBuilder buf = new StringBuilder(n + 16);
        buf.append(s, 0, i);
        do {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    buf.append(AMP);
                    break;
                case '"':
                    buf.append(QUOT);
                    break;
                case '<':
                    buf.append(LT);
                    break;
                case '>':
                    buf.append(GT);
                    break;
                case '\'':
                    buf.append(APOS);
                    break;
                case '`':
                    // only reached when html is true, _needsMarkupEscape gates it
                    buf.append(BACKTICK);
                    break;
                default:
                    buf.append(c);
                    break;
            }
            i++;
            int start = i;
            while (i < n && !_needsMarkupEscape(s.charAt(i), html)) {
                i++;
            }
            buf.append(s, start, i);
        } while (i < n);
        return buf.toString();
    }

    /**
     * True when a character must be encoded by html() or xml().
     *
     * @param c the character to test
     * @param html true to include the backtick in the set
     * @return true if the character needs an entity
     */
    private static boolean _needsMarkupEscape(char c, boolean html) {
        switch (c) {
            case '&':
            case '"':
            case '<':
            case '>':
            case '\'':
                return true;
            case '`':
                return html;
            default:
                return false;
        }
    }

    /**
     * True when a character must be encoded by jsString(): the C0 controls,
     * the backslash, the quote characters, the markup characters and the two
     * Unicode line terminators.
     *
     * @param c the character to test
     * @return true if the character needs an escape sequence
     */
    private static boolean _needsJsEscape(char c) {
        if (c < 0x20) {
            return true;
        }
        switch (c) {
            case '\\':
            case '<':
            case '>':
            case '"':
            case '\'':
            case '&':
            case '\u2028':
            case '\u2029':
                return true;
            default:
                return false;
        }
    }
}
