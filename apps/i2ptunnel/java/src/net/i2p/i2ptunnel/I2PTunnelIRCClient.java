package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.irc.DCCClientManager;
import net.i2p.i2ptunnel.irc.DCCHelper;
import net.i2p.i2ptunnel.irc.I2PTunnelDCCServer;
import net.i2p.i2ptunnel.irc.IrcInboundFilter;
import net.i2p.i2ptunnel.irc.IrcOutboundFilter;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.PortMapper;

/**
 * IRC client tunnel with DCC support and filtering.
 * <p>
 * TODO: Consider extending I2PTunnelClient to reduce code duplication.
 */
public class I2PTunnelIRCClient extends I2PTunnelClientBase {

    /** list of Destination objects that we point at */
    private final List<I2PSocketAddress> _addrs;
    // application should ping timeout before this
    private static final long DEFAULT_READ_TIMEOUT = 10 * (long) 60 * 1000;
    protected long readTimeout = DEFAULT_READ_TIMEOUT;
    private final boolean _dccEnabled;
    private I2PTunnelDCCServer _DCCServer;
    private DCCClientManager _DCCClientManager;

    /**
     *  @since 0.8.9
     */
    public static final String PROP_DCC = "i2ptunnel.ircclient.enableDCC";

    /**
     *  As of 0.9.20 this is fast, and does NOT connect the manager to the router,
     *  or open the local socket. You MUST call startRunning() for that.
     *
     * @param destinations peers we target, comma- or space-separated. Since 0.9.9, each dest may be appended with :port
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelIRCClient(
                              int localPort,
                              String destinations,
                              Logging l,
                              boolean ownDest,
                              EventDispatcher notifyThis,
                              I2PTunnel tunnel, String pkf) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis,
              "IRC Client on " + tunnel.listenHost + ':' + localPort, tunnel, pkf);
        // force connect delay and bulk profile
        Properties opts = tunnel.getClientOptions();
        if (opts.getProperty("i2p.streaming.connectDelay") == null)
            opts.setProperty("i2p.streaming.connectDelay", "150");
        opts.remove("i2p.streaming.maxWindowSize");
        if (opts.getProperty("i2cp.leaseSetEncType") == null)
            opts.setProperty("i2cp.leaseSetEncType", "4,0");

        _addrs = new ArrayList<>(4);
        buildAddresses(destinations);

        if (_addrs.isEmpty()) {
            l.log("[IRC Client] No target destinations found");
            notifyEvent("openClientResult", "error");
            // Nothing is listening for the above event, so it's useless
            // Maybe figure out where to put a waitEventValue("openClientResult") ??
            // In the meantime, let's do this the easy way

            // Don't close() here, because it does a removeSession() and then
            // TunnelController can't acquire() it to release() it.
            // Unfortunately, super() built the whole tunnel before we get here.
            throw new IllegalArgumentException("No valid target destinations found");
        }

        setName("IRCProxy");

        _dccEnabled = Boolean.parseBoolean(tunnel.getClientOptions().getProperty(PROP_DCC));
        // TODO add some prudent tunnel options (or is it too late?)

        notifyEvent("openIRCClientResult", "ok");
    }

    /** @since 0.9.9 moved from constructor */
    private void buildAddresses(String destinations) {
        if (destinations == null)
            return;
        StringTokenizer tok = new StringTokenizer(destinations, ", ");
        synchronized(_addrs) {
            _addrs.clear();
            while (tok.hasMoreTokens()) {
                String destination = tok.nextToken();
                try {
                    // Try to resolve here but only log if it doesn't.
                    // Note that b32 _addrs will often not be resolvable at instantiation time.
                    // We will try again to resolve in clientConnectionRun()
                    I2PSocketAddress addr = new I2PSocketAddress(destination);
                    _addrs.add(addr);
                    if (addr.isUnresolved()) {
                        String name = addr.getHostName();
                        if (name.length() == 60 && name.endsWith(".b32.i2p"))
                            l.log("[IRC Client] Warning - Could not resolve " + name +
                                  ", perhaps it is not up, will retry when connecting.");
                        else
                            l.log("[IRC Client] Warning - Could not resolve " + name +
                                  ", you must add it to your addressbook for it to work.");
                    }
                } catch (IllegalArgumentException iae) {
                     l.log("✖ [IRC Client] Bad destination " + destination + " - " + iae);
                }
            }
        }
    }

