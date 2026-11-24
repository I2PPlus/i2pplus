package net.i2p.data.i2cp;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Every I2CP message and data test in this package,
 * run as one suite by {@code ant test}. A new message
 * test is added to the list below rather than run on
 * its own, so the suite stays the whole picture of
 * the wire format.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    AbuseReasonTest.class,
    AbuseSeverityTest.class,
    BandwidthLimitsMessageTest.class,
    CreateLeaseSetMessageTest.class,
    CreateSessionMessageTest.class,
    DestLookupMessageTest.class,
    DestReplyMessageTest.class,
    DestroySessionMessageTest.class,
    DisconnectMessageTest.class,
    GetBandwidthLimitsMessageTest.class,
    GetDateMessageTest.class,
    HostReplyMessageTest.class,
    MessageIdTest.class,
    MessagePayloadMessageTest.class,
    MessageStatusMessageTest.class,
    ReceiveMessageBeginMessageTest.class,
    ReceiveMessageEndMessageTest.class,
    ReconfigureSessionMessageTest.class,
    ReportAbuseMessageTest.class,
    RequestLeaseSetMessageTest.class,
    SendMessageExpiresMessageTest.class,
    SendMessageMessageTest.class,
    SessionConfigTest.class,
    SessionIdTest.class,
    SessionStatusMessageTest.class,
    SetDateMessageTest.class,
})
public class I2CPTestSuite {

    /**
     * Holds the suite list only. JUnit builds this class
     * to read the annotation, and never calls a test
     * method on it.
     */
    public I2CPTestSuite() {}
}
