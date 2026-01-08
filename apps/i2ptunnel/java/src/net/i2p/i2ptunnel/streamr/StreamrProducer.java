package net.i2p.i2ptunnel.streamr;

import java.io.File;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.i2ptunnel.udpTunnel.I2PTunnelUDPServerBase;
import net.i2p.util.EventDispatcher;

/**
 * Streamr producer that acts as an I2P server and UDP client,
 * receiving UDP data on a configured port and forwarding it through I2P
 *
 * @author welterde
 * @author zzz modded for I2PTunnel
 */
public class StreamrProducer extends I2PTunnelUDPServerBase {

    /**
     * Creates a Streamr producer that receives UDP data and forwards it through I2P.
     *
     * @param port local UDP port to listen on
     * @param privkey I2P private key file for the destination
     * @param privkeyname name of the private key
     * @param l logging facility
     * @param notifyThis event dispatcher for notifications
     * @param tunnel the tunnel context
     * @since 0.9.53
     */
    public StreamrProducer(int port,
                           File privkey, String privkeyname, Logging l,
                           EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(privkey, privkeyname, l, notifyThis, tunnel);

        // The broadcaster
        this.multi = new MultiSource();
        this.multi.setSink(this);

        // The listener
        this.subscriber = new Subscriber(this.multi);
        setSink(this.subscriber);

        // now start udp-server
        this.server = new UDPSource(port);
        this.server.setSink(this.multi);
    }

    /**
     *  Starts the UDP server and begins forwarding data.
     *  @since 0.9.53
     */
    @Override
    public final void startRunning() {
        super.startRunning();
        this.server.start();
        l.log("Streamr server ready");
    }

    /**
     *  Stops the server and releases resources.
     *  @param forced if true, forces immediate close
     *  @return true if closed successfully
     *  @since 0.9.53
     */
    @Override
    public boolean close(boolean forced) {
        this.server.stop();
        this.multi.stop();
        return super.close(forced);
    }

    private final MultiSource multi;
    private final UDPSource server;
    private final Sink subscriber;
}
