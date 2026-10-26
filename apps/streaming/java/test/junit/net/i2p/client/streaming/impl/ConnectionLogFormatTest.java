package net.i2p.client.streaming.impl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The entries a blocking connect writes as it goes. The old ones led with the
 * method name waitForConnect() and interpolated the whole Destination, several
 * hundred characters of base64, onto one line.
 */
public class ConnectionLogFormatTest {

    /** The wait entry states the budget, in prose, with a truncated peer. */
    @Test
    public void waitEntryStatesTheBudgetInProse() {
        assertEquals("Waiting for the connection to open [abcdefgh] -> Timeout: 10000ms",
                     Connection.connectWaitLine("[abcdefgh]", 10000L));
    }

    /** How the wait ended is the part worth reading, so it goes after "-&gt; ". */
    @Test
    public void doneEntrySeparatesConnectedFromGaveUp() {
        assertEquals("Connection opened while waiting [abcdefgh] -> Took: 1234ms",
                     Connection.connectDoneLine("[abcdefgh]", 1234L, false));
        assertEquals("Connection still not open [abcdefgh] -> Gave up after: 10000ms",
                     Connection.connectDoneLine("[abcdefgh]", 10000L, true));
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
}
