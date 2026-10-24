package i2p.susi.webmail.pop3;

import i2p.susi.util.Config;
import i2p.susi.webmail.WebMail;
import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Close the POP3 connection after a certain idle time.
 *
 * @since 0.9.13
 */
class IdleCloser {
    /** The mailbox watched for idleness. */
    private final POP3MailBox mailbox;
    /** The timer running the idle checks. */
    private final SimpleTimer2.TimedEvent timer;
    /** The log for the closer. */
    private final Log _log;
    /** True while the close thread runs. */
    private volatile boolean isClosing;
    /** True once the closer is shut down. */
    private volatile boolean isDead;
    /**
     * Default idle time before closing, less than the five minute
     * timeout of the postman pop3 server.
     */
    private static final int DEFAULT_IDLE_SECONDS = 4*60;
    /** Smallest configured idle time. */
    private static final int MIN_IDLE_CONFIG = 60;

    /**
     * Creates a new idle closer for the given mailbox.
     *
     * @param mailbox the POP3 mailbox to monitor
     */
    public IdleCloser(POP3MailBox mailbox) {
        this.mailbox = mailbox;
        timer = new Checker();
        _log = I2PAppContext.getGlobalContext().logManager().getLog(IdleCloser.class);
    }

    /**
     * Cancels the idle closer.
     */
    public void cancel() {
        isDead = true;
        timer.cancel();
    }

    /**
     * The configured idle time.
     *
     * @return the idle time in milliseconds
     */
    private static long getMaxIdle() {
        int seconds = DEFAULT_IDLE_SECONDS;
        String con = Config.getProperty(WebMail.CONFIG_IDLE_SECONDS);
        if (con != null) {
            try {
                int secs = Integer.parseInt(con);
                if (secs < MIN_IDLE_CONFIG) {secs = MIN_IDLE_CONFIG;}
                seconds = secs;
            } catch (NumberFormatException nfe) { /* ignored */ }
        }
        return seconds * 1000L;
    }

    /** Checks the mailbox for idleness. */
    private class Checker extends SimpleTimer2.TimedEvent {
        /** Schedule first idle-close check. */
        public Checker() {
            super(I2PAppContext.getGlobalContext().simpleTimer2(), getMaxIdle() + 5*1000);
        }

        @Override
        public void timeReached() {
        if (isDead) {return;}
            if (!mailbox.isConnected()) {
                // mailbox may reconnect later, keep checking (SM-L3)
                schedule(getMaxIdle() + 5*1000);
                return;
            } // unsynchronized here, sync in thread only
            if (!isClosing) {
                long config = getMaxIdle();
                long idle = System.currentTimeMillis() - mailbox.getLastActivity();
                long remaining = config - idle;
                Log log = IdleCloser.this._log;
                if (remaining <= 0) {
                    if (log.shouldDebug()) {log.debug("Closing the connection after " + idle + " ms idle");}
                    Thread t = new Closer();
                    isClosing = true;
                    t.start();
                } else if (log.shouldDebug()) {log.debug("Keeping the connection open, idle " + idle + " ms");}
                schedule(remaining + 5000);
            }
        }
    }

    /** Closes the idle mailbox connection. */
    private class Closer extends I2PAppThread {
        /** Thread that closes the connection. */
        public Closer() {super("Susimail-Closer");}

        @Override
        public void run() {
            try {
                synchronized (mailbox.getLock()) {
                    if (!mailbox.isConnected()) {return;}
                    long config = getMaxIdle();
                    long idle = System.currentTimeMillis() - mailbox.getLastActivity();
                    long remaining = config - idle;
                    if (remaining <= 0) {
                        // If we have items to delete, wait for the response code,
                        // otherwise the DelayedDeleter thread will have to run later.
                        // Since we are threaded we can do that here.
                        boolean shouldWait = mailbox.hasQueuedDeletions();
                        mailbox.close(shouldWait);
                        isDead = true;
                    } else {timer.schedule(remaining + 5000);}
                }
            } finally {isClosing = false;}
        }
    }

}
