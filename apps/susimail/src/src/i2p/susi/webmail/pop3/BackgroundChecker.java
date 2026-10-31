package i2p.susi.webmail.pop3;

import i2p.susi.util.Config;
import i2p.susi.webmail.WebMail;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Check for new mail periodically.
 *
 * @since 0.9.13
 */
class BackgroundChecker {

    /** The mailbox checked in the background. */
    private final POP3MailBox mailbox;
    /** The message ids marked for deletion. */
    private final Set<String> toDelete;
    /** The timer scheduling the checks. */
    private final SimpleTimer2.TimedEvent timer;
    /** True while a check is running. */
    private volatile boolean isChecking;
    /** True once the mailbox is shut down. */
    private volatile boolean isDead;
    /** Logger for the background checker. */
    private final Log _log;

    /** Default interval between the checks, in minutes. */
    private static final int DEFAULT_CHECK_MINUTES = 3*60;
    /** Smallest check interval allowed, in minutes. */
    private static final int MIN_CHECK_MINUTES = 15;
    /** Least idle time before a check runs. */
    private static final long MIN_IDLE = 30*(long) 60*1000;
    /** Least time since the last check. */
    private static final long MIN_SINCE = 60*(long) 60*1000;

    /**
     * Creates a new background checker for the given mailbox.
     *
     * @param mailbox the POP3 mailbox to check for new mail
     */
    public BackgroundChecker(POP3MailBox mailbox) {
        this.mailbox = mailbox;
        toDelete = new ConcurrentHashSet<>();
        timer = new Checker();
        _log = I2PAppContext.getGlobalContext().logManager().getLog(BackgroundChecker.class);
    }

    /**
     * Returns the list of queued deletions.
     *
     * @return a copy of the queued UIDLs
     */
    public Collection<String> getQueued() {
        List<String> rv = new ArrayList<>(toDelete);
        return rv;
    }

    /**
     * Cancels the background checker.
     */
    public void cancel() {
        isDead = true;
        timer.cancel();
    }

    /**
     * Reads the check interval from the config.
     *
     * @return the interval between checks, in ms
     */
    private long getCheckTime() {
        int minutes = DEFAULT_CHECK_MINUTES;
        String con = Config.getProperty(WebMail.CONFIG_CHECK_MINUTES);
        if (con != null) {
            try {
                int mins = Integer.parseInt(con);
                // allow shorter for testing
                if (mins < MIN_CHECK_MINUTES && !_log.shouldDebug()) {mins = MIN_CHECK_MINUTES;}
                minutes = mins;
            } catch (NumberFormatException nfe) { /* ignored */ }
        }
        return (long) minutes * 60 * 1000L;
    }

    /** Timer event starting the periodic mailbox checks. */
    private class Checker extends SimpleTimer2.TimedEvent {

        /** Schedule first check. */
        public Checker() {
            super(I2PAppContext.getGlobalContext().simpleTimer2(), getCheckTime());
        }

        @Override
        public void timeReached() {
        if (isDead) {return;}
        if (!mailbox.isConnected() && !isChecking) {
            long idle = System.currentTimeMillis() - mailbox.getLastActivity();
            long last = System.currentTimeMillis() - mailbox.getLastChecked();
            if (idle >= MIN_IDLE && last >= MIN_SINCE) {
                if (_log.shouldDebug()) {
                    _log.debug("Checking for new mail -> Idle: " + DataHelper.formatDuration(idle) +
                               " * Since the last check: " + DataHelper.formatDuration(last));
                }
                Thread t = new Getter();
                isChecking = true;
                t.start();
            } else if (_log.shouldDebug()) {
                _log.debug("Not checking for new mail yet -> Idle: " + DataHelper.formatDuration(idle) +
                           " * Since the last check: " + DataHelper.formatDuration(last));
            }
        } else if (_log.shouldDebug()) {_log.debug("Not checking for new mail -> The connection is still open");}
            schedule(getCheckTime());
        }
    }

    /** Thread fetching the new messages of a check. */
    private class Getter extends I2PAppThread {
        /** Thread that runs the mail check. */
        public Getter() {super("Susimail-Getter");}
            @Override
            public void run() {
            try {
                if (mailbox.blockingConnectToServer()) {
                    int found = mailbox.getNumMails();
                    if (found > 0) {
                        if (_log.shouldDebug()) {
                            _log.debug("Server reports " + found + " messages -> Notifying the webmail session");
                        }
                        mailbox.foundNewMail(true); // may not really be new
                    }
                }
            } finally {
                isChecking = false;
                if (!isDead) {timer.schedule(getCheckTime());}
            }
        }
    }

}
