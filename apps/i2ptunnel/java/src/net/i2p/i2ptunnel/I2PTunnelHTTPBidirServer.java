/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import net.i2p.util.EventDispatcher;

 /**
  * Bidirectional HTTP server tunnel combining server and client functionality.
  * <p>
  * Extends I2PTunnelHTTPServer to provide both inbound and outbound
  * HTTP connectivity through I2P. Creates a server tunnel for receiving
  * requests and a client tunnel (proxy) for outbound requests,
  * enabling bidirectional HTTP communication through a single I2P destination.
  * </p>
  * <p>
  * This class manages two tunnel components:
  * <ul>
  *   <li><b>Server:</b> Incoming HTTP connections via I2PServerSocket</li>
  *   <li><b>Client:</b> I2PTunnelHTTPBidirProxy for outbound HTTP requests</li>
  * </ul>
  * </p>
  * <p>
  * Useful for applications requiring both server and client HTTP capabilities
  * through I2P, such as web applications making external API calls.
  * </p>
   *
   * @see I2PTunnelHTTPServer
   * @see I2PTunnelHTTPBidirProxy
   */
  public class I2PTunnelHTTPBidirServer extends I2PTunnelHTTPServer {

     /**
      * Creates a bidirectional HTTP server tunnel.
      * <p>
      * The server listens for incoming HTTP connections on the specified port,
      * while the client proxy handles outbound requests through a separate port.
      * </p>
      *
      * @param host the local InetAddress to bind the server to (usually null/0.0.0.0)
      * @param port the local port for incoming HTTP server connections
      * @param proxyport the local port for the outbound HTTP proxy
      * @param privData Base64-encoded private key data for the I2P destination
      * @param spoofHost the spoofed hostname for the server destination
      * @param l the Logging instance for status messages
      * @param notifyThis the EventDispatcher for tunnel events
      * @param tunnel the parent I2PTunnel instance
      * @throws IllegalArgumentException if the I2CP configuration is invalid
      */
     public I2PTunnelHTTPBidirServer(InetAddress host, int port, int proxyport, String privData, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
         super(host, port, privData, spoofHost, l, notifyThis, tunnel);
         finishSetupI2PTunnelHTTPBidirServer(l, proxyport);
     }

     /**
      * Creates a bidirectional HTTP server tunnel with a private key file.
      *
      * @param host the local InetAddress to bind the server to
      * @param port the local port for incoming HTTP server connections
      * @param proxyport the local port for the outbound HTTP proxy
      * @param privkey File containing Base64-encoded private key data
      * @param privkeyname the name of the private key file (for logging)
      * @param spoofHost the spoofed hostname for the server destination
      * @param l the Logging instance for status messages
      * @param notifyThis the EventDispatcher for tunnel events
      * @param tunnel the parent I2PTunnel instance
      * @throws IllegalArgumentException if the key file cannot be read
      */
     public I2PTunnelHTTPBidirServer(InetAddress host, int port, int proxyport, File privkey, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
         super(host, port, privkey, privkeyname, spoofHost, l, notifyThis, tunnel);
         finishSetupI2PTunnelHTTPBidirServer(l, proxyport);
     }

     /**
      * Creates a bidirectional HTTP server tunnel with an input stream for the private key.
      *
      * @param host the local InetAddress to bind the server to
      * @param port the local port for incoming HTTP server connections
      * @param proxyport the local port for the outbound HTTP proxy
      * @param privData InputStream containing Base64-encoded private key data
      * @param privkeyname the name of the private key (for logging)
      * @param spoofHost the spoofed hostname for the server destination
      * @param l the Logging instance for status messages
      * @param notifyThis the EventDispatcher for tunnel events
      * @param tunnel the parent I2PTunnel instance
      * @throws IllegalArgumentException if the key data is invalid
      */
     public I2PTunnelHTTPBidirServer(InetAddress host, int port, int proxyport, InputStream privData, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
         super(host, port, privData, privkeyname, spoofHost, l, notifyThis, tunnel);
         finishSetupI2PTunnelHTTPBidirServer(l, proxyport);
     }

     /**
      * Completes the bidirectional server setup by initializing the client proxy.
      * <p>
      * This method is called after the parent server tunnel is initialized.
      * It creates the outbound HTTP proxy component and starts both tunnels.
      * </p>
      *
      * @param l the Logging instance
      * @param proxyport the port for the outbound HTTP proxy
      */
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

