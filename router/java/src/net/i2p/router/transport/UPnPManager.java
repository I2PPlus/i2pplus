package net.i2p.router.transport;

/*
 * public domain
 */

import static net.i2p.router.transport.Transport.AddressSource.SOURCE_UPNP;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.Translate;
import org.cybergarage.util.Debug;
import org.freenetproject.DetectedIP;
import org.freenetproject.ForwardPort;
import org.freenetproject.ForwardPortCallback;
import org.freenetproject.ForwardPortStatus;

/**
 * Universal Plug and Play (UPnP) manager for NAT traversal and port forwarding.
 * <p>
 * This class manages UPnP operations to automatically configure
 * port forwarding on UPnP-enabled routers, allowing I2P to
 * work through NAT and firewalls. It bridges between I2P's
 * RouterAddress structures and the external FreeNet UPnP library.
 * <p>
 * <strong>Core Features:</strong>
 * <ul>
 * <li>Automatic UPnP device discovery</li>
 * <li>Port forwarding request and management</li>
 * <li>External IP address detection</li>
 * <li>Gateway device mapping and configuration</li>
 * <li>Lease renewal and monitoring</li>
 * <li>Service advertisement for I2P</li>
 * </ul>
 * <p>
 * <strong>UPnP Protocol Support:</strong>
 * <ul>
 * <li>SSDP (Simple Service Discovery Protocol)</li>
 * <li>HTTP interface for device configuration</li>
 * <li>SOAP-based control messages</li>
 * <li>Event-driven architecture with callbacks</li>
 * </ul>
 * <p>
 * <strong>Integration:</strong>
 * <ul>
 * <li>Bridges I2P RouterAddress to FreeNet structures</li>
 * <li>Works with {@link TransportManager} for address updates</li>
 * <li>Provides callbacks for transport events</li>
 * <li>Handles multiple router discovery protocols</li>
 * </ul>
 * <p>
 * <strong>Configuration:</strong>
 * <ul>
 * <li>Configurable HTTP and SSDP ports</li>
 * <li>Automatic device selection and fallback</li>
 * <li>Error handling and retry logic</li>
 * <li>Logging and monitoring capabilities</li>
 * </ul>
 *
 * @since 0.7.4
 */
class UPnPManager {
    /** Logger for the UPnP forwarding. */
    private final Log _log;
    /** The router context. */
    private final RouterContext _context;
    /** The UPnP protocol implementation. */
    private final UPnP _upnp;
    /** Callback for the UPnP results. */
    private final UPnPCallback _upnpCallback;
    /** Callback for the gateway scans. */
    private final UPnPScannerCallback _scannerCallback;
    /** Callback for the delayed retries. */
    private final DelayedCallback _delayedCallback;
    /** True while UPnP is running. */
    private volatile boolean _isRunning;
    /** True if the config wants UPnP on. */
    private volatile boolean _shouldBeRunning;
    /** When the gateway was last rescanned. */
    private volatile long _lastRescan;
    /** True once the current error was logged. */
    private boolean _errorLogged;
    /** True once the gateway loss was logged. */
    private boolean _disconLogged;
    /** The gateway address detected. */
    private InetAddress _detectedAddress;
    /** The transport manager we forward for. */
    private final TransportManager _manager;
    /** Timer rescanning the gateway. */
    private final SimpleTimer2.TimedEvent _rescanner;
    /**
     * This is the TCP HTTP Event listener
     * We move these so we don't conflict with other users of the same upnp library
     * UPnP also binds to port 1900 UDP for multicast reception - this cannot be changed.
     */
    private static final String PROP_HTTP_PORT = "i2np.upnp.HTTPPort";
    /** Default HTTP port for the callbacks. */
    private static final int DEFAULT_HTTP_PORT = 7652;
    /** This is the UDP SSDP search reply listener. */
    private static final String PROP_SSDP_PORT = "i2np.upnp.SSDPPort";
    /** Default SSDP port for discovery. */
    private static final int DEFAULT_SSDP_PORT = 7653;
    /** Shortest delay between rescans. */
    private static final long RESCAN_MIN_DELAY = 60*1000L;
    /** Rescan delay after a failure. */
    private static final long RESCAN_SHORT_DELAY = 2*60*1000L;
    /** Rescan delay while everything works. */
    private static final long RESCAN_LONG_DELAY = 14*60*1000L;
    /** Port mapping name for TCP. */
    private static final String TCP_PORT_NAME = "TCP";
    /** Port mapping name for UDP. */
    private static final String UDP_PORT_NAME = "UDP";

