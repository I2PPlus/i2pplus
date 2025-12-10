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

    @Override
    public final void startRunning() {
        super.startRunning();
        this.server.start();
        l.log("Streamr server ready");
    }

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
