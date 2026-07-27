package edu.internet2.ndt;

import com.vuze.plugins.mlab.tools.ndt.swingemu.JOptionPane;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Constants for the NDT (Network Diagnostic Tool) client, including
 * protocol values, test type flags, firewall test statuses, data rate
 * indicators, RFC option flags, and unit conversion factors.
 */
public class NDTConstants {

    /** Prevent instantiation of constants class. */
    private NDTConstants() {}

    /** Client OS name key sent in TEST_MSG during META test. */
    public static final String META_CLIENT_OS = "client.os.name";
    /** Browser OS name key sent in TEST_MSG during META test. */
    public static final String META_BROWSER_OS = "client.browser.name";
    /** Client kernel version key sent in TEST_MSG during META test. */
    public static final String META_CLIENT_KERNEL_VERSION = "client.kernel.version";
    /** Client software version key sent in TEST_MSG during META test. */
    public static final String META_CLIENT_VERSION = "client.version";
    /** Client application name key sent in TEST_MSG during META test. */
    public static final String META_CLIENT_APPLICATION = "client.application";

    /** Average RTT variable name as sent by the NDT server in TEST_MSG results. */
    public static final String AVGRTT = "avgrtt";
    /** Current receive window size variable name as sent by the NDT server. */
    public static final String CURRWINRCVD = "CurRwinRcvd";
    /** Maximum receive window size variable name as sent by the NDT server. */
    public static final String MAXRWINRCVD = "MaxRwinRcvd";
    /** Packet loss variable name as sent by the NDT server. */
    public static final String LOSS = "loss";
    /** Minimum RTT variable name as sent by the NDT server. */
    public static final String MINRTT = "MinRTT";
    /** Maximum RTT variable name as sent by the NDT server. */
    public static final String MAXRTT = "MaxRTT";
    /** Wait seconds variable name as sent by the NDT server. */
    public static final String WAITSEC = "waitsec";
    /** Current retransmission timeout variable name as sent by the NDT server. */
    public static final String CURRTO = "CurRTO";
    /** SACKs received variable name as sent by the NDT server. */
    public static final String SACKSRCVD = "SACKsRcvd";
    /** Mismatch counter variable name as sent by the NDT server. */
    public static final String MISMATCH = "mismatch";
    /** Bad cable detection variable name as sent by the NDT server. */
    public static final String BAD_CABLE = "bad_cable";
    /** Congestion detection variable name as sent by the NDT server. */
    public static final String CONGESTION = "congestion";
    /** Congestion window time variable name as sent by the NDT server. */
    public static final String CWNDTIME = "cwndtime";
    /** Receive window time variable name as sent by the NDT server. */
    public static final String RWINTIME = "rwintime";
    /** Optimal receiver buffer size variable name as sent by the NDT server. */
    public static final String OPTRCVRBUFF = "optimalRcvrBuffer";
    /** Access technology variable name as sent by the NDT server. */
    public static final String ACCESS_TECH = "accessTech";
    /** Duplicate ACKs incoming variable name as sent by the NDT server. */
    public static final String DUPACKSIN = "DupAcksIn";

    /** NDT protocol version string sent to server during login. */
    public static final String VERSION = "v3.7.0";
    /** NDT client title bar prefix string. */
    public static final String NDT_TITLE_STR = "Network Diagnostic Tool Client ";

    /** Test type bitmask: middlebox test. */
    public static final byte TEST_MID = (1 << 0);
    /** Test type bitmask: client-to-server bandwidth test. */
    public static final byte TEST_C2S = (1 << 1);
    /** Test type bitmask: server-to-client bandwidth test. */
    public static final byte TEST_S2C = (1 << 2);
    /** Test type bitmask: simple firewall test. */
    public static final byte TEST_SFW = (1 << 3);
    /** Test type bitmask: status test. */
    public static final byte TEST_STATUS = (1 << 4);
    /** Test type bitmask: META data exchange test. */
    public static final byte TEST_META = (1 << 5);

    /** Simple Firewall test result code: not yet tested. */
    public static final int SFW_NOTTESTED = 0;
    /** Simple Firewall test result code: no firewall detected. */
    public static final int SFW_NOFIREWALL = 1;
    /** Simple Firewall test result code: firewall presence unknown. */
    public static final int SFW_UNKNOWN = 2;
    /** Simple Firewall test result code: firewall likely present. */
    public static final int SFW_POSSIBLE = 3;

    /** Fractional difference threshold for packet queuing detection. */
    public static final double VIEW_DIFF = 0.1;

    /** Mailto parameter key name for target URL. */
    public static final String TARGET1 = "U";
    /** Mailto parameter key name for host. */
    public static final String TARGET2 = "H";