    /**
     * Set up UPnP discovery and hold the transport manager to notify.
     *
     * @param context router context supplying properties, logging, and the SSDP/HTTP ports
     * @param manager transport manager notified of the port mappings UPnP discovers
     */
    public UPnPManager(RouterContext context, TransportManager manager) {
        _context = context;
        _manager = manager;
        _log = _context.logManager().getLog(UPnPManager.class);
        // this controls what sockets UPnP listens on
        // not clear it makes any practical difference though
        if (!context.getProperty(TransportManager.PROP_ENABLE_UPNP_IPV6, TransportManager.DEFAULT_ENABLE_UPNP_IPV6))
            org.cybergarage.upnp.UPnP.setEnable(org.cybergarage.upnp.UPnP.USE_ONLY_IPV4_ADDR);
        // set up logging in the UPnP package
        Debug.initialize(context);
        int ssdpPort = _context.getProperty(PROP_SSDP_PORT, DEFAULT_SSDP_PORT);
        int httpPort = _context.getProperty(PROP_HTTP_PORT, DEFAULT_HTTP_PORT);
        Set<String> addrs = UPnP.getLocalAddresses();
        List<InetAddress> ias = new ArrayList<>(addrs.size());
        for (String addr : addrs) {
            try {
                InetAddress ia = InetAddress.getByName(addr);
                ias.add(ia);
            } catch (UnknownHostException uhe) { /* ignored */ }
        }
        InetAddress[] binds = ias.toArray(new InetAddress[ias.size()]);
        _upnp = new UPnP(context, ssdpPort, httpPort, binds);
        _upnpCallback = new UPnPCallback();
        _scannerCallback = _context.router().getUPnPScannerCallback();
        _delayedCallback = (_scannerCallback != null) ? new DelayedCallback() : null;
        _rescanner = new Rescanner();
    }

    /**
     * Blocking, may take a while.
     * May be called even if already running.
     */
    public synchronized void start() {
        _shouldBeRunning = true;
        if (!_isRunning && Addresses.isConnected()) {
            if (_log.shouldDebug())
                _log.debug("UPnP Start");
            long b = _context.clock().now();
            try {
                // We set these here every time, because ControlPoint auto-decrements on failure,
                // and will eventually hit 1024 and then negative
                _upnp.setHTTPPort(_context.getProperty(PROP_HTTP_PORT, DEFAULT_HTTP_PORT));
                _upnp.setSSDPPort(_context.getProperty(PROP_SSDP_PORT, DEFAULT_SSDP_PORT));
                if (_scannerCallback != null) {
                    _scannerCallback.beforeScan();
                }
                _isRunning = _upnp.runPlugin();
                if (_scannerCallback != null) {
                    if (_isRunning)
                        _delayedCallback.reschedule();
                    else
                        _scannerCallback.afterScan();
                }
                if (_log.shouldInfo())
                    _log.info("UPnP plug-in scan finished -> Took: " +
                              DataHelper.formatDuration(_context.clock().now() - b));
            } catch (RuntimeException e) {
                // NPE in UPnP (ticket #728), can't let it bring us down
                if (!_errorLogged) {
                    _log.error("UPnP error, please report", e);
                    _errorLogged = true;
                }
                if (_scannerCallback != null) {
                    _scannerCallback.afterScan();
                }
            }
        }
        if (_isRunning) {
            _rescanner.schedule(RESCAN_LONG_DELAY);
        } else {
            _rescanner.schedule(RESCAN_SHORT_DELAY);
            // Do we have a non-loopback, non-broadcast address?
            // If not, that's why it failed (HTTPServer won't start)
            if (!Addresses.isConnected()) {
                if (!_disconLogged) {
                    _log.logAlways(Log.WARN, "UPnP start failed - no network connection?");
                    _disconLogged = true;
                }
            } else {
                _log.error("UPnP start failed - port conflict?");
            }
        }
    }

