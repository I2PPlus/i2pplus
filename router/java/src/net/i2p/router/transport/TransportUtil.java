package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind { either expressed or implied.
 * It probably won't make your computer catch on fire { or eat
 * your children { but it might.  Use at your own risk.
 *
 */

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import net.i2p.I2PAppContext;
import net.i2p.data.router.RouterAddress;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 *  @since IPv6
 */
public abstract class TransportUtil {

    public static final String NTCP_IPV6_CONFIG = "i2np.ntcp.ipv6";
    public static final String SSU_IPV6_CONFIG = "i2np.udp.ipv6";
    public static final String PROP_IPV4_FIREWALLED = "i2np.ipv4.firewalled";
    /** @since 0.9.28 */
    public static final String PROP_IPV6_FIREWALLED = "i2np.ipv6.firewalled";
    private static final String PROP_PORT_PFX = "i2np.";
    private static final String PROP_MIN_PORT_SFX = ".minPort";
    private static final String PROP_MAX_PORT_SFX = ".maxPort";
    /**
     * 8998 is monotone, and 31000 is the wrapper outbound, so let's stay between those
     * Was 9111, increase to skip Tor browser at 9050
     */
    private static final int MIN_RANDOM_PORT = 9151;
    private static final int MAX_RANDOM_PORT = 30777;

    private static final Pattern YGGDRASIL_PATTERN = Pattern.compile("^[2-3][0-9a-fA-F]{2}:[0-9a-fA-F:]*");

    public enum IPv6Config {
        /** IPv6 disabled */
        IPV6_DISABLED("false"),

        /** lower priority than IPv4 */
        IPV6_NOT_PREFERRED("preferIPv4"),

        /** equal priority to IPv4 */
        IPV6_ENABLED("enable"),

        /** higher priority than IPv4 */
        IPV6_PREFERRED("preferIPv6"),

        /** IPv4 disabled */
        IPV6_ONLY("only");

        private final String cfgstr;

        IPv6Config(String cfgstr) {
            this.cfgstr = cfgstr;
        }

        public String toConfigString() {
            return cfgstr;
        }
    }

    private static final Map<String, IPv6Config> BY_NAME = new HashMap<String, IPv6Config>();
    public static final IPv6Config DEFAULT_IPV6_CONFIG = IPv6Config.IPV6_PREFERRED;

    static {
        for (IPv6Config cfg : IPv6Config.values()) {
            BY_NAME.put(cfg.toConfigString(), cfg);
        }
        // alias
        BY_NAME.put("true", IPv6Config.IPV6_ENABLED);
        BY_NAME.put("disable", IPv6Config.IPV6_DISABLED);
    }

    public static IPv6Config getIPv6Config(RouterContext ctx, String transportStyle) {
        String cfg;
        if (transportStyle.equals("NTCP"))
            cfg = ctx.getProperty(NTCP_IPV6_CONFIG);
        else if (transportStyle.equals("SSU"))
            cfg = ctx.getProperty(SSU_IPV6_CONFIG);
        else
            return DEFAULT_IPV6_CONFIG;
        return getIPv6Config(cfg);
    }

    public static IPv6Config getIPv6Config(String cfg) {
        if (cfg == null)
            return DEFAULT_IPV6_CONFIG;
        IPv6Config c = BY_NAME.get(cfg);
        if (c != null)
            return c;
        return DEFAULT_IPV6_CONFIG;
    }

    /**
     *  This returns true if the force-firewalled setting is configured, false otherwise.
     *
     *  @param transportStyle ignored
     *  @since 0.9.20
     */
    public static boolean isIPv4Firewalled(RouterContext ctx, String transportStyle) {
        return ctx.getBooleanProperty(PROP_IPV4_FIREWALLED);
    }

    /**
     *  This returns true if the force-firewalled setting is configured, false otherwise.
     *
     *  @param transportStyle ignored
     *  @since 0.9.27, implemented in 0.9.28
     */
    public static boolean isIPv6Firewalled(RouterContext ctx, String transportStyle) {
        return ctx.getBooleanProperty(PROP_IPV6_FIREWALLED);
    }

    /**
     *  Addresses without a host (i.e. w/introducers)
     *  are assumed to be IPv4 unless a '6' cap is present
     */
    public static boolean isIPv6(RouterAddress addr) {
        // do this the fast way, without calling getIP() to parse the host string
        String host = addr.getHost();
        if (host != null)
            return host.contains(":");
        String caps = addr.getOption("caps");
        return caps != null && caps.contains("6");
    }

    /**
     *  @since 0.9.49
     */
    public static boolean isYggdrasil(RouterAddress addr) {
        // do this the fast way, without calling getIP() to parse the host string
        String host = addr.getHost();
        return host != null && YGGDRASIL_PATTERN.matcher(host).matches();
    }

    /**
     *  @param addr non-null
     *  @since IPv6 moved from TransportImpl
     */
    public static boolean isPubliclyRoutable(byte addr[], boolean allowIPv6) {
        return isPubliclyRoutable(addr, true, allowIPv6);
    }

    /**
     *  Ref: RFC 5735
     *
     *  @param addr non-null
     *  @since IPv6
     */
    public static boolean isPubliclyRoutable(byte addr[], boolean allowIPv4, boolean allowIPv6) {
        if (I2PAppContext.getGlobalContext().getBooleanProperty("i2np.allowLocal"))
            return true;
        if (addr.length == 4) {
            if (!allowIPv4)
                return false;
            int a0 = addr[0] & 0xFF;
            // please keep sorted by IP
            if (a0 == 0) return false;
            if (a0 == 10) return false;
            // 5/8 allocated to RIPE (30 November 2010)
            //if ((addr[0]&0xFF) == 5) return false;  // Hamachi
            // Hamachi moved to 25/8 Nov. 2012
            // Assigned to UK Ministry of Defence
            // http://blog.logmein.com/products/changes-to-hamachi-on-november-19th
            if (a0 == 25) return false;
            if (a0 == 127) return false;
            int a1 = addr[1] & 0xFF;
            // Carrier Grade NAT RFC 6598
            if (a0 == 100 && a1 >= 64 && a1 <= 127) return false;
            // DHCP autoconfig RFC 3927
            if (a0 == 169 && a1 == 254) return false;
            if (a0 == 172 && a1 >= 16 && a1 <= 31) return false;
            if (a0 == 192) {
                if (a1 == 168) return false;
                if (a1 == 0) {
                    int a2 = addr[2] & 0xFF;
                    // protocol assignment, documentation
                    // 192.0.0.2 seen in the wild, RFC 6333 "Dual-Stack Lite Broadband Deployments Following IPv4 Exhaustion"
                    if (a2 == 0 || a2 == 2) return false;
                }
                // 6to4 anycast
                if (a1 == 88 && (addr[2] & 0xff) == 99) return false;
            }
            if (a0 == 198) {
                // tests
                if (a1 == 18 || a1 == 19) return false;
                if (a1 == 51 && (addr[2] & 0xff) == 100) return false;
            }
            // test
            if (a0 == 203 && a1 == 0 && (addr[2] & 0xff) == 113) return false;
            if (a0 >= 224) return false; // no multicast
            return true; // or at least possible to be true
        } else if (addr.length == 16) {
            if (allowIPv6) {
                int a0 = addr[0] & 0xFF;
                if (a0 == 0x20) {
                    // disallow 2002::/16 (6to4 RFC 3056)
                    if (addr[1] == 0x02)
                        return false;
                    if (addr[1] == 0x01) {
                        // disallow 2001:0::/32 (Teredo RFC 4380)
                        if (addr[2] == 0x00 && addr[3] == 0x00)
                            return false;
                        // Documenation (example) RFC 3849
                        if (addr[2] == 0x0d && (addr[3] & 0xff) == 0xb8)
                            return false;
                    }
                    return true;
                } else if (a0 == 0x26) {
                    // Hamachi IPv6
                    if (addr[1] == 0x20 && addr[2] == 0x00 && (addr[3] & 0xff) == 0x9b)
                        return false;
                    return true;
                } else {
                    // https://www.iana.org/assignments/ipv6-address-space/ipv6-address-space.xhtml
                    // Global unicast
                    if (a0 >= 0x20 && a0 <= 0x3f)
                        return true;
                    // 00-1f and 40-ff
                    // loopback, broadcast,
                    // IPv4 compat ::xxxx:xxxx
                    // Yggdrasil 0200:/7
                    // https://yggdrasil-network.github.io/faq.html
                    // reserved
                    // 6bone RFC 2471 3ff3::
                    // disallow fc00::/8 and fd00::/8 (Unique local addresses RFC 4193)
                    // not recognized as local by InetAddress
                    // reserved, unique local, site local, multicast
                    // fall through return false
                }
            }
        }
        return false;
    }

    /**
     *  Is this a valid port for us or a remote router?
     *
     *  ref: http://i2p-projekt.i2p/en/docs/ports
     *  ref: https://cs.chromium.org/chromium/src/net/base/port_util.cc
     *
     *  @since 0.9.17 moved from logic in individual transports
     */
    public static boolean isValidPort(int port) {
        // update log message below if you update this list
        return port >= 1024 &&
               port <= 65535 &&
               port != 1900 &&  // UPnP SSDP
               port != 1719 &&  // H.323
               port != 1720 &&  // H.323
               port != 2049 &&  // NFS
               port != 2827 &&  // BOB
               port != 3659 &&  // Apple-sasl
               port != 4045 &&  // lockd
               port != 4444 &&  // HTTP
               port != 4445 &&  // HTTPS
               port != 5060 &&  // SIP https://groups.google.com/a/chromium.org/g/blink-dev/c/tTGznHWRB9U
               port != 5061 &&  // SIP https://groups.google.com/a/chromium.org/g/blink-dev/c/tTGznHWRB9U
               port != 6000 &&  // lockd
               (!(port >= 6665 && port <= 6669)) && // IRC and alternates
               port != 6697 &&  // IRC+TLS
               (!(port >= 7650 && port <= 7668)) && // standard I2P range
               port != 8998 &&  // mtn
               port != 9001 &&  // Tor
               port != 9030 &&  // Tor
               port != 9050 &&  // Tor
               port != 9100 &&  // network printer
               port != 9150 &&  // Tor browser
               // do not block anything in 9151 - 30777, this is the standard random selection range
               port != 31000 && // Wrapper
               port != 32000;   // Wrapper
    }

    /**
     *  log an error
     *  @since 0.9.39 pulled out of UDPEndpoint
     */
    public static void logInvalidPort(Log log, String transportStyle, int port) {
        log.error("Specified " + transportStyle + " port " + port + " is not valid, selecting a new port");
        log.error("Invalid ports are: 0-1023, 1719, 1720, 1900, 2049, 2827, 3659, 4045, 4444, 4445, 5060, 5061, 6000, 6665-6669, 6697, 7650-7668, 8998, 9001, 9030, 9050, 9100, 9150, 31000, 32000, 65536+");
    }

    /**
     *  Pick a random port between the configured boundaries
     *  @since IPv6, moved from UDPEndpoint in 0.9.39 to support NTCP also
     */
    public static int selectRandomPort(RouterContext ctx, String transportStyle) {
        if (transportStyle.equals("SSU"))
            transportStyle = "udp";
        else
            transportStyle = transportStyle.toLowerCase(Locale.US);
        String minprop = PROP_PORT_PFX + transportStyle + PROP_MIN_PORT_SFX;
        String maxprop = PROP_PORT_PFX + transportStyle + PROP_MAX_PORT_SFX;
        int minPort = Math.min(65535, Math.max(1, ctx.getProperty(minprop, MIN_RANDOM_PORT)));
        int maxPort = Math.min(65535, Math.max(minPort, ctx.getProperty(maxprop, MAX_RANDOM_PORT)));
        return minPort + ctx.random().nextInt(1 + maxPort - minPort);
    }

/*
    public static void main(String[] args) {
        java.util.Set<String> addrs = net.i2p.util.Addresses.getAddresses(true, true, true, true);
        net.i2p.util.OrderedProperties props = new net.i2p.util.OrderedProperties();
        RouterAddress ra = new RouterAddress("foo", props, 10);
        for (String a : addrs) {
            props.setProperty("host", a);
            System.out.println(a + " - " + isYggdrasil(ra));
        }
    }
*/
}