    /** Default NDT control connection port (non-SSL). */
    public static final int CONTROL_PORT_DEFAULT = 3001;
    /** Default NDT control connection port (SSL). */
    public static final int CONTROL_PORT_SSL = 3010;

    /** SRV_QUEUE message body value: test starts now. */
    public static final int SRV_QUEUE_TEST_STARTS_NOW = 0;
    /** SRV_QUEUE message body value: server fault. */
    public static final int SRV_QUEUE_SERVER_FAULT = 9977;
    /** SRV_QUEUE message body value: server busy. */
    public static final int SRV_QUEUE_SERVER_BUSY = 9988;
    /** SRV_QUEUE message body value: heartbeat keepalive. */
    public static final int SRV_QUEUE_HEARTBEAT = 9990;
    /** SRV_QUEUE message body value: server busy, retry after 60s. */
    public static final int SRV_QUEUE_SERVER_BUSY_60s = 9999;

    /** Middlebox test MSS size. */
    public static final int MIDDLEBOX_PREDEFINED_MSS = 8192;
    /** Standard Ethernet MTU size used for middlebox detection. */
    public static final int ETHERNET_MTU_SIZE = 1456;

    /** Predefined message text for the simple firewall test. */
    public static final String SFW_PREDEFINED_TEST_MESSAGE = "Simple firewall test";

    /** Resource bundle for NDT localized messages. */
    private static ResourceBundle _rscBundleMessages;
    /** Resource bundle base name for Tcpbw100 message translations. */
    public static final String TCPBW100_MSGS = "edu.internet2.ndt.locale.Tcpbw100_msgs";
    /** Predefined buffer size for data transfers. */
    public static final int PREDEFINED_BUFFER_SIZE = 8192;

    // Data rate indicator values returned by the server's link detection
    /** Data rate indicator: insufficient data to determine rate. */
    public static final int DATA_RATE_INSUFFICIENT_DATA = -2;
    /** Data rate indicator: system fault during rate detection. */
    public static final int DATA_RATE_SYSTEM_FAULT = -1;
    /** Data rate indicator: RTT measurement only. */
    public static final int DATA_RATE_RTT = 0;
    /** Data rate indicator: dial-up connection. */
    public static final int DATA_RATE_DIAL_UP = 1;
    /** Data rate indicator: T1 connection. */
    public static final int DATA_RATE_T1 = 2;
    /** Data rate indicator: Ethernet connection. */
    public static final int DATA_RATE_ETHERNET = 3;
    /** Data rate indicator: T3 connection. */
    public static final int DATA_RATE_T3 = 4;
    /** Data rate indicator: Fast Ethernet connection. */
    public static final int DATA_RATE_FAST_ETHERNET = 5;
    /** Data rate indicator: OC-12 connection. */
    public static final int DATA_RATE_OC_12 = 6;
    /** Data rate indicator: Gigabit Ethernet connection. */
    public static final int DATA_RATE_GIGABIT_ETHERNET = 7;
    /** Data rate indicator: OC-48 connection. */
    public static final int DATA_RATE_OC_48 = 8;
    /** Data rate indicator: 10 Gigabit Ethernet connection. */
    public static final int DATA_RATE_10G_ETHERNET = 9;

    // Human-readable data rate labels
    /** Human-readable label for T1 data rate. */
    public static final String T1_STR = "T1";
    /** Human-readable label for T3 data rate. */
    public static final String T3_STR = "T3";
    /** Human-readable label for Ethernet data rate. */
    public static final String ETHERNET_STR = "Ethernet";
    /** Human-readable label for Fast Ethernet data rate. */
    public static final String FAST_ETHERNET = "FastE";
    /** Human-readable label for OC-12 data rate. */
    public static final String OC_12_STR = "OC-12";
    /** Human-readable label for Gigabit Ethernet data rate. */
    public static final String GIGABIT_ETHERNET_STR = "GigE";
    /** Human-readable label for OC-48 data rate. */
    public static final String OC_48_STR = "OC-48";
    /** Human-readable label for 10 Gigabit Ethernet data rate. */
    public static final String TENGIGABIT_ETHERNET_STR = "10 Gig";
    /** Human-readable label for system fault data rate. */
    public static final String SYSTEM_FAULT_STR = "systemFault";
    /** Human-readable label for dial-up data rate. */
    public static final String DIALUP_STR = "dialup2";
    /** Human-readable label for RTT-only data rate. */
    public static final String RTT_STR = "rtt";