    /**
     * Blocking, may take a while, up to 20 seconds.
     */
    public synchronized void stop() {
        if (_log.shouldDebug())
            _log.debug("UPnP Stop");
        _shouldBeRunning = false;
        _rescanner.cancel();
        if (_isRunning)
            _upnp.terminate();
        _isRunning = false;
        _detectedAddress = null;
        if (_log.shouldDebug())
            _log.debug("UPnP Stop Done");
    }

    /**
     * Call when IP or network connectivity might have changed.
     * Starts UPnP if previous start failed, else starts a search.
     * Must have called {@link #start()} first, and not called stop().
     * <p>
     * Should be fast. This only starts the search, the responses
     * will come in over the MX time (3 seconds).
     *
     * @return true if a rescan was actually fired off
     * @since 0.9.18
     */
    public synchronized boolean rescan() {
        if (!_shouldBeRunning)
            return false;
        if (_context.router().gracefulShutdownInProgress())
            return false;
        long now = System.currentTimeMillis();
        if (_lastRescan + RESCAN_MIN_DELAY > now)
            return false;
        _lastRescan = now;
        if (_isRunning) {
            if (_scannerCallback != null)
                _scannerCallback.beforeScan();
            if (_log.shouldDebug())
                _log.debug("UPnP Rescan", new Exception());
            // See also:
            // Adaptive Jitter Control for UPnP M-Search
            // Kevin Mills and Christopher Dabrowski
            _upnp.search();
            if (_scannerCallback != null)
                _delayedCallback.reschedule();
        } else {
            start();
        }
        return true;
    }

    /**
     * Initiate a UPnP search.
     *
     * @since 0.9.18
     */
    private class Rescanner extends SimpleTimer2.TimedEvent {

        /** Caller must schedule(). */
        public Rescanner() {
            super(_context.simpleTimer2());
        }

        /**
         * Rescan for UPnP devices and reschedule.
         */
        public void timeReached() {
            if (_shouldBeRunning) {
                rescan();
                reschedule(_isRunning ? RESCAN_LONG_DELAY : RESCAN_SHORT_DELAY);
            }
        }
    }

    /**
     * Delayed Callback.
     *
     * @since 0.9.41
     */
    private class DelayedCallback extends SimpleTimer2.TimedEvent {

        /** Caller must reschedule(). */
        public DelayedCallback() {
            super(_context.simpleTimer2());
        }

        /**
         * Run the post-scan callback.
         */
        public void timeReached() {
             _scannerCallback.afterScan();
        }

        /**
         * Pushes out.
         * We do it this way because we may have two scans running concurrently,
         * we only want to call afterScan() once.
         */
        void reschedule() {
            // false == use latest time
            reschedule((_upnp.getSearchMx() * 1000L) + 500, false);
        }
    }

    /**
     * Call when the ports might have changed
     * The transports can call this pretty quickly at startup,
     * which can have multiple UPnP threads running at once, but
     * that should be ok.
     *
     * @param ports transport ports to expose, one entry per style such as SSU or NTCP
     */
    public void update(Set<TransportManager.Port> ports) {
        if (_log.shouldDebug())
            _log.debug("UPnP Update with " + ports.size() + " ports");

        if (!_isRunning)
                return;

        Set<ForwardPort> forwards = new HashSet<>(ports.size());
        for (TransportManager.Port entry : ports) {
            String style = entry.style;
            int port = entry.port;
            int protocol;
            String name;
            if ("SSU".equals(style)) {
                protocol = ForwardPort.PROTOCOL_UDP_IPV4;
                name = UDP_PORT_NAME;
            } else if ("NTCP".equals(style)) {
                protocol = ForwardPort.PROTOCOL_TCP_IPV4;
                name = TCP_PORT_NAME;
            } else {
                continue;
            }
            if (_log.shouldDebug())
                _log.debug("Adding: " + style + " " + port);
            ForwardPort fp;
            if (entry.isIPv6)
                fp = new UPnP.IPv6ForwardPort(name, protocol, port, entry.ip);
            else
                fp = new ForwardPort(name, false, protocol, port);
            forwards.add(fp);
        }
        // non-blocking
        _upnp.onChangePublicPorts(forwards, _upnpCallback);
    }

    /**
     * This is the callback from UPnP.
     * It calls the TransportManager callbacks.
     */
    private class UPnPCallback implements ForwardPortCallback {

