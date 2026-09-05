package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 *  Unit tests for {@link EventPumper#classifyConnectException(Exception)}, the
 *  exception taxonomy behind outbound connect setup handling. Classification is by
 *  exception type, not class name: genuine channel-state failures from the socket
 *  layer (already-connected, connect-pending, not-yet-connected) are NETWORK so the
 *  peer is marked unreachable, while local defects - invalid arguments, a
 *  blocking-mode channel, null internals, or any unrelated runtime failure - are
 *  OTHER so an internal bug never blames (and blacks out) a healthy remote peer.
 *
 *  <p>This pin is important: the pre-refactor string heuristic read
 *  NotYetConnectedException as OTHER (missing a real connect failure) while
 *  blaming the peer for a local IllegalBlockingModeException.
 *
 *  @since 0.9.71+
 */
public class EventPumperConnectErrorTest {

    @Test
    public void testChannelStateFailuresAreNetwork() {
        assertEquals(EventPumper.ConnectErrorKind.NETWORK,
                     EventPumper.classifyConnectException(new java.nio.channels.NotYetConnectedException()));
        assertEquals(EventPumper.ConnectErrorKind.NETWORK,
                     EventPumper.classifyConnectException(new java.nio.channels.AlreadyConnectedException()));
        assertEquals(EventPumper.ConnectErrorKind.NETWORK,
                     EventPumper.classifyConnectException(new java.nio.channels.ConnectionPendingException()));
    }

    @Test
    public void testLocalMisuseIsOther() {
        // Blocking-mode channel, invalid arguments, and null internals are local
        // defects: the connection dies but the peer did nothing wrong.
        assertEquals(EventPumper.ConnectErrorKind.OTHER,
                     EventPumper.classifyConnectException(new java.nio.channels.IllegalBlockingModeException()));
        assertEquals(EventPumper.ConnectErrorKind.OTHER,
                     EventPumper.classifyConnectException(new IllegalArgumentException()));
        assertEquals(EventPumper.ConnectErrorKind.OTHER,
                     EventPumper.classifyConnectException(new NullPointerException()));
    }

    @Test
    public void testInternalFailuresAreOther() {
        assertEquals(EventPumper.ConnectErrorKind.OTHER,
                     EventPumper.classifyConnectException(new IllegalStateException()));
        assertEquals(EventPumper.ConnectErrorKind.OTHER,
                     EventPumper.classifyConnectException(new RuntimeException("boom")));
        assertEquals(EventPumper.ConnectErrorKind.OTHER,
                     EventPumper.classifyConnectException(new java.nio.channels.ClosedSelectorException()));
    }
}