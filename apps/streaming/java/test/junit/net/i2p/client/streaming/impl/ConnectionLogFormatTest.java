package net.i2p.client.streaming.impl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The entries a blocking connect writes as it goes. The old ones led with the
 * method name waitForConnect() and interpolated the whole Destination, several
 * hundred characters of base64, onto one line.
 */
public class ConnectionLogFormatTest {

    /** Runs the cases; it holds no state of its own. */
    public ConnectionLogFormatTest() {
        super();
    }

    /** The wait entry states the budget, in prose, with a truncated peer. */
    @Test
    public void waitEntryStatesTheBudgetInProse() {
        assertEquals("Waiting for the connection to open [abcdefgh] -> Timeout: 10s",
                     Connection.connectWaitLine("[abcdefgh]", 10000L));
    }

    /** A 0 budget means the wait is unbounded, and the line has to say so. */
    @Test
    public void waitEntryNamesAnUnboundedWait() {
        assertEquals("Waiting for the connection to open [abcdefgh] -> No timeout",
                     Connection.connectWaitLine("[abcdefgh]", 0L));
    }

    /** How the wait ended is the part worth reading, so it goes after "-&gt; ". */
    @Test
    public void doneEntrySeparatesConnectedFromGaveUp() {
        assertEquals("Connection opened while waiting [abcdefgh] -> Took: 1234ms",
                     Connection.connectDoneLine("[abcdefgh]", 1234L, false));
        assertEquals("Connection still not open [abcdefgh] -> Gave up after: 10s",
                     Connection.connectDoneLine("[abcdefgh]", 10000L, true));
    }

    /**
     * The give-up entry carries the SYN send count - the number that says
     * whether the connect ran out of sends or out of wall-clock budget.
     */
    @Test
    public void synGiveUpEntryCountsTheUnansweredSends() {
        assertEquals("[abcdefgh] -> SYN not acknowledged, closing" +
                     "\n* SYN sends: 12 * Soft resends: 3 * Age: 9s",
                     Connection.synGiveUpLine("[abcdefgh]", false, 12, 3, 9000L));
    }

    /**
     * A connection the peer opened never sends a SYN, so its give-up cannot
     * report one as unanswered: the sequence-0 packet is the SYN-ACK, and
     * "SYN not acknowledged" reads as a failed outbound connect on what is a
     * server connection.
     */
    @Test
    public void synGiveUpEntryNamesTheSynAckOnAnInboundConnection() {
        assertEquals("[abcdefgh] -> SYN-ACK not acknowledged, closing" +
                     "\n* SYN-ACK sends: 4 * Soft resends: 0 * Age: 13s",
                     Connection.synGiveUpLine("[abcdefgh]", true, 4, 0, 13000L));
    }

    /**
     * The rotation entry's number is the budget left, because that is what says
     * whether a fresh tunnel could be used at all - a spare is instant, a build
     * is not.
     */
    @Test
    public void rotationEntryStatesTheBudgetLeft() {
        assertEquals("[abcdefgh] -> SYN not acknowledged after 3 sends, " +
                     "rotating to a fresh outbound tunnel * Budget left: 6s",
                     Connection.synRotationLine("[abcdefgh]", false, 3, 6000L));
    }

    /**
     * On a connection the peer opened, the packet the fresh outbound tunnel
     * exists to carry is our SYN-ACK, and saying "SYN" here describes the other
     * direction of connection.
     */
    @Test
    public void rotationEntryNamesTheSynAckOnAnInboundConnection() {
        assertEquals("[abcdefgh] -> SYN-ACK not acknowledged after 2 sends, " +
                     "rotating to a fresh outbound tunnel * Budget left: 60s",
                     Connection.synRotationLine("[abcdefgh]", true, 2, 60000L));
    }

    /**
     * The manager's result line names the outcome, then the reason, then how
     * long the attempt ran - never the method that wrote it.
     */
    @Test
    public void managerResultLineSeparatesOpenedFromFailed() {
        assertEquals("Connect to [abcdefgh] opened * Elapsed: 1234ms",
                     ConnectionManager.connectResultLine("abcdefgh", null, 1234L));
        assertEquals("Connect to [abcdefgh] failed -> Connection timed out: SYN not acknowledged" +
                     " * Elapsed: 45s",
                     ConnectionManager.connectResultLine("abcdefgh",
                         "Connection timed out: SYN not acknowledged", 45000L));
    }

    /**
     * The inactivity entry names the configured action instead of printing the
     * bare option number, so the reader sees what the router is about to do.
     */
    @Test
    public void inactivityActionNamesTheActionNotTheNumber() {
        assertEquals("No action",
                     Connection.inactivityActionName(ConnectionOptions.INACTIVITY_ACTION_NOOP));
        assertEquals("Disconnect",
                     Connection.inactivityActionName(ConnectionOptions.INACTIVITY_ACTION_DISCONNECT));
        assertEquals("Send keepalive",
                     Connection.inactivityActionName(ConnectionOptions.INACTIVITY_ACTION_SEND));
        // A value outside the set must not read as one of the three real actions.
        assertEquals("Unknown", Connection.inactivityActionName(-1));
        assertEquals("Unknown", Connection.inactivityActionName(3));
    }

    /**
     * A stalled established stream says what it measured and what it does about
     * it: the run length and firing count say whether one packet was lost or the
     * tunnel is carrying nothing, and the fresh tunnel is the answer to the
     * second case.
     */
    @Test
    public void stallEntryStatesTheStallAndTheRotation() {
        assertEquals("[abcdefgh] -> No forward ACK progress for 30s across 16 retransmit firings" +
                     "\n* Rotating the next send to a fresh outbound tunnel",
                     Connection.stallRotationLine("[abcdefgh]", 30000L, 16));
    }

    /**
     * The run length comes from the unit formatter, not from dividing by 1000:
     * a stall of minutes has to read as minutes, where the arithmetic form
     * printed 185s.
     */
    @Test
    public void stallEntryFormatsTheRunLengthInItsOwnUnits() {
        assertEquals("[abcdefgh] -> No forward ACK progress for 3m across 24 retransmit firings" +
                     "\n* Rotating the next send to a fresh outbound tunnel",
                     Connection.stallRotationLine("[abcdefgh]", 185000L, 24));
    }
}
