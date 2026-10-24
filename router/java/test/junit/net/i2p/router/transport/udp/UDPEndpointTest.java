package net.i2p.router.transport.udp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Loopback over a real datagram socket: a packet handed to one endpoint's
 * sender arrives intact at the other endpoint's {@link UDPEndpoint#receive()},
 * the direct-from-socket path reserved for testing. Replaces the old
 * UDPEndpointTestStandalone, whose writer never worked past SSU1.
 */
public class UDPEndpointTest {

    private RouterContext _ctx;
    private UDPEndpoint _from;
    private UDPEndpoint _to;
    private UDPPacket _sent;
    private volatile UDPPacket _received;
    /** The payload copied out of the received datagram by the reader thread. */
    private volatile byte[] _result;

    @Before
    public void setUp() throws Exception {
        _ctx = RouterTestHelper.newContext();
        assumeTrue("No RouterContext available", _ctx != null);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        // no transport, so no receiver thread competes with receive()
        _from = new UDPEndpoint(_ctx, null, 0, loopback);
        _to = new UDPEndpoint(_ctx, null, 0, loopback);
        _from.startup();
        _to.startup();
        assertTrue("endpoint did not bind a port", _from.getListenPort() > 0);
        assertTrue("endpoint did not bind a port", _to.getListenPort() > 0);
    }

    @After
    public void tearDown() {
        if (_from != null)
            _from.shutdown();
        if (_to != null)
            _to.shutdown();
        // the receive thread releases the packet on success; cover the failure paths
        if (_received == null && _sent != null)
            _sent.release();
    }

    @Test
    public void testLoopbackSendReceive() throws Exception {
        byte[] payload = new byte[64];
        _ctx.random().nextBytes(payload);
        final CountDownLatch got = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            UDPPacket packet = _to.receive();
            if (packet != null) {
                // copy out before the pooled buffer can be reused
                int len = packet.getPacket().getLength();
                byte[] data = new byte[len];
                System.arraycopy(packet.getPacket().getData(), packet.getPacket().getOffset(), data, 0, len);
                _received = packet;
                _result = data;
                packet.release();
                got.countDown();
            }
        }, "UDPEndpointTest reader");
        reader.setDaemon(true);
        reader.start();

        _sent = UDPPacket.acquire(_ctx, false);
        _sent.getPacket().setData(payload);
        _sent.getPacket().setLength(payload.length);
        _sent.getPacket().setAddress(InetAddress.getByName("127.0.0.1"));
        _sent.getPacket().setPort(_to.getListenPort());
        _from.send(_sent);

        assertTrue("packet not received within 5s", got.await(5, TimeUnit.SECONDS));
        assertNotNull(_received);
        assertEquals(payload.length, _result.length);
        assertArrayEquals(payload, _result);
    }
}