    /** RFC 1323 window scaling disabled. */
    public static final int RFC_1323_DISABLED = 0;
    /** RFC 1323 window scaling enabled. */
    public static final int RFC_1323_ENABLED = 1;
    /** RFC 1323 window scaling self-disabled by endpoint. */
    public static final int RFC_1323_SELF_DISABLED = 2;
    /** RFC 1323 window scaling peer-disabled by remote. */
    public static final int RFC_1323_PEER_DISABLED = 3;

    /** RFC 2018 selective acknowledgment enabled. */
    public static final int RFC_2018_ENABLED = 1;

    /** RFC 896 Nagle algorithm enabled. */
    public static final int RFC_896_ENABLED = 1;

    /** RFC 3168 explicit congestion notification enabled. */
    public static final int RFC_3168_ENABLED = 1;
    /** RFC 3168 ECN self-disabled. */
    public static final int RFC_3168_SELF_DISABLED = 2;
    /** RFC 3168 ECN peer-disabled. */
    public static final int RFC_3168_PEER_DISABLED = 3;

    /** Receiver-limited threshold (fraction of time). */
    public static final float BUFFER_LIMITED = 0.15f;

    /** Maximum TCP receive window size in bytes (16-bit field). */
    public static final int TCP_MAX_RECV_WIN_SIZE = 65535;

    // Unit conversion factors
    /** Base-10 kilo multiplier (1000). */
    public static final int KILO = 1000;
    /** Base-2 kilo multiplier (1024). */
    public static final int KILO_BITS = 1024;
    /** Conversion factor from bits to bytes (8.0). */
    public static final double EIGHT = 8.0;

    // Duplex mismatch detection indicators
    /** Duplex status indicator: OK at both ends. */
    public static final int DUPLEX_OK_INDICATOR = 0;
    /** Duplex status indicator: mismatch detected. */
    public static final int DUPLEX_NOK_INDICATOR = 1;
    /** Duplex status indicator: switch full-duplex, host half-duplex. */
    public static final int DUPLEX_SWITCH_FULL_HOST_HALF = 2;
    /** Duplex status indicator: switch half-duplex, host full-duplex. */
    public static final int DUPLEX_SWITCH_HALF_HOST_FULL = 3;
    /** Duplex status indicator: possible switch full, host half. */
    public static final int DUPLEX_SWITCH_FULL_HOST_HALF_POSS = 4;
    /** Duplex status indicator: possible switch half, host full. */
    public static final int DUPLEX_SWITCH_HALF_HOST_FULL_POSS = 5;
    /** Duplex status indicator: warning, switch half, host full. */
    public static final int DUPLEX_SWITCH_HALF_HOST_FULL_WARN = 7;

    // Cable status values
    /** Cable status: OK (no fault detected). */
    public static final int CABLE_STATUS_OK = 0;
    /** Cable status: bad cable detected. */
    public static final int CABLE_STATUS_BAD = 1;

    // Congestion status values
    /** Congestion status: no congestion detected. */
    public static final int CONGESTION_NONE = 0;
    /** Congestion status: congestion found. */
    public static final int CONGESTION_FOUND = 1;

    /** Socket free port indicator (bind to any available port). */
    public static final int SOCKET_FREE_PORT_INDICATOR = 0;
    /** IPv4 loopback address string. */
    public static final String LOOPBACK_ADDRS_STRING = "127.0.0.1";
    /** Percentage multiplier (100). */
    public static final int PERCENTAGE = 100;

    /** Returned by {@link Protocol#recv_msg} on success. */
    public static final int PROTOCOL_MSG_READ_SUCCESS = 0;

    /**
     * Load the NDT message resource bundle for the given locale.
     *
     * @param paramLocale locale to load
     */
    public static void initConstants(Locale paramLocale) {
        try {
            _rscBundleMessages = ResourceBundle.getBundle(TCPBW100_MSGS, paramLocale);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Error while loading language files:\n" + e.getMessage());
        }
    }

    /**
     * Load the NDT message resource bundle for the given language and country.
     *
     * @param paramStrLang ISO 639 language code (e.g. "en", "fr")
     * @param paramStrCountry ISO 3166 country code (e.g. "US", "FR"), may be ignored
     */
    public static void initConstants(String paramStrLang, String paramStrCountry) {
        try {
            Locale locale = new Locale(paramStrLang);
            _rscBundleMessages = ResourceBundle.getBundle(TCPBW100_MSGS, locale);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Error while loading language files:\n" + e.getMessage());
        }
    }

    /**
     * Look up a translated string from the NDT message bundle.
     *
     * @param paramStrName key name (e.g. "start", "done")
     * @return translated string, or the key itself if not found
     */
    public static String getMessageString(String paramStrName) {
        return _rscBundleMessages.getString(paramStrName);
    }

}
