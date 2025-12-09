/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 ******************************************************************/

package org.cybergarage.upnp;

import org.cybergarage.net.HostInterface;
import org.cybergarage.soap.SOAP;
import org.cybergarage.upnp.ssdp.SSDP;
import org.cybergarage.util.Debug;
import org.cybergarage.xml.Parser;

/**
 * Global utility class for UPnP framework configuration and operations.
 *
 * <p>This class provides static methods and constants for configuring the UPnP framework, including
 * network settings, XML parser configuration, and utility functions for UUID generation, server
 * identification, and framework initialization.
 *
 * <p>The class serves as the main entry point for framework-wide configuration and provides
 * constants used throughout the UPnP implementation.
 *
 * @since 1.0
 * @author Satoshi Konno
 */
public class UPnP {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    /**
     * Name of the system properties used to identifies the default XML Parser.<br>
     * The value of the properties MUST BE the fully qualified class name of<br>
     * XML Parser which CyberLink should use.
     */
    public static final String XML_CLASS_PROPERTTY = "cyberlink.upnp.xml.parser";

    /** Framework name used in server identification. */
    public static final String NAME = "CyberLinkJava";

    /** Framework version used in server identification. */
    public static final String VERSION = "3.0";

    /** Number of retry attempts for server operations. */
    // I2P was 100
    public static final int SERVER_RETRY_COUNT = 4;

    /** Default extra time in seconds before considering a device expired. */
    public static final int DEFAULT_EXPIRED_DEVICE_EXTRA_TIME = 60;

    /**
     * Generates the server identification string for UPnP operations.
     *
     * @return a server identification string in the format "OS/Version UPnP/1.0
     *     FrameworkName/FrameworkVersion"
     */
    public static final String getServerName() {
        String osName = System.getProperty("os.name");
        String osVer = System.getProperty("os.version");
        return osName + "/" + osVer + " UPnP/1.0 " + NAME + "/" + VERSION;
    }

    /** INMPR03 protocol identifier. */
    public static final String INMPR03 = "INMPR03";

    /** INMPR03 protocol version. */
    public static final String INMPR03_VERSION = "1.0";

    /** Number of discovery attempts over wireless interfaces for INMPR03. */
    public static final int INMPR03_DISCOVERY_OVER_WIRELESS_COUNT = 4;

    /** Standard XML declaration for UPnP documents. */
    public static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";

    /** Maximum value for UPnP.org configuration IDs. */
    public static final int CONFIGID_UPNP_ORG_MAX = 16777215;

    ////////////////////////////////////////////////
    //	Enable / Disable
    ////////////////////////////////////////////////

    /** Enable IPv6-only addressing mode. */
    public static final int USE_ONLY_IPV6_ADDR = 1;

    /** Enable loopback address usage. */
    public static final int USE_LOOPBACK_ADDR = 2;

    /** Enable IPv6 link-local scope addresses. */
    public static final int USE_IPV6_LINK_LOCAL_SCOPE = 3;

    /** Enable IPv6 subnet scope addresses. */
    public static final int USE_IPV6_SUBNET_SCOPE = 4;

    /** Enable IPv6 administrative scope addresses. */
    public static final int USE_IPV6_ADMINISTRATIVE_SCOPE = 5;

    /** Enable IPv6 site-local scope addresses. */
    public static final int USE_IPV6_SITE_LOCAL_SCOPE = 6;

    /** Enable IPv6 global scope addresses. */
    public static final int USE_IPV6_GLOBAL_SCOPE = 7;

    /** Enable SSDP search responses on multiple interfaces. */
    public static final int USE_SSDP_SEARCHRESPONSE_MULTIPLE_INTERFACES = 8;

    /** Enable IPv4-only addressing mode. */
    public static final int USE_ONLY_IPV4_ADDR = 9;

