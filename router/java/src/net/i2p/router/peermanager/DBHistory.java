package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;

import net.i2p.router.RouterContext;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * History of NetDb related activities (lookups, replies, stores, etc)
 *
 */
public class DBHistory {
    private final Log _log;
    private final RouterContext _context;
    private long _successfulLookups;
    private long _failedLookups;
    private final RateStat _failedLookupRate;
    private final RateStat _invalidReplyRate;
    private long _lastLookupSuccessful;
    private long _lastLookupFailed;
    private long _lastStoreSuccessful;
    private long _lastStoreFailed;
    private long _unpromptedDbStoreNew;
    private long _unpromptedDbStoreOld;
    private final String _statGroup;

    public DBHistory(RouterContext context, String statGroup) {
        _context = context;
        _log = context.logManager().getLog(DBHistory.class);
        _statGroup = statGroup;
        _failedLookupRate = new RateStat("dbHistory.failedLookupRate", "How often peer responds to a lookup",
                                         statGroup, new long[] {10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _invalidReplyRate = new RateStat("dbHistory.invalidReplyRate", "How often peer gives us a bad RI?",
                                         statGroup, new long[] {10*60*1000l, 60*60*1000l, 24*60*60*1000l });
    }

    /** how many times we have sent them a db lookup and received the value back from them */
    public long getSuccessfulLookups() {return _successfulLookups;}
    /** how many times we have sent them a db lookup and not received the value or a lookup reply */
    public long getFailedLookups() {return _failedLookups;}

    /**
     *  Not persisted until 0.9.24
     *  @since 0.7.8
     */
    public long getLastLookupSuccessful() {return _lastLookupSuccessful;}

    /**
     *  Not persisted until 0.9.24
     *  @since 0.7.8
     */
    public long getLastLookupFailed() {return _lastLookupFailed;}

    /**
     *  Not persisted until 0.9.24
     *  @since 0.7.8
     */
    public long getLastStoreSuccessful() {return _lastStoreSuccessful;}

    /**
     *  Not persisted until 0.9.24
     *  @since 0.7.8
     */
    public long getLastStoreFailed() {return _lastStoreFailed;}

    /** how many times have they sent us data we didn't ask for and that we've never seen? */
    public long getUnpromptedDbStoreNew() {return _unpromptedDbStoreNew;}
    /** how many times have they sent us data we didn't ask for but that we have seen? */
    public long getUnpromptedDbStoreOld() {return _unpromptedDbStoreOld;}
    /** how often does the peer fail to reply to a lookup request, broken into 1 hour and 1 day periods */
    public RateStat getFailedLookupRate() {return _failedLookupRate;}
    /** not sure how much this is used, to be investigated */
    public RateStat getInvalidReplyRate() {return _invalidReplyRate;}

    /** Note that the peer was not only able to respond to the lookup, but sent us the data we wanted! */
    public void lookupSuccessful() {
        _successfulLookups++;
        _failedLookupRate.addData(0);
        _context.statManager().addRateData("peer.failedLookupRate", 0);
        _lastLookupSuccessful = _context.clock().now();
    }

    /** Note that the peer failed to respond to the db lookup in any way */
    public void lookupFailed() {
        _failedLookups++;
        _failedLookupRate.addData(1);
        _context.statManager().addRateData("peer.failedLookupRate", 1);
        _lastLookupFailed = _context.clock().now();
    }

    /**
     * Note that we successfully stored to a floodfill peer and verified the result by asking another floodfill peer
     *
     *  @since 0.7.8
     */
    public void storeSuccessful() {
        // Fixme, redefined this to include both lookup and store fails,
        // need to fix the javadocs
        _failedLookupRate.addData(0);
        _context.statManager().addRateData("peer.failedLookupRate", 0);
        _lastStoreSuccessful = _context.clock().now();
    }

    /**
     * Note that floodfill verify failed
     *
     *  @since 0.7.8
     */
    public void storeFailed() {
        // Fixme, redefined this to include both lookup and store fails, need to fix the javadocs
        _failedLookupRate.addData(1);
        _lastStoreFailed = _context.clock().now();
    }

    /**
     * Receive a lookup reply from the peer, where they gave us the specified info
     *
     * @param newPeers number of peers we have never seen before
     * @param oldPeers number of peers we have seen before
     * @param invalid number of peers that are invalid / out of date / otherwise b0rked
     * @param duplicate number of peers we asked them not to give us (though they're allowed to send us
     *                  themselves if they don't know anyone else)
     */
    public void lookupReply(int newPeers, int oldPeers, int invalid, int duplicate) {
        if (invalid > 0) {_invalidReplyRate.addData(invalid);}
    }

    /**
     * Note that the peer sent us a data point without us asking for it
     * @param wasNew whether we already knew about this data point or not
     */
    public void unpromptedStoreReceived(boolean wasNew) {
        if (wasNew) {_unpromptedDbStoreNew++;}
        else {_unpromptedDbStoreOld++;}
    }

    public void setSuccessfulLookups(long num) {_successfulLookups = num;}
    public void setFailedLookups(long num) {_failedLookups = num;}
    public void setUnpromptedDbStoreNew(long num) {_unpromptedDbStoreNew = num;}
    public void setUnpromptedDbStoreOld(long num) {_unpromptedDbStoreOld = num;}

    public void coalesceStats() {
        if (_log.shouldDebug()) {_log.debug("Coalescing Profile Manager stats");}
        _failedLookupRate.coalesceStats();
        _invalidReplyRate.coalesceStats();
    }

    private final static String NL = System.getProperty("line.separator");
    private final static String HR = "# ----------------------------------------------------------------------------------------";
    /** write out the data from the profile to the stream including comments */
    public void store(OutputStream out) throws IOException {store(out, true);}

    /**
     * write out the data from the profile to the stream
     * @param addComments add comment lines to the output
     * @since 0.9.41
     */
    public void store(OutputStream out, boolean addComments) throws IOException {
        StringBuilder buf = new StringBuilder(512);
        if (addComments) {
            buf.append(NL);
            buf.append(HR).append(NL);
            buf.append("# NetDb History").append(NL);
            buf.append(HR).append(NL);
        }
        add(buf, addComments, "lastLookupFailed", _lastLookupFailed, "Time of last failed lookup from peer (ms since the epoch)");
        add(buf, addComments, "lastLookupSuccessful", _lastLookupSuccessful, "Time of last successful lookup from peer (ms since the epoch)");
        add(buf, addComments, "lastStoreFailed", _lastStoreFailed, "Time of last failed store to peer (ms since the epoch)");
        add(buf, addComments, "lastStoreSuccessful", _lastStoreSuccessful, "Time of last successful store to peer (ms since the epoch)");
        add(buf, addComments, "unpromptedDbStoreNew", _unpromptedDbStoreNew, "Number of times peer sent us something unrequested and not seen before");
        add(buf, addComments, "unpromptedDbStoreOld", _unpromptedDbStoreOld, "Number of times peer sent us something unrequested but seen before");
        add(buf, addComments, "failedLookups", _failedLookups, "Number of times peer never responded to a lookup request");
        add(buf, addComments, "successfulLookups", _successfulLookups, "Number of times peer sent a valid response to a lookup request");

        out.write(buf.toString().getBytes("UTF-8"));
        _failedLookupRate.store(out, "dbHistory.failedLookupRate", addComments);
        _invalidReplyRate.store(out, "dbHistory.invalidReplyRate", addComments);
    }

    private static void add(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments) {buf.append("# ").append(description).append(": ").append(val).append(NL);}
    }

    public void load(Properties props) {
        _failedLookups = getLong(props, "dbHistory.failedLookups");
        _unpromptedDbStoreNew = getLong(props, "dbHistory.unpromptedDbStoreNew");
        _unpromptedDbStoreOld = getLong(props, "dbHistory.unpromptedDbStoreOld");
        // following 4 weren't persisted until 0.9.24
        _lastLookupSuccessful = getLong(props, "dbHistory.lastLookupSuccessful");
        _lastLookupFailed = getLong(props, "dbHistory.lastLookupFailed");
        _lastStoreSuccessful = getLong(props, "dbHistory.lastStoreSuccessful");
        _lastStoreFailed = getLong(props, "dbHistory.lastStoreFailed");
        _successfulLookups = getLong(props, "dbHistory.successfulLookups");
        try {
            _failedLookupRate.load(props, "dbHistory.failedLookupRate", true);
            _log.debug("Loading dbHistory.failedLookupRate");
        } catch (IllegalArgumentException iae) {
            _log.warn("Db History Failed Lookup rate is corrupt (" + iae.getMessage() + ") -> resetting...");
        }

        try {_invalidReplyRate.load(props, "dbHistory.invalidReplyRate", true);}
        catch (IllegalArgumentException iae) {_log.warn("Db History Invalid Reply rate is corrupt -> " + iae.getMessage());}
    }

    private final static long getLong(Properties props, String key) {
        return ProfilePersistenceHelper.getLong(props, key);
    }
}
