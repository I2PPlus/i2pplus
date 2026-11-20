package i2p.susi.webmail;

import java.io.File;

/**
 * How much of a mailbox one refresh is allowed to pull down.
 *
 * A POP3 server can list a very large number of messages, and a refresh used to turn
 * every UIDL it reported into a Mail object, a request, and a temporary file in the
 * router's temp directory, with no ceiling on the count, the bytes, or the free space
 * left to hold them. A mailbox with tens of thousands of messages, or a server that
 * reports one, grew the webmail session until the router ran out of heap or inodes, and
 * the user saw nothing about it.
 *
 * A budget is asked before a message is queued for fetching, and told what the fetch
 * actually cost afterwards, so the ceiling holds even when the size the server
 * advertised is wrong. It is per refresh: a mailbox larger than the budget still loads,
 * over more than one refresh, and the caller is told that it stopped short.
 *
 * @since 0.9.72+
 */
class FetchBudget {

    /** Messages one refresh will fetch. */
    static final int MAX_MESSAGES = 500;
    /** Message bytes one refresh will fetch. */
    static final long MAX_BYTES = 64L * 1024 * 1024;
    /**
     * Free space the temp directory must still have after a fetch. Message bodies are
     * written there before they are parsed, so a fetch that fills the disk loses more
     * than the message it was fetching.
     */
    static final long MIN_FREE_BYTES = 32L * 1024 * 1024;
    /**
     * Requests per round trip to the server. The whole list used to be sent as one
     * batch, so nothing could be kept, and nothing could stop, part way through.
     */
    static final int BATCH_SIZE = 20;
    /**
     * What a header-only fetch is charged against the byte allowance. A header is
     * about 1-1.5 KB, and charging it the size of the message it belongs to would make
     * a folder of headers look like a folder of bodies.
     */
    static final int HEADER_BYTES = 1536;

    /** Why the budget stopped, or why it has not. */
    enum Reason {
        /** Nothing stopped this fetch. */
        NONE,
        /** The message count for this refresh is used up. */
        COUNT,
        /** The byte allowance for this refresh is used up. */
        BYTES,
        /** The temp directory has too little space left. */
        DISK
    }

    /** Directory the fetched bodies are written to. */
    private final File _tempDir;
    /** Messages fetched so far. */
    private int _messages;
    /** Bytes fetched so far. */
    private long _bytes;
    /** Why the last request was refused, NONE if it was not. */
    private Reason _reason = Reason.NONE;

    /**
     * A budget for one refresh of one folder.
     *
     * @param tempDir the directory fetched bodies are written to, for the free-space
     *                check; null to skip that check
     */
    FetchBudget(File tempDir) {
        _tempDir = tempDir;
    }

    /**
     * Would fetching one more thing of this size stay inside the budget? The answer is
     * decided by {@link #stopReason}, which is where the thresholds are pinned. Nothing
     * is charged here, so the caller may ask again after finding the message is already
     * on disk.
     *
     * @param size the bytes this fetch would cost, or 0 if unknown
     * @return true if the fetch may go ahead
     */
    boolean allows(int size) {
        _reason = stopReason(_messages, _bytes, freeSpace(), size,
                             MAX_MESSAGES, MAX_BYTES, MIN_FREE_BYTES);
        return _reason == Reason.NONE;
    }

    /**
     * Commit one request to the budget, charged at the size it was expected to cost.
     * A request that then fails still cost the round trip and the slot in the list, so
     * it stays charged.
     *
     * @param size bytes charged, a negative value is charged as nothing
     */
    void queued(int size) {
        _messages++;
        if (size > 0)
            _bytes += size;
    }

    /**
     * Correct the tally to what a fetch actually brought, which is what keeps the
     * ceiling honest when the size the server listed is wrong.
     *
     * @param delta bytes more than charged, or fewer if negative
     */
    void correct(int delta) {
        _bytes += delta;
        if (_bytes < 0)
            _bytes = 0;
    }

    /**
     * Why the last refused fetch was refused, for the message shown to the user.
     *
     * @return the reason, NONE if nothing has been refused yet
     */
    Reason getReason() {
        return _reason;
    }

    /**
     * Messages fetched so far.
     *
     * @return requests queued for this refresh, including the ones still in flight
     */
    int getMessages() {
        return _messages;
    }

    /**
     * Bytes fetched so far.
     *
     * @return bytes charged against this refresh, at the sizes the server listed until
     *         {@link #correct} replaces them with what arrived
     */
    long getBytes() {
        return _bytes;
    }

    /**
     * Whether the temp directory can still hold another batch of this refresh.
     *
     * The message and byte ceilings are asked before a fetch is queued. The loop that
     * then sends what was queued asks only this: those fetches are already charged
     * against the ceilings, so re-asking the count would refuse the very messages the
     * queueing loop admitted, and at exactly the ceiling a refresh would queue its
     * whole allowance and send none of it. What can still go wrong while the bodies are
     * arriving is the disk filling up, and that is what is asked here.
     *
     * @return true if the next batch may go out
     */
    boolean hasDiskSpace() {
        return hasDiskSpace(freeSpace(), MIN_FREE_BYTES);
    }

    /**
     * The disk half of {@link #stopReason} on its own, with the numbers handed to it
     * rather than read from fields, so the boundary is testable without a mailbox.
     *
     * @param free bytes available in the temp directory, Long.MAX_VALUE if there is no
     *             directory to ask
     * @param minFree the free space the directory must be left with
     * @return true if the space is there
     */
    static boolean hasDiskSpace(long free, long minFree) {
        return free >= minFree;
    }

    /**
     * How much space the temp directory has left.
     *
     * @return the bytes available, or Long.MAX_VALUE if there is no directory to ask,
     *         so a directory the router cannot read does not stop every fetch. A
     *         reading of 0 is taken as a full disk, which is what it usually means.
     */
    private long freeSpace() {
        if (_tempDir == null || !_tempDir.isDirectory())
            return Long.MAX_VALUE;
        long free = _tempDir.getUsableSpace();
        return free > 0 ? free : 0;
    }

    /**
     * The decision, with the numbers handed to it rather than read from fields, so the
     * thresholds can be tested without a mailbox, a server, or a disk.
     *
     * A size of zero or less is charged nothing up front, which is what a server that
     * did not list a size gives; that message is still counted, and its real cost is
     * charged afterwards by {@link #correct}.
     *
     * @param fetched messages fetched so far
     * @param bytes bytes fetched so far
     * @param free bytes free in the temp directory
     * @param size the bytes this fetch would cost
     * @param maxMessages the message ceiling for one refresh
     * @param maxBytes the byte ceiling for one refresh
     * @param minFree the free space the temp directory must keep
     * @return why this fetch may not go ahead, NONE if it may
     */
    static Reason stopReason(int fetched, long bytes, long free, int size,
                             int maxMessages, long maxBytes, long minFree) {
        if (fetched >= maxMessages)
            return Reason.COUNT;
        long want = size > 0 ? size : 0;
        if (bytes + want > maxBytes)
            return Reason.BYTES;
        if (!hasDiskSpace(free, minFree + want))
            return Reason.DISK;
        return Reason.NONE;
    }

    /**
     * The reason in words, for the log.
     *
     * @param reason the reason to name
     * @return what was used up, in prose
     */
    static String describe(Reason reason) {
        switch (reason) {
            case COUNT:
                return "the message count for one refresh";
            case BYTES:
                return "the byte allowance for one refresh";
            case DISK:
                return "free space in the temp directory";
            default:
                return "nothing yet";
        }
    }
}