    /**
     * Enables a specific UPnP framework feature or configuration option.
     *
     * @param value the feature to enable (one of the USE_* constants)
     */
    public static final void setEnable(int value) {
        switch (value) {
            case USE_ONLY_IPV6_ADDR:
                {
                    HostInterface.USE_ONLY_IPV6_ADDR = true;
                }
                break;
            case USE_ONLY_IPV4_ADDR:
                {
                    HostInterface.USE_ONLY_IPV4_ADDR = true;
                }
                break;
            case USE_LOOPBACK_ADDR:
                {
                    HostInterface.USE_LOOPBACK_ADDR = true;
                }
                break;
            case USE_IPV6_LINK_LOCAL_SCOPE:
                {
                    SSDP.setIPv6Address(SSDP.IPV6_LINK_LOCAL_ADDRESS);
                }
                break;
            case USE_IPV6_SUBNET_SCOPE:
                {
                    SSDP.setIPv6Address(SSDP.IPV6_SUBNET_ADDRESS);
                }
                break;
            case USE_IPV6_ADMINISTRATIVE_SCOPE:
                {
                    SSDP.setIPv6Address(SSDP.IPV6_ADMINISTRATIVE_ADDRESS);
                }
                break;
            case USE_IPV6_SITE_LOCAL_SCOPE:
                {
                    SSDP.setIPv6Address(SSDP.IPV6_SITE_LOCAL_ADDRESS);
                }
                break;
            case USE_IPV6_GLOBAL_SCOPE:
                {
                    SSDP.setIPv6Address(SSDP.IPV6_GLOBAL_ADDRESS);
                }
                break;
        }
    }

    /**
     * Disables a specific UPnP framework feature or configuration option.
     *
     * @param value the feature to disable (one of the USE_* constants)
     */
    public static final void setDisable(int value) {
        switch (value) {
            case USE_ONLY_IPV6_ADDR:
                {
                    HostInterface.USE_ONLY_IPV6_ADDR = false;
                }
                break;
            case USE_ONLY_IPV4_ADDR:
                {
                    HostInterface.USE_ONLY_IPV4_ADDR = false;
                }
                break;
            case USE_LOOPBACK_ADDR:
                {
                    HostInterface.USE_LOOPBACK_ADDR = false;
                }
                break;
        }
    }

    /**
     * Checks if a specific UPnP framework feature or configuration option is enabled.
     *
     * @param value the feature to check (one of the USE_* constants)
     * @return true if the feature is enabled, false otherwise
     */
    public static final boolean isEnabled(int value) {
        switch (value) {
            case USE_ONLY_IPV6_ADDR:
                {
                    return HostInterface.USE_ONLY_IPV6_ADDR;
                }
            case USE_ONLY_IPV4_ADDR:
                {
                    return HostInterface.USE_ONLY_IPV4_ADDR;
                }
            case USE_LOOPBACK_ADDR:
                {
                    return HostInterface.USE_LOOPBACK_ADDR;
                }
        }
        return false;
    }

    ////////////////////////////////////////////////
    //	UUID
    ////////////////////////////////////////////////

    private static final String toUUID(int seed) {
        String id = Integer.toString(seed & 0xFFFF, 16);
        int idLen = id.length();
        String uuid = "";
        for (int n = 0; n < (4 - idLen); n++) uuid += "0";
        uuid += id;
        return uuid;
    }

    /**
     * Creates a unique UUID for UPnP device identification.
     *
     * @return a UUID string in the standard UPnP format
     */
    public static final String createUUID() {
        long time1 = System.currentTimeMillis();
        long time2 = (long) ((double) System.currentTimeMillis() * Math.random());
        return toUUID((int) (time1 & 0xFFFF))
                + "-"
                + toUUID((int) ((time1 >> 32) | 0xA000) & 0xFFFF)
                + "-"
                + toUUID((int) (time2 & 0xFFFF))
                + "-"
                + toUUID((int) ((time2 >> 32) | 0xE000) & 0xFFFF);
    }

    ////////////////////////////////////////////////
    //	BootId
    ////////////////////////////////////////////////

    /**
     * Creates a boot ID for UPnP device identification.
     *
     * @return a boot ID based on the current system time in seconds
     */
    public static final int createBootId() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    ////////////////////////////////////////////////
    //	ConfigId
    ////////////////////////////////////////////////

