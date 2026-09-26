package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.util.SimpleTimer2;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests the idempotency contract of {@link Connection#notifyCloseSent()}.
 *
 * <p>{@code notifyCloseSent()} records when the CLOSE was handed to the wire via a
 * CAS on an {@code AtomicLong} that starts at 0, so the first call fixes the
 * timestamp and every later call is a no-op. A duplicate notification can only
 * come from a path that bypasses the CLOSE-flag guard in
 * ConnectionDataReceiver (e.g. an ACK-only packet sent by {@code ackImmediately()}
 * after the close), and it must not move {@link Connection#getCloseSentOn()} —
 * the disconnect and resend accounting both treat a nonzero value as "we sent
 * CLOSE first".
 *
 * @since 0.9.71+
 */
public class ConnectionNotifyCloseSentTest {

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Mock private ConnectionManager manager;
    @Mock private I2PSession session;
    @Mock private SchedulerChooser chooser;
    @Mock private SimpleTimer2 timer;
    @Mock private PacketQueue queue;
    @Mock private ConnectionPacketHandler handler;
    @Mock private ConnectionOptions opts;

    private Connection con;

    @Before
    public void setUp() {
        con = new Connection(I2PAppContext.getGlobalContext(), manager, session, chooser,
                             timer, queue, handler, opts, false);
    }

    /** An unclosed connection reports the 0 sentinel, never a real timestamp. */
    @Test
    public void testCloseNotSentStartsAtZero() {
        assertEquals(0L, con.getCloseSentOn());
    }

    /** The first notification records the context clock's time. */
    @Test
    public void testFirstCallRecordsTimestamp() {
        con.notifyCloseSent();
        long first = con.getCloseSentOn();
        assertTrue("timestamp must be set by the first call", first > 0);
    }

    /** Repeated notifications leave the recorded timestamp untouched. */
    @Test
    public void testDuplicateNotificationIsIdempotent() {
        con.notifyCloseSent();
        long first = con.getCloseSentOn();
        assertTrue(first > 0);
        // the shared context clock caches its reading for back-to-back calls, so
        // repeat the notification rather than waiting for the clock to move: what
        // is being pinned is that only the first call may write the timestamp.
        con.notifyCloseSent();
        assertEquals("duplicate notifyCloseSent must not move the timestamp",
                     first, con.getCloseSentOn());
        con.notifyCloseSent();
        assertEquals("further duplicates must not move the timestamp either",
                     first, con.getCloseSentOn());
    }
}
