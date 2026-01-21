package net.i2p.router;

import net.i2p.router.util.DecayingBloomFilter;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.util.Log;

/**
 * Singleton to manage the logic (and historical data) to determine whether a message
 * is valid or not (meaning it isn't expired and hasn't already been received).  We'll
 * need a revamp once we start dealing with long message expirations (since it might
 * involve keeping a significant number of entries in memory), but that probably won't
 * be necessary until I2P 3.0.
 *
 */
public class MessageValidator {
    private final Log _log;
    private final RouterContext _context;
    private DecayingBloomFilter _filter;


    public MessageValidator(RouterContext context) {
        _log = context.logManager().getLog(MessageValidator.class);
        _context = context;
        long[] rates = new long[] { 60*1000, 60*60*1000, 24*60*60*1000 };
        context.statManager().createRateStat("router.duplicateMessageId", "Duplicate messageId received", "Router",
                                             rates);
        context.statManager().createRateStat("router.invalidMessageTime", "Message outside valid range received", "Router",
                                             rates);
    }


    /**
     * Determine if this message should be accepted as valid (not expired, not a duplicate)
     *
     * @return reason why the message is invalid (or null if the message is valid)
     */
    public String validateMessage(long messageId, long expiration) {
        String msg = validateMessage(expiration);
        if (msg != null)
            return msg;

        boolean isDuplicate = noteReception(messageId, expiration);
        if (isDuplicate) {
            if (_log.shouldInfo())
                _log.info("Rejecting message " + messageId + " duplicate", new Exception("Duplicate origin"));
            _context.statManager().addRateData("router.duplicateMessageId", 1);
            return "Duplicate message";
        } else {
            //if (_log.shouldDebug())
            //    _log.debug("Accepting message " + messageId + " because it is NOT a duplicate", new Exception("Original origin"));
            return null;
        }
    }

    /**
     * Only check the expiration for the message
     */
    public String validateMessage(long expiration) {
        long now = _context.clock().now();
        if (now - (Router.CLOCK_FUDGE_FACTOR * 3 / 2) >= expiration) {
            if (_log.shouldInfo())
                _log.info("Rejecting message expired " + (now-expiration) + "ms ago");
            _context.statManager().addRateData("router.invalidMessageTime", (now-expiration));
            return "Expired " + (now-expiration) + "ms ago";
        } else if (now + 4*Router.CLOCK_FUDGE_FACTOR < expiration) {
            if (_log.shouldInfo())
                _log.info("Rejecting message expiring too far in the future (" + (expiration-now) + "ms)");
            _context.statManager().addRateData("router.invalidMessageTime", (now-expiration));
            return "Expires too far in the future (" + (expiration-now) + "ms)";
        }
        return null;
    }

    private static final long TIME_MASK = 0xFFFFFC00;

    /**
     * Mark the message as having been received to detect duplicates.
     * Uses a decaying bloom filter to track recently received messages.
     *
     * @param messageId the unique message identifier
     * @param messageExpiration the expiration time of the message
     * @return true if we have already seen this message (duplicate), false if new
     */
    private boolean noteReception(long messageId, long messageExpiration) {
        long val = messageId;
        double fp = _filter.getFalsePositiveRate();
        // tweak phe high order bits with the message expiration /seconds/
        ////val ^= (messageExpiration & TIME_MASK) << 16;
        val ^= (messageExpiration & TIME_MASK);
        boolean dup = _filter.add(val);
        if (dup && _log.shouldWarn()) {
            _log.warn("Duplicate message [MsgID " + messageId + "] with " + _filter.getCurrentDuplicateCount()
                      + " other duplicates, " + _filter.getInsertedCount()
                      + " other entries" + (fp > 0 ? ", and a FALSE POSITIVE rate of "
                      + fp : ""));
        }
        return dup;
    }

    /**
     * Start the message validator, initializing the duplicate detection filter.
     */
    public synchronized void startup() {
        _filter = new DecayingHashSet(_context, (int)Router.CLOCK_FUDGE_FACTOR * 2, 8, "RouterMV");
    }

    /**
     * Stop the message validator and clean up resources.
     */
    synchronized void shutdown() {
        _filter.stopDecaying();
    }
}
