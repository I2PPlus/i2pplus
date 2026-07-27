package i2p.susi.webmail;

/**
 * Listener for notifications about new email arrivals.
 *
 * <p>Callback interface used by POP3MailBox to notify listeners when new mail arrives,
 * enabling real-time updates in the webmail interface.</p>
 *
 * @since 0.9.13
 */
public interface NewMailListener {
    /** @param yes true if new mail was found */
    public void foundNewMail(boolean yes);
}
