/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

/**
 * Bidirectional HTTP server tunnel combining server and client functionality.
 * <p>
 * Extends I2PTunnelHTTPServer to provide both inbound and outbound
 * HTTP connectivity through I2P. Creates a server tunnel for receiving
 * requests and a client tunnel (proxy) for outbound requests,
 * enabling bidirectional HTTP communication through a single I2P destination.
 * <p>
 * Useful for applications requiring both server and client HTTP capabilities
 * through I2P, such as web applications making external API calls.
 */

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;

import net.i2p.util.EventDispatcher;

public class I2PTunnelHTTPBidirServer extends I2PTunnelHTTPServer {

    public I2PTunnelHTTPBidirServer(InetAddress host, int port, int proxyport, String privData, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, spoofHost, l, notifyThis, tunnel);
        finishSetupI2PTunnelHTTPBidirServer(l, proxyport);
    }

    public I2PTunnelHTTPBidirServer(InetAddress host, int port, int proxyport, File privkey, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privkey, privkeyname, spoofHost, l, notifyThis, tunnel);
        finishSetupI2PTunnelHTTPBidirServer(l, proxyport);
    }

    public I2PTunnelHTTPBidirServer(InetAddress host, int port, int proxyport, InputStream privData, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, privkeyname, spoofHost, l, notifyThis, tunnel);
        finishSetupI2PTunnelHTTPBidirServer(l, proxyport);
    }

    private void finishSetupI2PTunnelHTTPBidirServer(Logging l, int proxyport) {

        localPort = proxyport;
        bidir = true;

        /* start the httpclient */
        I2PTunnelClientBase client = new I2PTunnelHTTPBidirProxy(localPort, l, sockMgr, getTunnel(), getEventDispatcher(), __serverId);
        client.startRunning();
        task = client;
        sockMgr.setName("Server"); // TO-DO: Need to change this to "Bidir"!
        getTunnel().addSession(sockMgr.getSession());
        l.log("Ready!");
        notifyEvent("openServerResult", "ok");
    }
}