    /**
     * clientConnectionRun.
     */
    @Override
    protected void clientConnectionRun(Socket s) {
        if (_log.shouldInfo())
            _log.info("[IRC Client] New connection - local address is: " + s.getLocalAddress() +
                      " from: " + s.getInetAddress());
        I2PSocket i2ps = null;
        I2PSocketAddress addr = pickDestination();
        int failures = 0;
        try {
            if (addr == null)
                throw new UnknownHostException("No valid destination configured");
            int port = addr.getPort();
            // Retry on transient connection failures (tunnel build failure,
            // temporary no-routes). NoRouteToHostException is thrown when the
            // destination is unreachable or tunnels fail to build, which on a
            // slow or hidden service is often transient. Give the pool time to
            // recover between attempts with exponential backoff, and fail fast
            // when the client outbound pool provably has no tunnels and none
            // are being built - further retries cannot succeed.
            while (true) {
                try {
                    // Re-resolve on each attempt so a b32 destination that could
                    // not be resolved earlier is picked up once its LeaseSet
                    // becomes available.
                    Destination clientDest = addr.getAddress();
                    if (clientDest == null)
                        throw new UnknownHostException("Could not resolve " + addr.getHostName());
                    i2ps = createI2PSocket(clientDest, port);
                    break;
                } catch (IOException ioe) {
                    failures++;
                    if (!shouldRetry(failures, ioe))
                        throw ioe;
                    if (_log.shouldInfo())
                        _log.info("[IRC Client] Connect attempt " + (failures + 1) + '/' + IRC_CONNECT_MAX_ATTEMPTS +
                                  " failed (" + ioe.getMessage() + "), retrying");
                    if (!retryDelay(failures)) // interrupted by tunnel shutdown
                        throw ioe;
                } catch (I2PException ie) {
                    failures++;
                    if (!shouldRetry(failures, ie))
                        throw ie;
                    if (_log.shouldInfo())
                        _log.info("[IRC Client] Connect attempt " + (failures + 1) + '/' + IRC_CONNECT_MAX_ATTEMPTS +
                                  " failed (" + ie.getMessage() + "), retrying");
                    if (!retryDelay(failures)) // interrupted by tunnel shutdown
                        throw ie;
                }
            }
            i2ps.setReadTimeout(readTimeout);
            StringBuffer expectedPong = new StringBuffer();
            DCCHelper dcc = _dccEnabled ? new DCC(s.getLocalAddress().getAddress()) : null;
            Thread in = new I2PAppThread(new IrcInboundFilter(s,i2ps, expectedPong, _log, dcc), "IRCCln." + _clientId, true);
            in.start();
            Runnable out = new IrcOutboundFilter(s,i2ps, expectedPong, _log, dcc);
            // we are called from an unlimited thread pool, so run inline
            out.run();
        } catch (IOException ex) {
            // generally NoRouteToHostException
            if (_log.shouldWarn())
                _log.warn("[IRC Client] Error connecting: " + ex.getMessage() + " after " + failures + " attempt(s)");
            try {
                // Send a response so the user doesn't just see a disconnect
                // and blame his router or the network.
                String name = addr != null ? addr.getHostName() : "undefined";
                String msg = ":" + name + " 499 you :" + ex + "\r\n";
                s.getOutputStream().write(DataHelper.getUTF8(msg));
            } catch (IOException ioe) { /* ignored */ }
        } catch (I2PException ex) {
            if (_log.shouldWarn())
                _log.warn("[IRC Client] Error connecting: " + ex.getMessage() + " after " + failures + " attempt(s)");
            try {
                // Send a response so the user doesn't just see a disconnect
                // and blame his router or the network.
                String name = addr != null ? addr.getHostName() : "undefined";
                String msg = ":" + name + " 499 you :" + ex + "\r\n";
                s.getOutputStream().write(DataHelper.getUTF8(msg));
            } catch (IOException ioe) { /* ignored */ }
        } finally {
            // only because we are running it inline
            closeSocket(s);
            if (i2ps != null) {
                try { i2ps.close(); } catch (IOException ioe) { /* ignored */ }
                synchronized (sockLock) {
                    mySockets.remove(i2ps);
                }
            }
        }

    }

    /**
     *  Maximum number of I2P connect attempts per client connection: the initial
     *  try plus three exponential-backoff retries. Once the budget is exhausted
     *  the tunnel gives up and sends the 499 error reply.
     *
     *  @since 0.9.71+
     */
    static final int IRC_CONNECT_MAX_ATTEMPTS = 4;

    /** Backoff floor (ms) for I2P connect retries, doubling per attempt. @since 0.9.71+ */
    static final long IRC_CONNECT_RETRY_BASE_DELAY = 1000;

