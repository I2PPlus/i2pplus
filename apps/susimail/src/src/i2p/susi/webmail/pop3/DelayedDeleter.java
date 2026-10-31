package i2p.susi.webmail.pop3;

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
 * Queue UIDLs for later deletion.
 * We send deletions at close time but we don't wait around
 * for the answer. Also, the user may delete mails when offline.
 * So we queue them here and reconnect to delete.
 *
 * @since 0.9.13
 */
class DelayedDeleter {

    /** The mailbox the messages are deleted from. */
    private final POP3MailBox mailbox;
    /** The message ids waiting to be deleted. */
    private final Set<String> toDelete;
    /** The timer running the delete checks. */
    private final SimpleTimer2.TimedEvent timer;
    /** The log for the deleter. */
    private final Log _log;
    /** True while the delete thread runs. */
    private volatile boolean isDeleting;
    /** True once the deleter is shut down. */
    private volatile boolean isDead;
    /** Interval between the delete checks. */
    private static final long CHECK_TIME = 16*(long) 60*1000;
    /** Idle time before deleting the messages. */
    private static final long MIN_IDLE = 60*(long) 60*1000;

    /**
     * Creates a new delayed deleter for the given mailbox.
     *
     * @param mailbox the POP3 mailbox to delete from
     */
    public DelayedDeleter(POP3MailBox mailbox) {
        this.mailbox = mailbox;
        toDelete = new ConcurrentHashSet<>();
        timer = new Checker();
        _log = I2PAppContext.getGlobalContext().logManager().getLog(DelayedDeleter.class);
    }

    /**
     * Queues a UIDL for later deletion.
     *
     * @param uidl the message UIDL to delete
     */
    public void queueDelete(String uidl) {toDelete.add(uidl);}

    /**
     * Removes a UIDL from the deletion queue.
     *
     * @param uidl the message UIDL to remove from queue
     */
    public void removeQueued(String uidl) {toDelete.remove(uidl);}

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
     * Cancels the delayed deleter.
     */
    public void cancel() {
        isDead = true;
        timer.cancel();
    }

    /** Checks for messages to delete. */
    private class Checker extends SimpleTimer2.TimedEvent {
        /** Schedule first deferred-delete check. */
        public Checker() {
            super(I2PAppContext.getGlobalContext().simpleTimer2(), CHECK_TIME + 5*1000);
        }

        @Override
        public void timeReached() {
            if (isDead) {return;}
            if (!toDelete.isEmpty() && !isDeleting) {
                long idle = System.currentTimeMillis() - mailbox.getLastActivity();
                if (idle >= MIN_IDLE) {
                    if (_log.shouldDebug()) {
                        _log.debug("Deleting the queued messages -> Queued: " + toDelete.size() +
                                   " * Idle: " + DataHelper.formatDuration(idle));
                    }
                    Thread t = new Deleter();
                    isDeleting = true;
                    t.start();
                } else if (_log.shouldDebug()) {
                    _log.debug("Not deleting the queued messages yet -> Queued: " + toDelete.size() +
                               " * Idle: " + DataHelper.formatDuration(idle));
                }
            } else if (_log.shouldDebug()) {_log.debug("Not deleting any messages -> None queued");}
            schedule(CHECK_TIME);
        }
    }

    /** Deletes the waiting messages. */
    private class Deleter extends I2PAppThread {
        /** Thread that performs the deletion. */
        public Deleter() {super("Susimail-Delete");}
            @Override
            public void run() {
            try {
                int origSize = toDelete.size();
                mailbox.deletePending(false);
                int delSize = origSize - toDelete.size();
                if (_log.shouldDebug()) {
                    _log.debug("Deleted the queued messages -> Deleted: " + delSize + " * Queued: " + origSize);
                }
            } finally {
                isDeleting = false;
                if (!isDead) {timer.schedule(CHECK_TIME);}
            }
        }
    }

}