    /**
     * Calculates a configuration ID from the given XML configuration.
     *
     * @param configXml the XML configuration string
     * @return the calculated configuration ID
     */
    public static final int caluculateConfigId(String configXml) {
        if (configXml == null) return 0;
        int configId = 0;
        int configLen = configXml.length();
        for (int n = 0; n < configLen; n++) {
            configId += configXml.codePointAt(n);
            if (configId < CONFIGID_UPNP_ORG_MAX) continue;
            configId = configId % CONFIGID_UPNP_ORG_MAX;
        }
        return configId;
    }

    ////////////////////////////////////////////////
    // XML Parser
    ////////////////////////////////////////////////

    private static Parser xmlParser;

    /**
     * Sets the XML parser to be used by the UPnP framework.
     *
     * @param parser the XML parser implementation
     */
    public static final void setXMLParser(Parser parser) {
        xmlParser = parser;
        SOAP.setXMLParser(parser);
    }

    /**
     * Gets the XML parser used by the UPnP framework.
     *
     * @return the XML parser implementation
     * @throws RuntimeException if no XML parser is available
     */
    public static final Parser getXMLParser() {
        if (xmlParser == null) {
            xmlParser = loadDefaultXMLParser();
            if (xmlParser == null)
                throw new RuntimeException(
                        "No XML parser defined. And unable to laod any. \n"
                                + "Try to invoke UPnP.setXMLParser before UPnP.getXMLParser");
            SOAP.setXMLParser(xmlParser);
        }
        return xmlParser;
    }

    /**
     * This method loads the default XML Parser using the following behavior: - First if present
     * loads the parsers specified by the system property {@link UPnP#XML_CLASS_PROPERTTY}<br>
     * - Second by a fall-back technique, it tries to load the XMLParser from one<br>
     * of the following classes: {@link JaxpParser}, {@link kXML2Parser}, {@link XercesParser}
     *
     * @return {@link Parser} which has been loaded successuflly or null otherwise
     * @since 1.8.0
     */
    private static Parser loadDefaultXMLParser() {
        Parser parser = null;

        String[] parserClass =
                new String[] {
                    System.getProperty(XML_CLASS_PROPERTTY),
                    // "org.cybergarage.xml.parser.XmlPullParser",
                    "org.cybergarage.xml.parser.JaxpParser"
                    // "org.cybergarage.xml.parser.kXML2Parser",
                    // "org.cybergarage.xml.parser.XercesParser"
                };

        for (int i = 0; i < parserClass.length; i++) {
            if (parserClass[i] == null) continue;
            try {
                parser =
                        (Parser)
                                Class.forName(parserClass[i])
                                        .getDeclaredConstructor()
                                        .newInstance();
                return parser;
            } catch (Throwable e) {
                Debug.warning("Unable to load " + parserClass[i] + " as XMLParser due to " + e);
            }
        }
        return null;
    }

    ////////////////////////////////////////////////
    //	TTL
    ////////////////////////////////////////////////

    /** Default time-to-live value for multicast packets. */
    public static final int DEFAULT_TTL = 4;

    private static int timeToLive = DEFAULT_TTL;

    /**
     * Sets the time-to-live value for multicast packets.
     *
     * @param value the TTL value to set
     */
    public static final void setTimeToLive(int value) {
        timeToLive = value;
    }

    /**
     * Gets the current time-to-live value for multicast packets.
     *
     * @return the current TTL value
     */
    public static final int getTimeToLive() {
        return timeToLive;
    }

    ////////////////////////////////////////////////
    //	Initialize
    ////////////////////////////////////////////////

    static {
        ////////////////////////////
        // Interface Option
        ////////////////////////////

        // setXMLParser(new kXML2Parser());

        ////////////////////////////
        // TimeToLive
        ////////////////////////////

        setTimeToLive(DEFAULT_TTL);

        ////////////////////////////
        // Debug Option
        ////////////////////////////

        // Debug.on();
    }

    /**
     * Initializes the UPnP framework. This method ensures the static initialization block is
     * executed.
     */
    public static final void initialize() {
        // Dummy function to call UPnP.static
    }
}