    /**
     *  Exponential backoff delay (ms) to sleep before the given connect retry.
     *  The first retry waits {@link #IRC_CONNECT_RETRY_BASE_DELAY} (1s), doubling
     *  per attempt up to a hard cap of 8s. During a tunnel-pool stall the cap
     *  lets the pool recover instead of hammering it, and the loop still fails
     *  fast once {@link #poolState()} reports a provably dead pool.
     *  Pure decision - no context access, safe for unit tests.
     *
     *  @param attempt the 1-based connect failure count (how many failures so far)
     *  @return delay in ms: 0 for attempt &lt;= 0, else 1000 &lt;&lt; (attempt-1) bounded to 8000
     *  @since 0.9.71+
     */
    static long getConnectRetryDelayMs(int attempt) {
        if (attempt <= 0) {return 0;}
        return Math.min(8 * IRC_CONNECT_RETRY_BASE_DELAY,
                        IRC_CONNECT_RETRY_BASE_DELAY << Math.min(attempt - 1, 3));
    }

    /**
     *  Whether a connect attempt should be retried after {@code failures} failed
     *  attempts. Three independent conditions must all hold:
     *  <ul>
     *  <li>the attempt budget is not exhausted ({@code failures < maxAttempts}),</li>
     *  <li>the client outbound pool is not provably dead ({@code poolState != -1}; a
     *      {@code -2} "unknown" result from standalone clients does NOT fail fast,
     *      matching {@code shouldStopEmptyReconnect()}),</li>
     *  <li>the failure is retryable per {@link #isRetryableConnectFailure(Throwable)}.</li>
     *  </ul>
     *  Pure decision - no context access, safe for unit tests.
     *
     *  @param failures the 1-based number of failed attempts so far
     *  @param maxAttempts the total attempt budget, 1-based
     *  @param poolState the {@link #poolState()} value from the last failed attempt
     *  @param last the throwable from the last failed attempt
     *  @return true if another attempt should be scheduled
     *  @since 0.9.71+
     */
    static boolean shouldRetryConnect(int failures, int maxAttempts, int poolState, Throwable last) {
        if (failures >= maxAttempts) {return false;}
        if (poolState == -1) {return false;}
        return isRetryableConnectFailure(last);
    }

    /**
     *  Whether a connect failure is transient enough to warrant another attempt.
     *  <ul>
     *  <li>Not retryable: {@code ConnectException} (an explicit refusal is a hard
     *      answer from the peer that a new attempt cannot change), an
     *      {@code InterruptedIOException} (local cancellation - the tunnel is
     *      closing), or {@code null}.</li>
     *  <li>Retryable: everything else - {@code NoRouteToHostException} connect
     *      timeouts, {@code UnknownHostException} for a b32 destination whose
     *      LeaseSet may become available by the next attempt, {@code I2PException}
     *      tunnel build failures (incl. {@code TooManyStreamsException}), and
     *      generic I/O errors.</li>
     *  </ul>
     *  Pure decision - no context access, safe for unit tests.
     *
     *  @param t the throwable from the failed connect
     *  @return true if a retry is worthwhile
     *  @since 0.9.71+
     */
    static boolean isRetryableConnectFailure(Throwable t) {
        if (t == null) {return false;}
        if (t instanceof ConnectException) {return false;}
        if (t instanceof InterruptedIOException) {return false;}
        return true;
    }

    /**
     *  Instance gate for the clientConnectionRun retry loop: applies the static
     *  budget, dead-pool and failure-classification rules with the current pool
     *  state ({@link #poolState()}).
     *
     *  @param failures the 1-based number of failed attempts so far
     *  @param t the throwable from the last failed attempt
     *  @return true if another connect attempt should be scheduled
     *  @since 0.9.71+
     */
    private boolean shouldRetry(int failures, Throwable t) {
        return shouldRetryConnect(failures, IRC_CONNECT_MAX_ATTEMPTS, poolState(), t);
    }