        /**
         * Callback that relays UPnP port-forward status changes to the transport.
         */
        private UPnPCallback() {}

        /** Called to indicate status on one or more forwarded ports. */
        public void portForwardStatus(Map<ForwardPort,ForwardPortStatus> statuses) {
            if (_log.shouldDebug())
                 _log.debug("UPnP Callback: with " + statuses.size() + " statuses");
            // Let's not have two of these running at once.
            // Deadlock reported in ticket #1699
            // and the locking isn't foolproof in UDPTransport.
            // UPnP runs the callbacks in a thread, so we can block.
            // There is only one UPnPCallback, so lock on this
            synchronized(this) {
                locked_PFS(statuses);
            }
        }

        /**
         * Logs the port forwarding results, lock held.
         *
         * @param statuses the results, by port mapping
         */
        private void locked_PFS(Map<ForwardPort,ForwardPortStatus> statuses) {
            byte[] ipaddr = null;
            DetectedIP[] ips = _upnp.getAddress();
            if (ips != null && ips.length > 0) {
                for (DetectedIP ip : ips) {
                    // store the first public one and tell the transport manager if it changed
                    // Note that getAddress() will actually return a max of one address.
                    if (TransportUtil.isPubliclyRoutable(ip.publicAddress.getAddress(), false)) {
                        if (_log.shouldDebug())
                            _log.debug("External address: " + ip.publicAddress + " type: " + ip.natType);
                        if (!ip.publicAddress.equals(_detectedAddress)) {
                            _detectedAddress = ip.publicAddress;
                            // deadlock path 1
                            _manager.externalAddressReceived(SOURCE_UPNP, _detectedAddress.getAddress(), 0);
                        }
                        ipaddr = ip.publicAddress.getAddress();
                        break;
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("Unusable external address: " + ip.publicAddress + " type: " + ip.natType);
                    }
                }
            } else {
                if (_log.shouldDebug())
                    _log.debug("No external address returned");
            }

            if (statuses.isEmpty()) {
                if (_log.shouldWarn())
                    _log.warn("No statuses returned");
                return;
            }

            for (Map.Entry<ForwardPort, ForwardPortStatus> entry : statuses.entrySet()) {
                ForwardPort fp = entry.getKey();
                ForwardPortStatus fps = entry.getValue();
                if (_log.shouldDebug())
                    _log.debug("FPS: " + fp.name + ' ' + fp.protocol + ' ' + fp.portNumber +
                               (fp.isIP6 ? " IPv6" : " IPv4") +
                               " status: " + fps.status + " reason: " + fps.reasonString + " ext port: " + fps.externalPort);
                String style;
                if (fp.protocol == ForwardPort.PROTOCOL_UDP_IPV4) {
                    style = "SSU";
                } else if (fp.protocol == ForwardPort.PROTOCOL_TCP_IPV4) {
                    style = "NTCP";
                } else {
                    if (_log.shouldWarn())
                        _log.debug("Unknown protocol " + fp.protocol);
                    continue;
                }
                boolean success = fps.status >= ForwardPortStatus.MAYBE_SUCCESS;
                byte[] fwdip;
                if (fp.isIP6) {
                    UPnP.IPv6ForwardPort v6fp = (UPnP.IPv6ForwardPort) fp;
                    String sip = v6fp.getIP();
                    fwdip = Addresses.getIP(sip);
                    if (fwdip == null)
                        continue;
                } else {
                    fwdip = ipaddr;
                }
                // deadlock path 2
                _manager.forwardPortStatus(style, fwdip, fp.portNumber, fps.externalPort, success, fps.reasonString);
            }
        }
    }

    /**
     * Warning - blocking, very slow, queries the active router,
     * will take many seconds if it has vanished.
     *
     * @return an HTML fragment describing the mappings UPnP has discovered
     */
    public String renderStatusHTML() {
        if (!_isRunning)
            return "<h3 id=upnp>" + _t("UPnP is not enabled") + "</h3>\n";
        return _upnp.renderStatusHTML();
    }

    /** Bundle holding the translated console strings. */
    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /**
     * Look up a translation key in the router web message bundle.
     *
     * @param s the translation key
     * @return the translated string
     */
    private final String _t(String s) {
        return Translate.getString(s, _context, BUNDLE_NAME);
    }

}
