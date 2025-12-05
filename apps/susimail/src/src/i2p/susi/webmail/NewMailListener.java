package i2p.susi.webmail;

/**
 * Listener for notifications about new email arrivals.
 * @since 0.9.13
 */
public interface NewMailListener {
    public void foundNewMail(boolean yes);
}
