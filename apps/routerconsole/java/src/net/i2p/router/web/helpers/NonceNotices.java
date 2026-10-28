package net.i2p.router.web.helpers;

import javax.servlet.http.HttpSession;

import net.i2p.I2PAppContext;
import net.i2p.router.web.Messages;
import net.i2p.util.Log;

/**
 * A one-shot notice for a console control that refused to run because its
 * session nonce was already spent.
 *
 * <p>The console's nonce-guarded actions validate a single-use token and
 * return without a word when it fails. {@code FormHandler} adds a visible form
 * error on its own, so the config pages say so; the two controls that are not
 * form handlers — the log pages' Clear logs link and the sidebar's
 * restart/shutdown form — gave the user a dead button with no explanation at
 * all, which is what made a spent nonce look like a broken console.
 *
 * <p>A refusal is recorded on the session and rendered by the next sidebar
 * render, which clears it, so the notice cannot outlive the page it describes.
 * It is also dropped once it is older than {@link #NOTICE_TTL_MS}: a refusal
 * the user has not seen within a minute is describing a page they have
 * already left, and showing it later would be a lie about the page in front of
 * them.
 *
 * @since 0.9.72+
 */
public class NonceNotices {

    /** The log for the nonce notices. */
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(NonceNotices.class);

    /**
     * Session attribute holding the pending refusal. Package-visible so a test
     * can plant a record the producer would not have written — a stale one, a
     * truncated one, one of the wrong type.
     */
    static final String SESSION_ATTR = "__router.console.nonce.notice__";

    /**
     * How long a recorded refusal is worth showing, in ms. Long enough for the
     * submission's own page render to pick it up, short enough that a notice
     * never appears on a page the user navigated to afterwards.
     */
    static final long NOTICE_TTL_MS = 60 * 1000L;

    /** Utility class, not intended for instantiation. */
    private NonceNotices() {}

    /**
     * Record that a control refused to run so the next render says why.
     *
     * @param session the session of the request that was refused; null is a
     *                no-op, since there is nowhere to leave the notice
     * @param control what was refused, for the debug log only, e.g. "clear logs"
     */
    public static void refuse(HttpSession session, String control) {
        if (session == null) {return;}
        long now = System.currentTimeMillis();
        try {
            synchronized (session) {
                session.setAttribute(SESSION_ATTR, new String[] {control, Long.toString(now)});
            }
        } catch (IllegalStateException ise) {
            // Session invalidated mid-request; Jetty throws ISE from setAttribute.
            // Nothing to report to, so drop the notice.
            if (_log.shouldDebug()) {
                _log.debug("NonceNotices.refuse(): session invalid, notice dropped: " + control);
            }
            return;
        }
        if (_log.shouldDebug()) {
            _log.debug("NonceNotices.refuse(): control not run, session nonce spent: " + control);
        }
    }

    /**
     * The sentence for the pending refusal, cleared on reading.
     *
     * @param session the session to read; null yields no notice
     * @return the translated sentence, empty string if there is nothing to show
     */
    public static String take(HttpSession session) {
        if (session == null) {return "";}
        String[] notice;
        try {
            synchronized (session) {
                Object held = session.getAttribute(SESSION_ATTR);
                notice = held instanceof String[] ? (String[]) held : null;
                if (notice != null) {session.removeAttribute(SESSION_ATTR);}
            }
        } catch (IllegalStateException ise) {
            // Session invalidated mid-render; Jetty throws ISE from getAttribute.
            return "";
        }
        if (notice == null || notice.length < 2) {return "";}
        long issued;
        try {
            issued = Long.parseLong(notice[1]);
        } catch (NumberFormatException nfe) {
            return "";
        }
        if (isStale(System.currentTimeMillis(), issued, NOTICE_TTL_MS)) {return "";}
        return messageFor(I2PAppContext.getGlobalContext());
    }

    /**
     * Whether a recorded refusal is too old to describe the page being rendered.
     *
     * @param nowMs the current time in ms
     * @param issuedMs when the refusal was recorded, in ms
     * @param ttlMs how long a refusal stays worth showing, in ms
     * @return true if the notice should be dropped
     */
    static boolean isStale(long nowMs, long issuedMs, long ttlMs) {
        return nowMs - issuedMs > ttlMs;
    }

    /**
     * The translated sentence shown for a refusal. No parameter is substituted:
     * a translated string with a placeholder is a MessageFormat string, and an
     * apostrophe in a translation then quotes the braces and swallows the
     * value, so the sentence names the situation in prose and the control name
     * stays in the debug log.
     *
     * @param ctx the context supplying the console language
     * @return the sentence to show
     */
    static String messageFor(I2PAppContext ctx) {
        return Messages.getString("The session token of this page was already used, so the control did not run. Reload the page and try again.",
                                  ctx);
    }
}
