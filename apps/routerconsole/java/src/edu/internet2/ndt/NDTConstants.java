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

    // META test key names sent in TEST_MSG during META test
    public static final String META_CLIENT_OS = "client.os.name";
    public static final String META_BROWSER_OS = "client.browser.name";
    public static final String META_CLIENT_KERNEL_VERSION = "client.kernel.version";
    public static final String META_CLIENT_VERSION = "client.version";
    public static final String META_CLIENT_APPLICATION = "client.application";

    // Variable names as sent by the NDT server in TEST_MSG results
    public static final String AVGRTT = "avgrtt";
    public static final String CURRWINRCVD = "CurRwinRcvd";
    public static final String MAXRWINRCVD = "MaxRwinRcvd";
    public static final String LOSS = "loss";
    public static final String MINRTT = "MinRTT";
    public static final String MAXRTT = "MaxRTT";
    public static final String WAITSEC = "waitsec";
    public static final String CURRTO = "CurRTO";
    public static final String SACKSRCVD = "SACKsRcvd";
    public static final String MISMATCH = "mismatch";
    public static final String BAD_CABLE = "bad_cable";
    public static final String CONGESTION = "congestion";
    public static final String CWNDTIME = "cwndtime";
    public static final String RWINTIME = "rwintime";
    public static final String OPTRCVRBUFF = "optimalRcvrBuffer";
    public static final String ACCESS_TECH = "accessTech";
    public static final String DUPACKSIN = "DupAcksIn";

    /** NDT protocol version string sent to server during login. */
    public static final String VERSION = "v3.7.0";
    public static final String NDT_TITLE_STR = "Network Diagnostic Tool Client ";

    // Test type bitmasks (OR'd together to form the test request byte)
    public static final byte TEST_MID = (1 << 0);
    public static final byte TEST_C2S = (1 << 1);
    public static final byte TEST_S2C = (1 << 2);
    public static final byte TEST_SFW = (1 << 3);
    public static final byte TEST_STATUS = (1 << 4);
    public static final byte TEST_META = (1 << 5);

    // Simple Firewall test result codes
    public static final int SFW_NOTTESTED = 0;
    public static final int SFW_NOFIREWALL = 1;
    public static final int SFW_UNKNOWN = 2;
    public static final int SFW_POSSIBLE = 3;

    /** Fractional difference threshold for packet queuing detection. */
    public static final double VIEW_DIFF = 0.1;

    // Mailto parameter key names
    public static final String TARGET1 = "U";
    public static final String TARGET2 = "H";

    // Default NDT control ports
    public static final int CONTROL_PORT_DEFAULT = 3001;
    public static final int CONTROL_PORT_SSL = 3010;

    // SRV_QUEUE message body values indicating server status
    public static final int SRV_QUEUE_TEST_STARTS_NOW = 0;
    public static final int SRV_QUEUE_SERVER_FAULT = 9977;
    public static final int SRV_QUEUE_SERVER_BUSY = 9988;
    public static final int SRV_QUEUE_HEARTBEAT = 9990;
    public static final int SRV_QUEUE_SERVER_BUSY_60s = 9999;

    // Middlebox test constants
    public static final int MIDDLEBOX_PREDEFINED_MSS = 8192;
    public static final int ETHERNET_MTU_SIZE = 1456;

    // Simple Firewall test constants
    public static final String SFW_PREDEFINED_TEST_MESSAGE = "Simple firewall test";

    private static ResourceBundle _rscBundleMessages;
    public static final String TCPBW100_MSGS = "edu.internet2.ndt.locale.Tcpbw100_msgs";
    public static final int PREDEFINED_BUFFER_SIZE = 8192;

    // Data rate indicator values returned by the server's link detection
    public static final int DATA_RATE_INSUFFICIENT_DATA = -2;
    public static final int DATA_RATE_SYSTEM_FAULT = -1;
    public static final int DATA_RATE_RTT = 0;
    public static final int DATA_RATE_DIAL_UP = 1;
    public static final int DATA_RATE_T1 = 2;
    public static final int DATA_RATE_ETHERNET = 3;
    public static final int DATA_RATE_T3 = 4;
    public static final int DATA_RATE_FAST_ETHERNET = 5;
    public static final int DATA_RATE_OC_12 = 6;
    public static final int DATA_RATE_GIGABIT_ETHERNET = 7;
    public static final int DATA_RATE_OC_48 = 8;
    public static final int DATA_RATE_10G_ETHERNET = 9;

    // Human-readable data rate labels
    public static final String T1_STR = "T1";
    public static final String T3_STR = "T3";
    public static final String ETHERNET_STR = "Ethernet";
    public static final String FAST_ETHERNET = "FastE";
    public static final String OC_12_STR = "OC-12";
    public static final String GIGABIT_ETHERNET_STR = "GigE";
    public static final String OC_48_STR = "OC-48";
    public static final String TENGIGABIT_ETHERNET_STR = "10 Gig";
    public static final String SYSTEM_FAULT_STR = "systemFault";
    public static final String DIALUP_STR = "dialup2";
    public static final String RTT_STR = "rtt";

    // RFC 1323 Window Scaling: 0=disabled, 1=enabled, 2=self-disabled, 3=peer-disabled
    public static final int RFC_1323_DISABLED = 0;
    public static final int RFC_1323_ENABLED = 1;
    public static final int RFC_1323_SELF_DISABLED = 2;
    public static final int RFC_1323_PEER_DISABLED = 3;

    // RFC 2018 Selective Acknowledgment
    public static final int RFC_2018_ENABLED = 1;

    // RFC 896 Nagle Algorithm
    public static final int RFC_896_ENABLED = 1;

    // RFC 3168 Explicit Congestion Notification: 0=disabled, 1=enabled, 2=self-disabled, 3=peer-disabled
    public static final int RFC_3168_ENABLED = 1;
    public static final int RFC_3168_SELF_DISABLED = 2;
    public static final int RFC_3168_PEER_DISABLED = 3;

    /** Receiver-limited threshold (fraction of time). */
    public static final float BUFFER_LIMITED = 0.15f;

    /** Maximum TCP receive window size in bytes (16-bit field). */
    public static final int TCP_MAX_RECV_WIN_SIZE = 65535;

    // Unit conversion factors
    public static final int KILO = 1000;
    public static final int KILO_BITS = 1024;
    public static final double EIGHT = 8.0;

    // Duplex mismatch detection indicators
    public static final int DUPLEX_OK_INDICATOR = 0;
    public static final int DUPLEX_NOK_INDICATOR = 1;
    public static final int DUPLEX_SWITCH_FULL_HOST_HALF = 2;
    public static final int DUPLEX_SWITCH_HALF_HOST_FULL = 3;
    public static final int DUPLEX_SWITCH_FULL_HOST_HALF_POSS = 4;
    public static final int DUPLEX_SWITCH_HALF_HOST_FULL_POSS = 5;
    public static final int DUPLEX_SWITCH_HALF_HOST_FULL_WARN = 7;

    // Cable status values
    public static final int CABLE_STATUS_OK = 0;
    public static final int CABLE_STATUS_BAD = 1;

    // Congestion status values
    public static final int CONGESTION_NONE = 0;
    public static final int CONGESTION_FOUND = 1;

    public static final int SOCKET_FREE_PORT_INDICATOR = 0;
    public static final String LOOPBACK_ADDRS_STRING = "127.0.0.1";
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