    /**
     *  Sleep through the exponential backoff preceding a retry, failing fast when
     *  the tunnel is being shut down mid-delay.
     *
     *  @param failures the 1-based number of failed attempts so far
     *  @return true if the delay elapsed, false if the thread was interrupted
     *  @since 0.9.71+
     */
    private boolean retryDelay(int failures) {
        try {
            Thread.sleep(getConnectRetryDelayMs(failures));
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private final I2PSocketAddress pickDestination() {
        synchronized(_addrs) {
            int size = _addrs.size();
            if (size <= 0) {
                if (_log.shouldError())
                    _log.error("[IRC Client] No client targets?!");
                return null;
            }
            if (size == 1) // skip the rand in the most common case
                return _addrs.get(0);
            int index = _context.random().nextInt(size);
            return _addrs.get(index);
        }
    }

    /**
     *  Update the dests then call super.
     *
     *  @since 0.9.9
     */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel)
            return;
        Properties props = tunnel.getClientOptions();
        // see TunnelController.setSessionOptions()
        String targets = props.getProperty("targetDestination");
        buildAddresses(targets);
        super.optionsUpdated(tunnel);
    }

    /**
     * startRunning.
     */
    @Override
    public void startRunning() {
        super.startRunning();
        if (open)
            _context.portMapper().register(PortMapper.SVC_IRC, getTunnel().listenHost, getLocalPort());
    }

    /**
     * close.
     */
    @Override
    public boolean close(boolean forced) {
        int reg = _context.portMapper().getPort(PortMapper.SVC_IRC);
        if (reg == getLocalPort())
            _context.portMapper().unregister(PortMapper.SVC_IRC);
        synchronized(this) {
            if (_DCCServer != null) {
                _DCCServer.close(forced);
                _DCCServer = null;
            }
            if (_DCCClientManager != null) {
                _DCCClientManager.close(forced);
                _DCCClientManager = null;
            }
        }
        return super.close(forced);
    }

    //
    //  Start of the DCCHelper interface
    //

  private class DCC implements DCCHelper {

    private final byte[] _localAddr;

    /**
     *  @param local Our IP address, from the IRC client's perspective
     */
    public DCC(byte[] local) {
        if (local.length == 4)
            _localAddr = local;
        else
            _localAddr = new byte[] {127, 0, 0, 1};
    }

    /**
     * @return whether enabled
     */
    @Override
    public boolean isEnabled() {
        return _dccEnabled;
    }

    /**
     * @return the b32 hostname
     */
    public String getB32Hostname() {
        return sockMgr.getSession().getMyDestination().toBase32();
    }

    /**
     * @return the local address
     */
    public byte[] getLocalAddress() {
        return _localAddr;
    }

    /**
     * newOutgoing.
     */
    @Override
    public int newOutgoing(byte[] ip, int port, String type) {
        I2PTunnelDCCServer server;
        synchronized(this) {
            if (_DCCServer == null) {
                if (_log.shouldInfo())
                    _log.info("[IRC Client] Starting DCC Server...");
                _DCCServer = new I2PTunnelDCCServer(sockMgr, l, I2PTunnelIRCClient.this, getTunnel());
                // TODO add some prudent tunnel options (or is it too late?)
                _DCCServer.startRunning();
            }
            server = _DCCServer;
        }
        int rv = server.newOutgoing(ip, port, type);
        if (_log.shouldInfo())
            _log.info("[IRC Client] New outgoing " + type + ' ' + port + " returns " + rv);
        return rv;
    }

    /**
     * newIncoming.
     */
    @Override
    public int newIncoming(String b32, int port, String type) {
        DCCClientManager tracker;
        synchronized(this) {
            if (_DCCClientManager == null) {
                if (_log.shouldInfo())
                    _log.info("[IRC Client] Starting DCC Client...");
                _DCCClientManager = new DCCClientManager(sockMgr, l, I2PTunnelIRCClient.this, getTunnel());
            }
            tracker = _DCCClientManager;
        }
        // The tracker starts our client
        int rv = tracker.newIncoming(b32, port, type);
        if (_log.shouldInfo())
            _log.info("[IRC Client] New incoming " + type + ' ' + b32 + ' ' + port + " returns " + rv);
        return rv;
    }

    /**
     * resumeOutgoing.
     */
    @Override
    public int resumeOutgoing(int port) {
        DCCClientManager tracker = _DCCClientManager;
        if (tracker != null)
            return tracker.resumeOutgoing(port);
        return -1;
    }

    public int resumeIncoming(int port) {
        I2PTunnelDCCServer server = _DCCServer;
        if (server != null)
            return server.resumeIncoming(port);
        return -1;
    }

    public int acceptOutgoing(int port) {
        I2PTunnelDCCServer server = _DCCServer;
        if (server != null)
            return server.acceptOutgoing(port);
        return -1;
    }

    public int acceptIncoming(int port) {
        DCCClientManager tracker = _DCCClientManager;
        if (tracker != null)
            return tracker.acceptIncoming(port);
        return -1;
    }
  }
}
