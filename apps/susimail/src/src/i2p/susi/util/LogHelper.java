package i2p.susi.util;

/**
 * Shared rules for naming a message in the webmail log, so every class that
 * writes about one shortens and brackets it the same way.
 *
 * @since 0.9.72+
 */
public class LogHelper {

    /**
     * Groups static helpers, so nothing instantiates this; the constructor only
     * states that an instance would carry no state.
     */
    public LogHelper() {}

    /**
     * Shortens a message id for the log. A UIDL is a base64 blob of up to 70
     * characters that nothing in the log viewer links, and it is already
     * spelled out in the cache filename, so only its head is kept. Use this
     * form when the message continues past the id and supplies its own
     * brackets, so the brackets are not written twice.
     *
     * @param uidl the server-side unique message id, may be null
     * @return the head of the id, never null
     * @since 0.9.72+
     */
    public static String head(String uidl) {
        if (uidl == null) {return "unknown";}
        return uidl.length() <= 8 ? uidl : uidl.substring(0, 8);
    }

    /**
     * Names a message id at the end of a log message, brackets included, for
     * the reader to find in the cache filename.
     *
     * @param uidl the server-side unique message id, may be null
     * @return the bracketed head of the id, never null
     * @since 0.9.72+
     */
    public static String shortId(String uidl) {
        return '[' + head(uidl) + ']';
    }
}
