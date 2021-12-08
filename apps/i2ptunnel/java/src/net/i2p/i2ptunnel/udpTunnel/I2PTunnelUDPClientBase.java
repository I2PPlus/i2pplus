/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel.udpTunnel;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.crypto.SigType;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelTask;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.EventDispatcher;

    /**
     * Base client class that sets up an I2P Datagram client destination.
     * The UDP side is not implemented here, as there are at least
     * two possibilities:
     *
     * 1) UDP side is a "server"
     *    Example: Streamr Consumer
     *    - Configure a destination host and port
     *    - External application sends no data
     *    - Extending class must have a constructor with host and port arguments
     *
     * 2) UDP side is a client/server
     *    Example: SOCKS UDP (DNS requests?)
     *    - configure an inbound port and a destination host and port
     *    - External application sends and receives data
     *    - Extending class must have a constructor with host and 2 port arguments
     *
     * So the implementing class must create a UDPSource and/or UDPSink,
     * and must call setSink().
     *
     * @author zzz with portions from welterde's streamr
     */
 public abstract class I2PTunnelUDPClientBase extends I2PTunnelTask implements Source, Sink {

    protected I2PAppContext _context;
    protected Logging l;

    static final long DEFAULT_CONNECT_TIMEOUT = 60 * 1000;

    private static final AtomicLong __clientId = new AtomicLong();
    protected long _clientId;

    private final Object startLock = new Object();

    private final I2PSession _session;
    private final Source _i2pSource;
    private final Sink _i2pSink;

    /**
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
   public I2PTunnelUDPClientBase(String destination, Logging l, EventDispatcher notifyThis,
                                  I2PTunnel tunnel) throws IllegalArgumentException {
        super("UDPServer", notifyThis, tunnel);
        _clientId = __clientId.incrementAndGet();
        this.l = l;

        _context = tunnel.getContext();

        tunnel.getClientOptions().setProperty("i2cp.dontPublishLeaseSet", "true");

        // create i2pclient and destination
        I2PClient client = I2PClientFactory.createClient();
        byte[] key;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            SigType stype = I2PClient.DEFAULT_SIGTYPE;
            String st = tunnel.getClientOptions().getProperty(I2PClient.PROP_SIGTYPE);
            if (st != null) {
                SigType type = SigType.parseSigType(st);
                if (type != null)
                    stype = type;
                else
                    l.log("Unsupported sig type " + st);
            }
            client.createDestination(out, stype);
            key = out.toByteArray();
        } catch(Exception exc) {
            throw new RuntimeException("failed to create i2p-destination", exc);
        }

        // create a session
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(key);
            // FIXME this may not pick up non-default I2CP host/port settings from tunnel
            _session = client.createSession(in, tunnel.getClientOptions());
            connected(_session);
        } catch(Exception exc) {
            throw new RuntimeException("failed to create session", exc);
        }

        // Setup the source. Handle both repliable and raw datagrams, on all ports.
        _i2pSource = new I2PSource(_session, I2PSource.Protocol.BOTH);

        // Setup the sink. Always send repliable datagrams.
        if (destination != null && destination.length() > 0) {
            I2PSocketAddress addr = new I2PSocketAddress(destination);
            if (addr.isUnresolved()) {
                // unlike in I2PTunnelClient, we don't defer and retry resolution later
                l.log("Could not resolve " + destination);
                throw new RuntimeException("failed to create session - could not resolve " + destination);
            }
            _i2pSink = new I2PSink(_session, addr.getAddress(), false, addr.getPort());
        } else {
            _i2pSink = new I2PSinkAnywhere(_session, false);
        }
    }

    /**
     * Actually start working on outgoing connections.
     * Classes should override to start UDP side as well.
     *
     * Not specified in I2PTunnelTask but used in both
     * I2PTunnelClientBase and I2PTunnelServer so let's
     * implement it here too.
     */
    public void startRunning() {
        synchronized (startLock) {
            try {
                _session.connect();
            } catch(I2PSessionException exc) {
                throw new RuntimeException("failed to connect session", exc);
            }
            start();
            startLock.notify();
        }
        open = true;
    }

    /**
     * I2PTunnelTask Methods
     *
     * Classes should override to close UDP side as well
     */
    public boolean close(boolean forced) {
        if (!open) return true;
        if (_session != null) {
            try {
                _session.destroySession();
            } catch (I2PSessionException ise) {}
        }
        l.log("Closing client " + toString());
        open = false;
        return true;
    }

    /**
     *  Source Methods
     *
     *  Sets the receiver of the UDP datagrams from I2P
     *  Subclass must call this after constructor
     *  and before start()
     */
    public void setSink(Sink s) {
        _i2pSource.setSink(s);
    }

    /** start the source */
    public void start() {
        _i2pSource.start();
    }

    /**
     *  Sink Methods
     *
     * @param to - ignored if configured for a single destination
     *             (we use the dest specified in the constructor)
     * @since 0.9.53 added fromPort and toPort parameters
     * @throws RuntimeException if session is closed
     */
    public void send(Destination to, int fromPort, int toPort, byte[] data) {
        _i2pSink.send(to, fromPort, toPort, data);
    }
}
