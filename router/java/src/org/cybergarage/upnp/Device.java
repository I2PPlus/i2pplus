/*
 * CyberLink for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.upnp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HashMap;
import org.cybergarage.http.HTTP;
import org.cybergarage.http.HTTPRequest;
import org.cybergarage.http.HTTPResponse;
import org.cybergarage.http.HTTPServerList;
import org.cybergarage.http.HTTPStatus;
import org.cybergarage.net.HostInterface;
import org.cybergarage.soap.SOAPResponse;
import org.cybergarage.upnp.control.ActionListener;
import org.cybergarage.upnp.control.ActionRequest;
import org.cybergarage.upnp.control.ActionResponse;
import org.cybergarage.upnp.control.ControlRequest;
import org.cybergarage.upnp.control.ControlResponse;
import org.cybergarage.upnp.control.QueryListener;
import org.cybergarage.upnp.control.QueryRequest;
import org.cybergarage.upnp.device.Advertiser;
import org.cybergarage.upnp.device.Description;
import org.cybergarage.upnp.device.InvalidDescriptionException;
import org.cybergarage.upnp.device.NTS;
import org.cybergarage.upnp.device.PresentationListener;
import org.cybergarage.upnp.device.ST;
import org.cybergarage.upnp.device.SearchListener;
import org.cybergarage.upnp.device.USN;
import org.cybergarage.upnp.event.Subscriber;
import org.cybergarage.upnp.event.Subscription;
import org.cybergarage.upnp.event.SubscriptionRequest;
import org.cybergarage.upnp.event.SubscriptionResponse;
import org.cybergarage.upnp.ssdp.SSDPNotifyRequest;
import org.cybergarage.upnp.ssdp.SSDPNotifySocket;
import org.cybergarage.upnp.ssdp.SSDPPacket;
import org.cybergarage.upnp.ssdp.SSDPSearchResponse;
import org.cybergarage.upnp.ssdp.SSDPSearchResponseSocket;
import org.cybergarage.upnp.ssdp.SSDPSearchSocketList;
import org.cybergarage.upnp.xml.DeviceData;
import org.cybergarage.util.Debug;
import org.cybergarage.util.Mutex;
import org.cybergarage.util.TimerUtil;
import org.cybergarage.xml.Node;
import org.cybergarage.xml.Parser;
import org.cybergarage.xml.ParserException;
import org.cybergarage.xml.XML;

/**
 * Represents a UPnP device in the CyberLink framework.
 *
 * <p>This class provides the core functionality for UPnP devices, including:
 *
 * <ul>
 *   <li>Device description and advertisement via SSDP
 *   <li>HTTP server functionality for handling requests
 *   <li>Service management and action invocation
 *   <li>Event notification and subscription handling
 *   <li>Hierarchical device relationships (root/embedded devices)
 * </ul>
 *
 * <p>A Device can be either a root device or an embedded device within another device. Each device
 * contains services that provide specific functionality through actions and state variables.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Automatic device discovery and announcement
 *   <li>SOAP-based control message handling
 *   <li>GENA-based event notification
 *   <li>Multi-homed network interface support
 *   <li>NMPR (Network Media Profile Requirements) compliance
 * </ul>
 *
 * @since 2002
 */
public class Device implements org.cybergarage.http.HTTPRequestListener, SearchListener {
    // //////////////////////////////////////////////
    // Constants
    // //////////////////////////////////////////////

    /**
     * Element name of a device node in the description document.
     */
    public static final String ELEM_NAME = "device";
    /**
     * Element name marking the root of a description document.
     */
    public static final String UPNP_ROOTDEVICE = "upnp:rootdevice";

    /**
     * Milliseconds to wait before sending the first SSDP announcement.
     */
    public static final int DEFAULT_STARTUP_WAIT_TIME = 1000;
    /**
     * Milliseconds to wait between repeats of an SSDP search.
     */
    public static final int DEFAULT_DISCOVERY_WAIT_TIME = 300;
    /**
     * Advertisement lifetime in seconds assigned to a freshly loaded description.
     */
    public static final int DEFAULT_LEASE_TIME = 30 * 60;

    /**
     * Port the built-in {@link HTTP} server binds to when none is set.
     */
    public static final int HTTP_DEFAULT_PORT = 4004;

    /**
     * Path served for the device description document.
     */
    public static final String DEFAULT_DESCRIPTION_URI = "/description.xml";
    /**
     * Path served for the device's presentation page.
     */
    public static final String DEFAULT_PRESENTATION_URI = "/presentation";

    // //////////////////////////////////////////////
    // Member
    // //////////////////////////////////////////////

    /** The root node of the description document. */
    private Node rootNode;
    /** This device's node within the description. */
    private Node deviceNode;

    /**
     * Gets the node holding this device's root description.
     *
     * @return the node holding the root description, or null
     */
    public Node getRootNode() {
        if (rootNode != null) return rootNode;
        if (deviceNode == null) return null;
        return deviceNode.getRootNode();
    }

    /**
     * Gets this device's own element node.
     *
     * @return this device's own <code>device</code> element
     */
    public Node getDeviceNode() {
        return deviceNode;
    }

    /**
     * Sets the node holding this device's root description.
     *
     * @param node the node holding the root description, or null to derive it from the device node
     */
    public void setRootNode(Node node) {
        rootNode = node;
    }

    /**
     * Sets this device's own element node.
     *
     * @param node this device's own <code>device</code> element
     */
    public void setDeviceNode(Node node) {
        deviceNode = node;
    }

    // //////////////////////////////////////////////
    // Initialize
    // //////////////////////////////////////////////

    static {
        UPnP.initialize();
    }

    // //////////////////////////////////////////////
    // Constructor
    // //////////////////////////////////////////////

    /**
     * Creates a device from a root description node and its own node.
     *
     * @param root the node holding the root description, or null
     * @param device this device's own <code>device</code> element
     */
    public Device(Node root, Node device) {
        rootNode = root;
        deviceNode = device;
        setUUID(UPnP.createUUID());
        setWirelessMode(false);
    }

    /**
     * Creates an empty device with no description nodes.
     */
    public Device() {
        this(null, null);
    }

    /**
     * Creates a device with no root node but the given device node.
     *
     * @param device this device's own <code>device</code> element
     */
    public Device(Node device) {
        this(null, device);
    }

    /**
     * Creates a device by loading its description from a file.
     *
     * @param descriptionFile the file holding the description XML
     * @throws InvalidDescriptionException if the description cannot be parsed or has no root or
     *                                     device element
     */
    public Device(File descriptionFile) throws InvalidDescriptionException {
        this(null, null);
        loadDescription(descriptionFile);
    }

    /**
     * Creates a device by loading its description from a stream.
     *
     * @param input the stream to read the description XML from
     * @throws InvalidDescriptionException if the description cannot be parsed or has no root or
     *                                     device element
     * @since 1.8.0
     */
    public Device(InputStream input) throws InvalidDescriptionException {
        this(null, null);
        loadDescription(input);
    }

    /**
     * Creates a device by loading its description from the named file.
     *
     * @param descriptionFileName the name of the file holding the description XML
     * @throws InvalidDescriptionException if the description cannot be parsed or has no root or
     *                                     device element
     */
    public Device(String descriptionFileName) throws InvalidDescriptionException {
        this(new File(descriptionFileName));
    }

    // //////////////////////////////////////////////
    // Mutex
    // //////////////////////////////////////////////

    /** Lock guarding the device's state. */
    private Mutex mutex = new Mutex();

    /**
     * Acquires the mutex guarding this device.
     */
    public void lock() {
        mutex.lock();
    }

    /**
     * Releases the mutex guarding this device.
     */
    public void unlock() {
        mutex.unlock();
    }

    // //////////////////////////////////////////////
    // getAbsoluteURL
    // //////////////////////////////////////////////

    /**
     * Resolves a possibly relative URL against a base URL and a description location.
     *
     * @param urlString the URL to resolve, which may be relative
     * @param baseURLStr the URLBase to resolve against, or null
     * @param locationURLStr the description location to resolve against, or null
     * @return the resolved URL, or urlString itself if it cannot be resolved
     */
    public String getAbsoluteURL(String urlString, String baseURLStr, String locationURLStr) {
        // Debug.warning("GAURL \"" + urlString + "\" \"" + baseURLStr + "\" \"" + locationURLStr +
        // '"');
        if ((urlString == null) || (urlString.length() <= 0)) return "";

        try {
            URL url = new URL(urlString);
            // Debug.warning("Return 0: " + url);
            return url.toString();
        } catch (Exception e) { /* ignored */ }

        if ((baseURLStr == null) || (baseURLStr.length() <= 0)) {
            if ((locationURLStr != null) && (0 < locationURLStr.length())) {
                if (!urlString.startsWith("/")) {
                    String absUrl;
                    // Relative to location, so back up to last slash
                    if (!locationURLStr.endsWith("/")) {
                        absUrl =
                                locationURLStr.substring(0, locationURLStr.lastIndexOf('/') + 1)
                                        + urlString;
                    } else {
                        absUrl = locationURLStr + urlString;
                    }
                    try {
                        URL url = new URL(absUrl);
                        // Debug.warning("Return 1: " + url);
                        return url.toString();
                    } catch (Exception e) { /* ignored */ }
                }

                String absUrl = HTTP.getAbsoluteURL(locationURLStr, urlString);
                try {
                    URL url = new URL(absUrl);
                    // Debug.warning("Return 3: " + url);
                    return url.toString();
                } catch (Exception e) { /* ignored */ }

                Device rootDev = getRootDevice();
                if (rootDev != null) {
                    String location = rootDev.getLocation();
                    String locationHost = HTTP.getHost(location);
                    int locationPort = HTTP.getPort(location);
                    baseURLStr = HTTP.getRequestHostURL(locationHost, locationPort);
                }
            }
        }

        if ((baseURLStr != null) && (0 < baseURLStr.length())) {
            if (!urlString.startsWith("/")) {
                String absUrl;
                // Relative to base, so back up to last slash
                if (!baseURLStr.endsWith("/")) {
                    absUrl = baseURLStr.substring(0, baseURLStr.lastIndexOf('/') + 1) + urlString;
                } else {
                    absUrl = baseURLStr + urlString;
                }
                try {
                    URL url = new URL(absUrl);
                    // Debug.warning("Return 4: " + url);
                    return url.toString();
                } catch (Exception e) { /* ignored */ }
            }

            String absUrl = HTTP.getAbsoluteURL(baseURLStr, urlString);
            try {
                URL url = new URL(absUrl);
                // Debug.warning("Return 6: " + url);
                return url.toString();
            } catch (Exception e) { /* ignored */ }
        }

        // Debug.warning("Return 7: " + urlString);
        return urlString;
    }

    /**
     * Resolves a possibly relative URL against the root device's base and location.
     *
     * @param urlString the URL to resolve, which may be relative
     * @return the URL resolved against the root device's URLBase and location
     */
    public String getAbsoluteURL(String urlString) {
        String baseURLStr = null;
        String locationURLStr = null;

        Device rootDev = getRootDevice();
        if (rootDev != null) {
            baseURLStr = rootDev.getURLBase();
            locationURLStr = rootDev.getLocation();
        }

        return getAbsoluteURL(urlString, baseURLStr, locationURLStr);
    }

    // //////////////////////////////////////////////
    // NMPR
    // //////////////////////////////////////////////

    /**
     * Marks the device NMPR compliant, or removes the NMPR elements.
     *
     * @param flag true to describe the device as NMPR compliant, false to drop the NMPR elements
     */
    public void setNMPRMode(boolean flag) {
        Node devNode = getDeviceNode();
        if (devNode == null) return;
        if (flag == true) {
            devNode.setNode(UPnP.INMPR03, UPnP.INMPR03_VERSION);
            devNode.removeNode(Device.URLBASE_NAME);
        } else {
            devNode.removeNode(UPnP.INMPR03);
        }
    }

    /**
     * Tests whether the device is marked NMPR compliant.
     *
     * @return true if the device is marked NMPR compliant
     */
    public boolean isNMPRMode() {
        Node devNode = getDeviceNode();
        if (devNode == null) return false;
        return (devNode.getNode(UPnP.INMPR03) != null) ? true : false;
    }

    // //////////////////////////////////////////////
    // Wireless
    // //////////////////////////////////////////////

    /** Whether wireless mode is on for notifications. */
    private boolean wirelessMode;

    /**
     * Records whether this device is announced over a wireless link.
     *
     * @param flag true when the device is announced over a wireless link
     */
    public void setWirelessMode(boolean flag) {
        wirelessMode = flag;
    }

    /**
     * Tests whether this device is announced over a wireless link.
     *
     * @return true when the device is announced over a wireless link
     */
    public boolean isWirelessMode() {
        return wirelessMode;
    }

    /**
     * Gets how many SSDP announcements this device should send.
     *
     * @return how many times this device should be announced
     */
    public int getSSDPAnnounceCount() {
        if (isNMPRMode() == true && isWirelessMode() == true)
            return UPnP.INMPR03_DISCOVERY_OVER_WIRELESS_COUNT;
        return 1;
    }

    // //////////////////////////////////////////////
    // Device UUID
    // //////////////////////////////////////////////

    /** The device's UUID, without the UDN prefix. */
    private String devUUID;

    /**
     * Sets the device's UUID and refreshes the UDN.
     *
     * @param uuid the new UUID
     */
    private void setUUID(String uuid) {
        this.devUUID = uuid;
    }

    /**
     * Gets the UUID generated when this device was created.
     *
     * @return the UUID generated for this device
     */
    public String getUUID() {
        return this.devUUID;
    }

    /** Rebuilds the UDN string from the UUID. */
    private void updateUDN() {
        setUDN("uuid:" + getUUID());
    }

    // //////////////////////////////////////////////
    // BootId
    // //////////////////////////////////////////////

    /** The boot id advertised in notifications. */
    private int bootId;

    /** Sets the boot id from the current time. */
    private void updateBootId() {
        this.bootId = UPnP.createBootId();
    }

    /**
     * Gets the boot ID this device announces.
     *
     * @return the boot ID announced by this device, or 0 until it is started
     */
    public int getBootId() {
        return this.bootId;
    }

    // //////////////////////////////////////////////
    // configID
    // //////////////////////////////////////////////

    /** Tag name for the configuration id. */
    private static final String CONFIG_ID = "configId";

    /**
     * Copies the configuration id from another device.
     *
     * @param dev the device to copy from
     */
    private void updateConfigId(Device dev) {
        int configId = 0;

        DeviceList cdevList = dev.getDeviceList();
        int cdevCnt = cdevList.size();
        for (int n = 0; n < cdevCnt; n++) {
            Device cdev = cdevList.getDevice(n);
            updateConfigId(cdev);
            configId += cdev.getConfigId();
            configId &= UPnP.CONFIGID_UPNP_ORG_MAX;
        }

        ServiceList serviceList = dev.getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            service.updateConfigId();
            configId += service.getConfigId();
            configId &= UPnP.CONFIGID_UPNP_ORG_MAX;
        }

        Node devNode = getDeviceNode();
        if (devNode == null) return;

        String devDescXml = devNode.toString();
        configId += UPnP.caluculateConfigId(devDescXml);
        configId &= UPnP.CONFIGID_UPNP_ORG_MAX;
        devNode.setAttribute(CONFIG_ID, configId);
    }

    /**
     * Recomputes the configuration ID of this device and everything below it.
     */
    public void updateConfigId() {
        updateConfigId(this);
    }

    /**
     * Gets the configuration ID computed for this device.
     *
     * @return the configuration ID of this device, or 0 until it is computed
     */
    public int getConfigId() {
        Node devNode = getDeviceNode();
        if (devNode == null) return 0;
        return devNode.getAttributeIntegerValue(CONFIG_ID);
    }

    // //////////////////////////////////////////////
    // Root Device
    // //////////////////////////////////////////////

    /**
     * Gets the root of this device's description tree.
     *
     * @return the root of this device's description tree, or null if it has no root
     */
    public Device getRootDevice() {
        Node rootNode = getRootNode();
        if (rootNode == null) return null;
        Node devNode = rootNode.getNode(Device.ELEM_NAME);
        if (devNode == null) return null;
        return new Device(rootNode, devNode);
    }

    // //////////////////////////////////////////////
    // Parent Device
    // //////////////////////////////////////////////


    /**
     * Get the device that contains this one.
     *
     * @return A Device that contain this object.<br>
     *         Return <code>null</code> if this is a root device.
     */
    public Device getParentDevice() {
        if (isRootDevice()) return null;
        Node devNode = getDeviceNode();
        Node aux = null;
        // <device><deviceList><device>
        aux = devNode.getParentNode().getParentNode();
        return new Device(aux);
    }

    /**
     * Add a Service to device without checking for duplicate or syntax error
     *
     * @param s Add Service s to the Device
     */
    public void addService(Service s) {
        Node serviceListNode = getDeviceNode().getNode(ServiceList.ELEM_NAME);
        if (serviceListNode == null) {
            serviceListNode = new Node(ServiceList.ELEM_NAME);
            getDeviceNode().addNode(serviceListNode);
        }
        serviceListNode.addNode(s.getServiceNode());
    }

    /**
     * Add a Device to device without checking for duplicate or syntax error. This method set or
     * reset the root node of the Device and itself<br>
     * <br>
     * Note: This method should be used to create a dynamic<br>
     * Device without writing any XML that describe the device<br>
     *
     * @param d Add Device d to the Device
     */
    public void addDevice(Device d) {
        Node deviceListNode = getDeviceNode().getNode(DeviceList.ELEM_NAME);
        if (deviceListNode == null) {
            // deviceListNode = new Node(ServiceList.ELEM_NAME); twa wrong
            // ELEM_NAME;
            deviceListNode = new Node(DeviceList.ELEM_NAME);
            getDeviceNode().addNode(deviceListNode);
        }
        deviceListNode.addNode(d.getDeviceNode());
        d.setRootNode(null);
        if (getRootNode() == null) {
            Node root = new Node(RootDescription.ROOT_ELEMENT);
            root.setNameSpace("", RootDescription.ROOT_ELEMENT_NAMESPACE);
            Node spec = new Node(RootDescription.SPECVERSION_ELEMENT);
            Node maj = new Node(RootDescription.MAJOR_ELEMENT);
            maj.setValue("1");
            Node min = new Node(RootDescription.MINOR_ELEMENT);
            min.setValue("0");
            spec.addNode(maj);
            spec.addNode(min);
            root.addNode(spec);
            setRootNode(root);
        }
    }

    // //////////////////////////////////////////////
    // UserData
    // //////////////////////////////////////////////

    /**
     * Returns the device's data for notifications.
     *
     * @return the device data
     */
    private DeviceData getDeviceData() {
        Node node = getDeviceNode();
        DeviceData userData = (DeviceData) node.getUserData();
        if (userData == null) {
            userData = new DeviceData();
            node.setUserData(userData);
            userData.setNode(node);
        }
        return userData;
    }

    // //////////////////////////////////////////////
    // Description
    // //////////////////////////////////////////////

    /**
     * Sets the file holding the description document.
     *
     * @param file the description file
     */
    private void setDescriptionFile(File file) {
        getDeviceData().setDescriptionFile(file);
    }

    /**
     * Gets the file this device's description was loaded from.
     *
     * @return the file the description was loaded from, or null if it came from a stream or string
     */
    public File getDescriptionFile() {
        return getDeviceData().getDescriptionFile();
    }

    /**
     * Sets the URI the description is served at.
     *
     * @param uri the description URI
     */
    private void setDescriptionURI(String uri) {
        getDeviceData().setDescriptionURI(uri);
    }

    /**
     * Returns the URI the description is served at.
     *
     * @return the description URI
     */
    private String getDescriptionURI() {
        return getDeviceData().getDescriptionURI();
    }

    /**
     * Checks whether a URI names this device's description.
     *
     * @param uri the URI to check
     * @return true if it is the description URI
     */
    private boolean isDescriptionURI(String uri) {
        String descriptionURI = getDescriptionURI();
        if (uri == null || descriptionURI == null) return false;
        return descriptionURI.equals(uri);
    }

    /**
     * Gets the directory holding this device's description file.
     *
     * @return the directory holding the description file, or an empty string if there is none
     */
    public String getDescriptionFilePath() {
        File descriptionFile = getDescriptionFile();
        if (descriptionFile == null) return "";
        return descriptionFile.getAbsoluteFile().getParent();
    }

    /**
     * Load the device description from a stream.
     *
     * @param input the stream to read the description XML from
     * @return true if the description was loaded
     * @throws InvalidDescriptionException if the description cannot be parsed or has no root or
     *                                     device element
     * @since 1.8.0
     */
    public boolean loadDescription(InputStream input) throws InvalidDescriptionException {
        try {
            Parser parser = UPnP.getXMLParser();
            rootNode = parser.parse(input);
            if (rootNode == null)
                throw new InvalidDescriptionException(Description.NOROOT_EXCEPTION);
            deviceNode = rootNode.getNode(Device.ELEM_NAME);
            if (deviceNode == null)
                throw new InvalidDescriptionException(Description.NOROOTDEVICE_EXCEPTION);
        } catch (ParserException e) {
            throw new InvalidDescriptionException(e);
        }

        if (initializeLoadedDescription() == false) return false;

        setDescriptionFile(null);

        return true;
    }

    /**
     * Loads the device description from description XML text.
     *
     * @param descString the description XML
     * @return true if the description was loaded
     * @throws InvalidDescriptionException if the description cannot be parsed or has no root or
     *                                     device element
     */
    public boolean loadDescription(String descString) throws InvalidDescriptionException {
        try {
            Parser parser = UPnP.getXMLParser();
            rootNode = parser.parse(descString);
            if (rootNode == null)
                throw new InvalidDescriptionException(Description.NOROOT_EXCEPTION);
            deviceNode = rootNode.getNode(Device.ELEM_NAME);
            if (deviceNode == null)
                throw new InvalidDescriptionException(Description.NOROOTDEVICE_EXCEPTION);
        } catch (ParserException e) {
            throw new InvalidDescriptionException(e);
        }

        if (initializeLoadedDescription() == false) return false;

        setDescriptionFile(null);

        return true;
    }

    /**
     * Loads the device description from a file.
     *
     * @param file the file holding the description XML
     * @return true if the description was loaded
     * @throws InvalidDescriptionException if the description cannot be parsed or has no root or
     *                                     device element
     */
    public boolean loadDescription(File file) throws InvalidDescriptionException {
        try {
            Parser parser = UPnP.getXMLParser();
            rootNode = parser.parse(file);
            if (rootNode == null)
                throw new InvalidDescriptionException(Description.NOROOT_EXCEPTION, file);
            deviceNode = rootNode.getNode(Device.ELEM_NAME);
            if (deviceNode == null)
                throw new InvalidDescriptionException(Description.NOROOTDEVICE_EXCEPTION, file);
        } catch (ParserException e) {
            throw new InvalidDescriptionException(e);
        }

        if (initializeLoadedDescription() == false) return false;

        setDescriptionFile(file);

        return true;
    }

    /**
     * Initializes the device fields from a loaded description.
     *
     * @return true on success
     */
    private boolean initializeLoadedDescription() {
        setDescriptionURI(DEFAULT_DESCRIPTION_URI);
        setLeaseTime(DEFAULT_LEASE_TIME);
        setHTTPPort(HTTP_DEFAULT_PORT);

        if (hasUDN() == false) updateUDN();

        return true;
    }

    // //////////////////////////////////////////////
    // isDeviceNode
    // //////////////////////////////////////////////

    /**
     * Tests whether a node is a device element.
     *
     * @param node the node to test
     * @return true if the node is a <code>device</code> element
     */
    public static boolean isDeviceNode(Node node) {
        return Device.ELEM_NAME.equals(node.getName());
    }

    // //////////////////////////////////////////////
    // Root Device
    // //////////////////////////////////////////////

    /**
     * Tests whether this device is the root of its description tree.
     *
     * @return true if this device is the root of its description tree
     */
    public boolean isRootDevice() {
        return getRootNode().getNode("device").getNodeValue(UDN).equals(getUDN());
    }

    // //////////////////////////////////////////////
    // Root Device
    // //////////////////////////////////////////////

    /**
     * Records the SSDP packet that announced this device.
     *
     * @param packet the SSDP packet that announced this device
     */
    public void setSSDPPacket(SSDPPacket packet) {
        getDeviceData().setSSDPPacket(packet);
    }

    /**
     * Gets the SSDP packet that announced this device.
     *
     * @return the SSDP packet that announced this device, or null
     */
    public SSDPPacket getSSDPPacket() {
        return getSSDPPacket(false);
    }

    /**
     * I2P for multiple location support
     *
     * @param preferIPv6 whether to prefer the IPv6 packet when both were advertised
     * @return the SSDP packet that announced this device, or null
     * @since 0.9.50
     */
    public SSDPPacket getSSDPPacket(boolean preferIPv6) {
        if (isRootDevice() == false) return null;
        return getDeviceData().getSSDPPacket(preferIPv6);
    }

    // //////////////////////////////////////////////
    // Location
    // //////////////////////////////////////////////

    /**
     * Sets the URL this device's description is served from.
     *
     * @param value the URL the description is served from
     */
    public void setLocation(String value) {
        getDeviceData().setLocation(value);
    }

    /**
     * Gets the URL this device's description is served from.
     *
     * @return the URL the description is served from
     */
    public String getLocation() {
        return getLocation(false);
    }

    /**
     * I2P for multiple location support
     *
     * @param preferIPv6 whether to prefer the IPv6 location when both were advertised
     * @return the URL the description is served from
     * @since 0.9.50
     */
    public String getLocation(boolean preferIPv6) {
        SSDPPacket packet = getSSDPPacket(preferIPv6);
        if (packet != null) return packet.getLocation();
        return getDeviceData().getLocation(preferIPv6);
    }

    // //////////////////////////////////////////////
    // LeaseTime
    // //////////////////////////////////////////////

    /**
     * Sets the advertisement lifetime, re-announcing a running device.
     *
     * @param value the advertisement lifetime in seconds
     */
    public void setLeaseTime(int value) {
        getDeviceData().setLeaseTime(value);
        Advertiser adv = getAdvertiser();
        if (adv != null) {
            announce();
            adv.restart();
        }
    }

    /**
     * Gets the advertisement lifetime, preferring the announced packet's value.
     *
     * @return the advertisement lifetime in seconds
     */
    public int getLeaseTime() {
        SSDPPacket packet = getSSDPPacket();
        if (packet != null) return packet.getLeaseTime();
        return getDeviceData().getLeaseTime();
    }

    // //////////////////////////////////////////////
    // TimeStamp
    // //////////////////////////////////////////////

    /**
     * Gets when this device was last announced.
     *
     * @return when this device was last announced, in milliseconds since the epoch, or 0
     */
    public long getTimeStamp() {
        SSDPPacket packet = getSSDPPacket();
        if (packet != null) return packet.getTimeStamp();
        return 0;
    }

    /**
     * Gets the seconds elapsed since this device was last announced.
     *
     * @return the seconds since this device was last announced
     */
    public long getElapsedTime() {
        return (System.currentTimeMillis() - getTimeStamp()) / 1000;
    }

    /**
     * Tests whether this device's advertisement has outlived its lease time.
     *
     * @return true if this device has not been announced within its lease time
     */
    public boolean isExpired() {
        long elipsedTime = getElapsedTime();
        long leaseTime = getLeaseTime() + UPnP.DEFAULT_EXPIRED_DEVICE_EXTRA_TIME;
        if (leaseTime < elipsedTime) return true;
        return false;
    }

    // //////////////////////////////////////////////
    // URL Base
    // //////////////////////////////////////////////

    /** Tag name for the base URL. */
    private static final String URLBASE_NAME = "URLBase";

    /**
     * Sets the base URL for service URLs.
     *
     * @param value the base URL
     */
    private void setURLBase(String value) {
        if (isRootDevice() == true) {
            Node node = getRootNode().getNode(URLBASE_NAME);
            if (node != null) {
                node.setValue(value);
                return;
            }
            node = new Node(URLBASE_NAME);
            node.setValue(value);
            int index = 1;
            if (getRootNode().hasNodes() == false) index = 1;
            getRootNode().insertNode(node, index);
        }
    }

    /**
     * Rewrites the base URL for a host.
     *
     * @param host the host name to use
     */
    private void updateURLBase(String host) {
        String urlBase = HostInterface.getHostURL(host, getHTTPPort(), "");
        setURLBase(urlBase);
    }

    /**
     * Gets the root device's URLBase.
     *
     * @return the root device's URLBase, or an empty string if this device is not the root
     */
    public String getURLBase() {
        if (isRootDevice() == true) return getRootNode().getNodeValue(URLBASE_NAME);
        return "";
    }

    // //////////////////////////////////////////////
    // deviceType
    // //////////////////////////////////////////////

    /** Tag name for the device type. */
    private static final String DEVICE_TYPE = "deviceType";

    /**
     * Sets the device type URN.
     *
     * @param value the device type URN
     */
    public void setDeviceType(String value) {
        getDeviceNode().setNode(DEVICE_TYPE, value);
    }

    /**
     * Gets the device type URN.
     *
     * @return the device type URN
     */
    public String getDeviceType() {
        return getDeviceNode().getNodeValue(DEVICE_TYPE);
    }

    /**
     * Tests whether this device carries the given device type URN.
     *
     * @param value the device type URN to compare against
     * @return true if this device has that type
     */
    public boolean isDeviceType(String value) {
        if (value == null) return false;
        return value.equals(getDeviceType());
    }

    // //////////////////////////////////////////////
    // friendlyName
    // //////////////////////////////////////////////

    /** Tag name for the friendly name. */
    private static final String FRIENDLY_NAME = "friendlyName";

    /**
     * Sets the name advertised for this device.
     *
     * @param value the name to advertise for this device
     */
    public void setFriendlyName(String value) {
        getDeviceNode().setNode(FRIENDLY_NAME, value);
    }

    /**
     * Gets the name advertised for this device.
     *
     * @return the name advertised for this device
     */
    public String getFriendlyName() {
        return getDeviceNode().getNodeValue(FRIENDLY_NAME);
    }

    // //////////////////////////////////////////////
    // manufacture
    // //////////////////////////////////////////////

    /** Tag name for the manufacturer. */
    private static final String MANUFACTURE = "manufacturer";

    /**
     * Sets the name of the company that makes this device.
     *
     * @param value the name of the company that makes this device
     */
    public void setManufacture(String value) {
        getDeviceNode().setNode(MANUFACTURE, value);
    }

    /**
     * Gets the name of the company that makes this device.
     *
     * @return the name of the company that makes this device
     */
    public String getManufacture() {
        return getDeviceNode().getNodeValue(MANUFACTURE);
    }

    // //////////////////////////////////////////////
    // manufactureURL
    // //////////////////////////////////////////////

    /** Tag name for the manufacturer URL. */
    private static final String MANUFACTURE_URL = "manufacturerURL";

    /**
     * Sets the URL of the manufacturer's home page.
     *
     * @param value the URL of the manufacturer's home page
     */
    public void setManufactureURL(String value) {
        getDeviceNode().setNode(MANUFACTURE_URL, value);
    }

    /**
     * Gets the URL of the manufacturer's home page.
     *
     * @return the URL of the manufacturer's home page
     */
    public String getManufactureURL() {
        return getDeviceNode().getNodeValue(MANUFACTURE_URL);
    }

    // //////////////////////////////////////////////
    // modelDescription
    // //////////////////////////////////////////////

    /** Tag name for the model description. */
    private static final String MODEL_DESCRIPTION = "modelDescription";

    /**
     * Sets the free-form description of this model.
     *
     * @param value the free-form description of this model
     */
    public void setModelDescription(String value) {
        getDeviceNode().setNode(MODEL_DESCRIPTION, value);
    }

    /**
     * Gets the free-form description of this model.
     *
     * @return the free-form description of this model
     */
    public String getModelDescription() {
        return getDeviceNode().getNodeValue(MODEL_DESCRIPTION);
    }

    // //////////////////////////////////////////////
    // modelName
    // //////////////////////////////////////////////

    /** Tag name for the model name. */
    private static final String MODEL_NAME = "modelName";

    /**
     * Sets the model name.
     *
     * @param value the model name
     */
    public void setModelName(String value) {
        getDeviceNode().setNode(MODEL_NAME, value);
    }

    /**
     * Gets the model name.
     *
     * @return the model name
     */
    public String getModelName() {
        return getDeviceNode().getNodeValue(MODEL_NAME);
    }

    // //////////////////////////////////////////////
    // modelNumber
    // //////////////////////////////////////////////

    /** Tag name for the model number. */
    private static final String MODEL_NUMBER = "modelNumber";

    /**
     * Sets the model number.
     *
     * @param value the model number
     */
    public void setModelNumber(String value) {
        getDeviceNode().setNode(MODEL_NUMBER, value);
    }

    /**
     * Gets the model number.
     *
     * @return the model number
     */
    public String getModelNumber() {
        return getDeviceNode().getNodeValue(MODEL_NUMBER);
    }

    // //////////////////////////////////////////////
    // modelURL
    // //////////////////////////////////////////////

    /** Tag name for the model URL. */
    private static final String MODEL_URL = "modelURL";

    /**
     * Sets the URL of the documentation for this model.
     *
     * @param value the URL of the documentation for this model
     */
    public void setModelURL(String value) {
        getDeviceNode().setNode(MODEL_URL, value);
    }

    /**
     * Gets the URL of the documentation for this model.
     *
     * @return the URL of the documentation for this model
     */
    public String getModelURL() {
        return getDeviceNode().getNodeValue(MODEL_URL);
    }

    // //////////////////////////////////////////////
    // serialNumber
    // //////////////////////////////////////////////

    /** Tag name for the serial number. */
    private static final String SERIAL_NUMBER = "serialNumber";

    /**
     * Sets the serial number of this unit.
     *
     * @param value the serial number of this unit
     */
    public void setSerialNumber(String value) {
        getDeviceNode().setNode(SERIAL_NUMBER, value);
    }

    /**
     * Gets the serial number of this unit.
     *
     * @return the serial number of this unit
     */
    public String getSerialNumber() {
        return getDeviceNode().getNodeValue(SERIAL_NUMBER);
    }

    // //////////////////////////////////////////////
    // UDN
    // //////////////////////////////////////////////

    /** Tag name for the unique device name. */
    private static final String UDN = "UDN";

    /**
     * Sets the unique device name.
     *
     * @param value the unique device name, this device's UUID prefixed with <code>uuid:</code>
     */
    public void setUDN(String value) {
        getDeviceNode().setNode(UDN, value);
    }

    /**
     * Gets the unique device name.
     *
     * @return the unique device name
     */
    public String getUDN() {
        return getDeviceNode().getNodeValue(UDN);
    }

    /**
     * Tests whether a non-empty unique device name is set.
     *
     * @return true if a non-empty UDN is set
     */
    public boolean hasUDN() {
        String udn = getUDN();
        if (udn == null || udn.length() <= 0) return false;
        return true;
    }

    // //////////////////////////////////////////////
    // UPC
    // //////////////////////////////////////////////

    /** Tag name for the UPC code. */
    private static final String UPC = "UPC";

    /**
     * Sets the Universal Product Code.
     *
     * @param value the Universal Product Code
     */
    public void setUPC(String value) {
        getDeviceNode().setNode(UPC, value);
    }

    /**
     * Gets the Universal Product Code.
     *
     * @return the Universal Product Code
     */
    public String getUPC() {
        return getDeviceNode().getNodeValue(UPC);
    }

    // //////////////////////////////////////////////
    // presentationURL
    // //////////////////////////////////////////////

    /** Tag name for the presentation URL. */
    private static final String presentationURL = "presentationURL";
    /** Listener notified of presentation requests. */
    private PresentationListener presentationListener;

    /**
     * Sets the URI path serving this device's control page.
     *
     * @param value the URI path serving this device's control page
     */
    public void setPresentationURL(String value) {
        getDeviceNode().setNode(presentationURL, value);
    }

    /**
     * Gets the URI path serving this device's control page.
     *
     * @return the URI path serving this device's control page
     */
    public String getPresentationURL() {
        return getDeviceNode().getNodeValue(presentationURL);
    }

    /**
     * Removes the presentation page, leaving the listener installed.
     *
     * @return true if there was a presentation URL to remove
     */
    public boolean removePresentationURL() {
        return getDeviceNode().removeNode(presentationURL);
    }

    /**
     * Checks whether a request targets the presentation URL.
     *
     * @param httpReq the request to check
     * @return true if it is a presentation request
     */
    private boolean isPresentationRequest(HTTPRequest httpReq) {
        if (!httpReq.isGetRequest()) return false;
        String urlPath = httpReq.getURI();
        if (urlPath == null) return false;
        String presentationURL = getPresentationURL();
        if (presentationURL == null) return false;
        return urlPath.startsWith(presentationURL);
    }

    /**
     * Installs the handler for this device's control page.
     *
     * @param listener the handler for control page requests, or null to stop serving one
     */
    public void setPresentationListener(PresentationListener listener) {
        this.presentationListener = listener;

        if (listener != null) {
            setPresentationURL(DEFAULT_PRESENTATION_URI);
        } else {
            removePresentationURL();
        }
    }

    /**
     * Tests whether a control page handler is installed.
     *
     * @return true if a control page handler is set
     */
    public boolean hasPresentationListener() {
        return (this.presentationListener != null) ? true : false;
    }

    /**
     * Gets the handler for this device's control page.
     *
     * @return the handler for control page requests, or null
     */
    public PresentationListener getPresentationListener() {
        return this.presentationListener;
    }

    // //////////////////////////////////////////////
    // deviceList
    // //////////////////////////////////////////////

    /**
     * Gets the devices embedded in this one.
     *
     * @return the devices embedded in this one
     */
    public DeviceList getDeviceList() {
        DeviceList devList = new DeviceList();
        Node devListNode = getDeviceNode().getNode(DeviceList.ELEM_NAME);
        if (devListNode == null) return devList;
        int nNode = devListNode.getNNodes();
        for (int n = 0; n < nNode; n++) {
            Node node = devListNode.getNode(n);
            if (Device.isDeviceNode(node) == false) continue;
            Device dev = new Device(node);
            devList.add(dev);
        }
        return devList;
    }

    /**
     * Tests whether a name identifies this device.
     *
     * @param name the UDN, friendly name or device type to test against
     * @return true if that name identifies this device
     */
    public boolean isDevice(String name) {
        if (name == null) return false;
        if (name.endsWith(getUDN()) == true) return true;
        if (name.equals(getFriendlyName()) == true) return true;
        if (name.endsWith(getDeviceType()) == true) return true;
        return false;
    }

    /**
     * Finds an embedded device by UDN, friendly name or device type.
     *
     * @param name the UDN, friendly name or device type to search for
     * @return the matching embedded device, or null
     */
    public Device getDevice(String name) {
        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            if (dev.isDevice(name) == true) return dev;
            Device cdev = dev.getDevice(name);
            if (cdev != null) return cdev;
        }
        return null;
    }

    /**
     * Finds an embedded device served at the given description URI.
     *
     * @param uri the description URI to search for
     * @return the embedded device served at that URI, or null
     */
    public Device getDeviceByDescriptionURI(String uri) {
        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            if (dev.isDescriptionURI(uri) == true) return dev;
            Device cdev = dev.getDeviceByDescriptionURI(uri);
            if (cdev != null) return cdev;
        }
        return null;
    }

    // //////////////////////////////////////////////
    // serviceList
    // //////////////////////////////////////////////

    /**
     * Gets the services of this device.
     *
     * @return the services of this device
     */
    public ServiceList getServiceList() {
        ServiceList serviceList = new ServiceList();
        Node serviceListNode = getDeviceNode().getNode(ServiceList.ELEM_NAME);
        if (serviceListNode == null) return serviceList;
        int nNode = serviceListNode.getNNodes();
        for (int n = 0; n < nNode; n++) {
            Node node = serviceListNode.getNode(n);
            if (Service.isServiceNode(node) == false) continue;
            Service service = new Service(node);
            serviceList.add(service);
        }
        return serviceList;
    }

    /**
     * Finds a service of this or an embedded device by type or ID.
     *
     * @param name the service type or service ID to search for
     * @return the matching service of this or an embedded device, or null
     */
    public Service getService(String name) {
        ServiceList serviceList = getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            if (service.isService(name) == true) return service;
        }

        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            Service service = dev.getService(name);
            if (service != null) return service;
        }

        return null;
    }

    /**
     * Finds a service by its service control description (SCPD) URL.
     *
     * @param searchUrl the service control URL to search for
     * @return the matching service of this or an embedded device, or null
     */
    public Service getServiceBySCPDURL(String searchUrl) {
        ServiceList serviceList = getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            if (service.isSCPDURL(searchUrl) == true) return service;
        }

        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            Service service = dev.getServiceBySCPDURL(searchUrl);
            if (service != null) return service;
        }

        return null;
    }

    /**
     * Finds a service by its control URL.
     *
     * @param searchUrl the control URL to search for
     * @return the matching service of this or an embedded device, or null
     */
    public Service getServiceByControlURL(String searchUrl) {
        ServiceList serviceList = getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            if (service.isControlURL(searchUrl) == true) return service;
        }

        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            Service service = dev.getServiceByControlURL(searchUrl);
            if (service != null) return service;
        }

        return null;
    }

    /**
     * Finds a service by its event subscription URL.
     *
     * @param searchUrl the event subscription URL to search for
     * @return the matching service of this or an embedded device, or null
     */
    public Service getServiceByEventSubURL(String searchUrl) {
        ServiceList serviceList = getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            if (service.isEventSubURL(searchUrl) == true) return service;
        }

        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            Service service = dev.getServiceByEventSubURL(searchUrl);
            if (service != null) return service;
        }

        return null;
    }

    /**
     * Finds the service a subscriber holds a subscription on.
     *
     * @param uuid the subscription ID to search for
     * @return the subscribed service with that ID, or null
     */
    public Service getSubscriberService(String uuid) {
        ServiceList serviceList = getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            String sid = service.getSID();
            if (uuid.equals(sid) == true) return service;
        }

        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            Service service = dev.getSubscriberService(uuid);
            if (service != null) return service;
        }

        return null;
    }

    // //////////////////////////////////////////////
    // StateVariable
    // //////////////////////////////////////////////

    /**
     * Finds a state variable by name, optionally restricted to one service type.
     *
     * @param serviceType the service type to restrict the search to, or null to search every
     *                    service
     * @param name the state variable name to search for
     * @return the matching state variable, or null
     */
    public StateVariable getStateVariable(String serviceType, String name) {
        if (serviceType == null && name == null) return null;

        ServiceList serviceList = getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            if (serviceType != null) {
                if (service.getServiceType().equals(serviceType) == false) continue;
            }
            StateVariable stateVar = service.getStateVariable(name);
            if (stateVar != null) return stateVar;
        }

        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            StateVariable stateVar = dev.getStateVariable(serviceType, name);
            if (stateVar != null) return stateVar;
        }

        return null;
    }

    /**
     * Finds a state variable by name across every service.
     *
     * @param name the state variable name to search for
     * @return the matching state variable, or null
     */
    public StateVariable getStateVariable(String name) {
        return getStateVariable(null, name);
    }

    // //////////////////////////////////////////////
    // Action
    // //////////////////////////////////////////////

    /**
     * Finds an action by name across every service.
     *
     * @param name the action name to search for
     * @return the matching action, or null
     */
    public Action getAction(String name) {
        ServiceList serviceList = getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            ActionList actionList = service.getActionList();
            int actionCnt = actionList.size();
            for (int i = 0; i < actionCnt; i++) {
                Action action = actionList.getAction(i);
                String actionName = action.getName();
                if (actionName == null) continue;
                if (actionName.equals(name) == true) return action;
            }
        }

        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            Action action = dev.getAction(name);
            if (action != null) return action;
        }

        return null;
    }

    // //////////////////////////////////////////////
    // iconList
    // //////////////////////////////////////////////

    /** Icon data by icon id. */
    private HashMap<String, byte[]> iconBytesMap = new HashMap<>();

    /**
     * Tests whether an icon's bytes are already cached.
     *
     * @param uri the icon URL to look up
     * @return true if the bytes for that icon are cached
     */
    public boolean isIconBytesURI(String uri) {
        byte[] iconBytes = iconBytesMap.get(uri);
        if (iconBytes == null) return false;
        return true;
    }

    /**
     * Finds an icon by its URL.
     *
     * @param uri the icon URL to search for
     * @return the matching icon, or null
     */
    public Icon getIconByURI(String uri) {
        IconList iconList = getIconList();
        if (iconList.size() <= 0) return null;

        int nIcon = iconList.size();
        for (int n = 0; n < nIcon; n++) {
            Icon icon = iconList.getIcon(n);
            if (icon.isURL(uri)) return icon;
        }

        return null;
    }

    /**
     * Appends an icon to this device's icon list.
     *
     * @param icon the icon to add
     * @return true if the icon was added
     */
    public boolean addIcon(Icon icon) {
        Node deviceNode = getDeviceNode();
        if (deviceNode == null) return false;

        Node iconListNode = deviceNode.getNode(IconList.ELEM_NAME);
        if (iconListNode == null) {
            iconListNode = new Node(IconList.ELEM_NAME);
            deviceNode.addNode(iconListNode);
        }

        Node iconNode = new Node(Icon.ELEM_NAME);
        if (icon.getIconNode() != null) {
            iconNode.set(icon.getIconNode());
        }
        iconListNode.addNode(iconNode);

        if (icon.hasURL() && icon.hasBytes()) {
            iconBytesMap.put(icon.getURL(), icon.getBytes());
        }

        return true;
    }

    /**
     * Gets the icons of this device.
     *
     * @return the icons of this device
     */
    public IconList getIconList() {
        IconList iconList = new IconList();
        Node iconListNode = getDeviceNode().getNode(IconList.ELEM_NAME);
        if (iconListNode == null) return iconList;
        int nNode = iconListNode.getNNodes();
        for (int n = 0; n < nNode; n++) {
            Node node = iconListNode.getNode(n);
            if (Icon.isIconNode(node) == false) continue;
            Icon icon = new Icon(node);
            if (icon.hasURL()) {
                String iconURL = icon.getURL();
                byte[] iconBytes = iconBytesMap.get(iconURL);
                if (iconBytes != null) {
                    icon.setBytes(iconBytes);
                }
            }
            iconList.add(icon);
        }
        return iconList;
    }

    /**
     * Gets the icon at a position in this device's icon list.
     *
     * @param n the position in the icon list
     * @return the icon at that position, or null if there is none
     */
    public Icon getIcon(int n) {
        IconList iconList = getIconList();
        if (n < 0 && (iconList.size() - 1) < n) return null;
        return iconList.getIcon(n);
    }

    /**
     * Finds the icon with the narrowest width.
     *
     * @return the narrowest icon, or null if this device has none
     */
    public Icon getSmallestIcon() {
        Icon smallestIcon = null;
        IconList iconList = getIconList();
        int iconCount = iconList.size();
        for (int n = 0; n < iconCount; n++) {
            Icon icon = iconList.getIcon(n);
            if (null == smallestIcon) {
                smallestIcon = icon;
                continue;
            }
            if (icon.getWidth() < smallestIcon.getWidth()) smallestIcon = icon;
        }

        return smallestIcon;
    }

    // //////////////////////////////////////////////
    // Notify
    // //////////////////////////////////////////////

    /**
     * Builds the URL this device's description is served from on a given host.
     *
     * @param host the address this device is reached on
     * @return the URL its description is served from on that address
     */
    public String getLocationURL(String host) {
        return HostInterface.getHostURL(host, getHTTPPort(), getDescriptionURI());
    }

    /**
     * Returns the notification header for the device.
     *
     * @return the NT value
     */
    private String getNotifyDeviceNT() {
        if (isRootDevice() == false) return getUDN();
        return UPNP_ROOTDEVICE;
    }

    /**
     * Returns the notification USN for the device.
     *
     * @return the USN value
     */
    private String getNotifyDeviceUSN() {
        if (isRootDevice() == false) return getUDN();
        return getUDN() + "::" + UPNP_ROOTDEVICE;
    }

    /**
     * Returns the notification header for the device type.
     *
     * @return the NT value
     */
    private String getNotifyDeviceTypeNT() {
        return getDeviceType();
    }

    /**
     * Returns the notification USN for the device type.
     *
     * @return the USN value
     */
    private String getNotifyDeviceTypeUSN() {
        return getUDN() + "::" + getDeviceType();
    }

    /**
     * Sleeps a random interval so SSDP replies from many devices do not collide.
     */
    public static final void notifyWait() {
        TimerUtil.waitRandom(DEFAULT_DISCOVERY_WAIT_TIME);
    }

    /**
     * Sends the alive advertisement for this device tree from one bind address.
     *
     * @param bindAddr the address to send the SSDP advertisement from
     */
    public void announce(String bindAddr) {
        String devLocation = getLocationURL(bindAddr);

        SSDPNotifySocket ssdpSock;
        // I2P
        try {
            ssdpSock = new SSDPNotifySocket(bindAddr);
        } catch (IOException ioe) {
            Debug.warning("Failed announce from " + bindAddr, ioe);
            return;
        }

        SSDPNotifyRequest ssdpReq = new SSDPNotifyRequest();
        ssdpReq.setServer(UPnP.getServerName());
        ssdpReq.setLeaseTime(getLeaseTime());
        ssdpReq.setLocation(devLocation);
        ssdpReq.setNTS(NTS.ALIVE);
        ssdpReq.setBootId(getBootId());

        // uuid:device-UUID(::upnp:rootdevice)*
        if (isRootDevice() == true) {
            String devNT = getNotifyDeviceNT();
            String devUSN = getNotifyDeviceUSN();
            ssdpReq.setNT(devNT);
            ssdpReq.setUSN(devUSN);
            ssdpSock.post(ssdpReq);

            String devUDN = getUDN();
            ssdpReq.setNT(devUDN);
            ssdpReq.setUSN(devUDN);
            ssdpSock.post(ssdpReq);
        }

        // uuid:device-UUID::urn:schemas-upnp-org:device:deviceType:v
        String devNT = getNotifyDeviceTypeNT();
        String devUSN = getNotifyDeviceTypeUSN();
        ssdpReq.setNT(devNT);
        ssdpReq.setUSN(devUSN);
        ssdpSock.post(ssdpReq);

        ssdpSock.close();

        ServiceList serviceList = getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            service.announce(bindAddr);
        }

        DeviceList childDeviceList = getDeviceList();
        int childDeviceCnt = childDeviceList.size();
        for (int n = 0; n < childDeviceCnt; n++) {
            Device childDevice = childDeviceList.getDevice(n);
            childDevice.announce(bindAddr);
        }
    }

    /**
     * Announces this device on every bound address after a discovery delay.
     */
    public void announce() {
        notifyWait();
        InetAddress[] binds = getDeviceData().getHTTPBindAddress();
        String[] bindAddresses;
        if (binds != null) {
            bindAddresses = new String[binds.length];
            for (int i = 0; i < binds.length; i++) {
                bindAddresses[i] = binds[i].getHostAddress();
            }
        } else {
            int nHostAddrs = HostInterface.getNHostAddresses();
            bindAddresses = new String[nHostAddrs];
            for (int n = 0; n < nHostAddrs; n++) {
                bindAddresses[n] = HostInterface.getHostAddress(n);
            }
        }
        for (int j = 0; j < bindAddresses.length; j++) {
            if (bindAddresses[j] == null || bindAddresses[j].length() == 0) continue;
            int ssdpCount = getSSDPAnnounceCount();
            for (int i = 0; i < ssdpCount; i++) announce(bindAddresses[j]);
        }
    }

    /**
     * Sends the byebye for this device tree from one bind address; a no-op in this build.
     *
     * @param bindAddr the address to send the SSDP byebye from
     */
    public void byebye(String bindAddr) {
        SSDPNotifySocket ssdpSock;
        // I2P
        try {
            ssdpSock = new SSDPNotifySocket(bindAddr);
        } catch (IOException ioe) {
            Debug.warning("Failed byebye from " + bindAddr, ioe);
            return;
        }

        SSDPNotifyRequest ssdpReq = new SSDPNotifyRequest();
        ssdpReq.setNTS(NTS.BYEBYE);

        // uuid:device-UUID(::upnp:rootdevice)*
        if (isRootDevice() == true) {
            String devNT = getNotifyDeviceNT();
            String devUSN = getNotifyDeviceUSN();
            ssdpReq.setNT(devNT);
            ssdpReq.setUSN(devUSN);
            ssdpSock.post(ssdpReq);
        }

        // uuid:device-UUID::urn:schemas-upnp-org:device:deviceType:v
        String devNT = getNotifyDeviceTypeNT();
        String devUSN = getNotifyDeviceTypeUSN();
        ssdpReq.setNT(devNT);
        ssdpReq.setUSN(devUSN);
        ssdpSock.post(ssdpReq);

        ssdpSock.close();

        ServiceList serviceList = getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            service.byebye(bindAddr);
        }

        DeviceList childDeviceList = getDeviceList();
        int childDeviceCnt = childDeviceList.size();
        for (int n = 0; n < childDeviceCnt; n++) {
            Device childDevice = childDeviceList.getDevice(n);
            childDevice.byebye(bindAddr);
        }
    }

    /**
     * Sends the byebye from every bound address; a no-op in this build.
     */
    public void byebye() {

        InetAddress[] binds = getDeviceData().getHTTPBindAddress();
        String[] bindAddresses;
        if (binds != null) {
            bindAddresses = new String[binds.length];
            for (int i = 0; i < binds.length; i++) {
                bindAddresses[i] = binds[i].getHostAddress();
            }
        } else {
            int nHostAddrs = HostInterface.getNHostAddresses();
            bindAddresses = new String[nHostAddrs];
            for (int n = 0; n < nHostAddrs; n++) {
                bindAddresses[n] = HostInterface.getHostAddress(n);
            }
        }

        for (int j = 0; j < bindAddresses.length; j++) {
            if (bindAddresses[j] == null || bindAddresses[j].length() <= 0) continue;
            int ssdpCount = getSSDPAnnounceCount();
            for (int i = 0; i < ssdpCount; i++) byebye(bindAddresses[j]);
        }
    }

    // //////////////////////////////////////////////
    // Search
    // //////////////////////////////////////////////

    /** Shared calendar for formatting dates. */
    private static Calendar cal = Calendar.getInstance();

    /**
     * Sends a search response naming this device's root description.
     *
     * @param ssdpPacket the search request being answered
     * @param st the search target being answered
     * @param usn the unique service name naming this device
     * @return true if the response was sent
     */
    public boolean postSearchResponse(SSDPPacket ssdpPacket, String st, String usn) {
        String localAddr = ssdpPacket.getLocalAddress();
        Device rootDev = getRootDevice();
        String rootDevLocation = rootDev.getLocationURL(localAddr);

        SSDPSearchResponse ssdpRes = new SSDPSearchResponse();
        ssdpRes.setLeaseTime(getLeaseTime());
        ssdpRes.setDate(cal);
        ssdpRes.setST(st);
        ssdpRes.setUSN(usn);
        ssdpRes.setLocation(rootDevLocation);
        ssdpRes.setBootId(getBootId());
        ssdpRes.setMYNAME(getFriendlyName());

        int mx = ssdpPacket.getMX();
        TimerUtil.waitRandom(mx * 1000);

        String remoteAddr = ssdpPacket.getRemoteAddress();
        int remotePort = ssdpPacket.getRemotePort();
        SSDPSearchResponseSocket ssdpResSock = new SSDPSearchResponseSocket();
        if (Debug.isOn() == true) ssdpRes.print();
        int ssdpCount = getSSDPAnnounceCount();
        for (int i = 0; i < ssdpCount; i++) ssdpResSock.post(remoteAddr, remotePort, ssdpRes);

        return true;
    }

    /**
     * Answers a search request for every device in this tree that matches it.
     *
     * @param ssdpPacket the search request to answer
     */
    public void deviceSearchResponse(SSDPPacket ssdpPacket) {
        String ssdpST = ssdpPacket.getST();

        if (ssdpST == null) return;

        boolean isRootDevice = isRootDevice();

        String devUSN = getUDN();
        if (isRootDevice == true) devUSN += "::" + USN.ROOTDEVICE;

        if (ST.isAllDevice(ssdpST) == true) {
            String devNT = getNotifyDeviceNT();
            int repeatCnt = (isRootDevice == true) ? 3 : 2;
            for (int n = 0; n < repeatCnt; n++) postSearchResponse(ssdpPacket, devNT, devUSN);
        } else if (ST.isRootDevice(ssdpST) == true) {
            if (isRootDevice == true) postSearchResponse(ssdpPacket, ST.ROOT_DEVICE, devUSN);
        } else if (ST.isUUIDDevice(ssdpST) == true) {
            String devUDN = getUDN();
            if (ssdpST.equals(devUDN) == true) postSearchResponse(ssdpPacket, devUDN, devUSN);
        } else if (ST.isURNDevice(ssdpST) == true) {
            String devType = getDeviceType();
            if (ssdpST.equals(devType) == true) {
                devUSN = getUDN() + "::" + devType;
                postSearchResponse(ssdpPacket, devType, devUSN);
            }
        }

        ServiceList serviceList = getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            service.serviceSearchResponse(ssdpPacket);
        }

        DeviceList childDeviceList = getDeviceList();
        int childDeviceCnt = childDeviceList.size();
        for (int n = 0; n < childDeviceCnt; n++) {
            Device childDevice = childDeviceList.getDevice(n);
            childDevice.deviceSearchResponse(ssdpPacket);
        }
    }

    /**
     * Handles an incoming search request by answering it.
     */
    public void deviceSearchReceived(SSDPPacket ssdpPacket) {
        deviceSearchResponse(ssdpPacket);
    }

    // //////////////////////////////////////////////
    // HTTP Server
    // //////////////////////////////////////////////

    /**
     * Sets the port the HTTP server binds to.
     *
     * @param port the port the HTTP server binds to
     */
    public void setHTTPPort(int port) {
        getDeviceData().setHTTPPort(port);
    }

    /**
     * Gets the port the HTTP server binds to.
     *
     * @return the port the HTTP server binds to
     */
    public int getHTTPPort() {
        return getDeviceData().getHTTPPort();
    }

    /**
     * Sets the addresses the HTTP server binds to.
     *
     * @param inets the addresses to bind the HTTP server to, or null for the default
     */
    public void setHTTPBindAddress(InetAddress[] inets) {
        this.getDeviceData().setHTTPBindAddress(inets);
    }

    /**
     * Gets the addresses the HTTP server is bound to.
     *
     * @return the addresses the HTTP server is bound to
     */
    public InetAddress[] getHTTPBindAddress() {
        return this.getDeviceData().getHTTPBindAddress();
    }

    /**
     * Get the IPv4 address SSDP multicasts to.
     *
     * @return SSDPIPv4MulticastAddress
     * @since 1.8
     */
    public String getSSDPIPv4MulticastAddress() {
        return this.getDeviceData().getMulticastIPv4Address();
    }

    /**
     * Set the IPv4 address SSDP multicasts to.
     *
     * @param ip the IPv4 multicast address
     * @since 1.8
     */
    public void getSSDPIPv4MulticastAddress(String ip) {
        this.getDeviceData().setMulticastIPv4Address(ip);
    }

    /**
     * Get the IPv6 address SSDP multicasts to.
     *
     * @return SSDPIPv6MulticastAddress
     * @since 1.8
     */
    public String getSSDPIPv6MulticastAddress() {
        return this.getDeviceData().getMulticastIPv6Address();
    }

    /**
     * Set the IPv6 address SSDP multicasts to.
     *
     * @param ip the IPv6 multicast address
     * @since 1.8
     */
    public void getSSDPIPv6MulticastAddress(String ip) {
        this.getDeviceData().setMulticastIPv6Address(ip);
    }

    /**
     * Routes an incoming HTTP request to the presentation, GET, POST or event handler.
     */
    public void httpRequestRecieved(HTTPRequest httpReq) {
        if (Debug.isOn() == true) httpReq.print();

        if (hasPresentationListener() && isPresentationRequest(httpReq)) {
            PresentationListener listener = getPresentationListener();
            listener.httpRequestRecieved(httpReq);
            return;
        }

        if (httpReq.isGetRequest() == true || httpReq.isHeadRequest() == true) {
            httpGetRequestRecieved(httpReq);
            return;
        }
        if (httpReq.isPostRequest() == true) {
            httpPostRequestRecieved(httpReq);
            return;
        }

        if (httpReq.isSubscribeRequest() == true || httpReq.isUnsubscribeRequest() == true) {
            SubscriptionRequest subReq = new SubscriptionRequest(httpReq);
            deviceEventSubscriptionRecieved(subReq);
            return;
        }

        httpReq.returnBadRequest();
    }

    /**
     * Builds the description document bytes for a host.
     *
     * @param host the host to embed
     * @return the description bytes
     */
    private synchronized byte[] getDescriptionData(String host) {
        if (isNMPRMode() == false) updateURLBase(host);
        Node rootNode = getRootNode();
        if (rootNode == null) return new byte[0];
        String desc = new String();
        desc += UPnP.XML_DECLARATION;
        desc += "\n";
        desc += rootNode.toString();
        return desc.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Handles a GET request to the device's server.
     *
     * @param httpReq the request received
     */
    private void httpGetRequestRecieved(HTTPRequest httpReq) {
        String uri = httpReq.getURI();
        Debug.message("httpGetRequestRecieved = " + uri);
        if (uri == null) {
            httpReq.returnBadRequest();
            return;
        }

        Device embDev;
        Service embService;

        byte[] fileByte = new byte[0];
        String contentType = null;
        String contentLanguage = null;

        if (isDescriptionURI(uri) == true) {
            String localAddr = httpReq.getLocalAddress();
            if ((localAddr == null) || (localAddr.length() <= 0))
                localAddr = HostInterface.getInterface();
            contentType = XML.DEFAULT_CONTENT_TYPE;
            contentLanguage = XML.DEFAULT_CONTENT_LANGUAGE;
            fileByte = getDescriptionData(localAddr);
        } else if ((embDev = getDeviceByDescriptionURI(uri)) != null) {
            String localAddr = httpReq.getLocalAddress();
            contentType = XML.DEFAULT_CONTENT_TYPE;
            contentLanguage = XML.DEFAULT_CONTENT_LANGUAGE;
            fileByte = embDev.getDescriptionData(localAddr);
        } else if ((embService = getServiceBySCPDURL(uri)) != null) {
            contentType = XML.DEFAULT_CONTENT_TYPE;
            contentLanguage = XML.DEFAULT_CONTENT_LANGUAGE;
            fileByte = embService.getSCPDData();
        } else if (isIconBytesURI(uri) == true) {
            Icon devIcon = getIconByURI(uri);
            if (devIcon != null) {
                contentType = devIcon.getMimeType();
                fileByte = devIcon.getBytes();
            }
        } else {
            httpReq.returnBadRequest();
            return;
        }

        HTTPResponse httpRes = new HTTPResponse();
        httpRes.setStatusCode(HTTPStatus.OK);
        if (contentType != null) {
            httpRes.setContentType(contentType);
        }
        if (contentLanguage != null) {
            // FIXME Check ACCEPT-LANGUAGE header in client request, and set a
            // suitable code.
            httpRes.setContentLanguage(contentLanguage);
        }
        httpRes.setContent(fileByte);

        httpReq.post(httpRes);
    }

    /**
     * Handles a POST request to the device's server.
     *
     * @param httpReq the request received
     */
    private void httpPostRequestRecieved(HTTPRequest httpReq) {
        if (httpReq.isSOAPAction() == true) {
            // SOAPRequest soapReq = new SOAPRequest(httpReq);
            soapActionRecieved(httpReq);
            return;
        }
        httpReq.returnBadRequest();
    }

    // //////////////////////////////////////////////
    // SOAP
    // //////////////////////////////////////////////

    /**
     * Answers a SOAP request with an unknown action.
     *
     * @param soapReq the request received
     */
    private void soapBadActionRecieved(HTTPRequest soapReq) {
        SOAPResponse soapRes = new SOAPResponse();
        soapRes.setStatusCode(HTTPStatus.BAD_REQUEST);
        soapReq.post(soapRes);
    }

    /**
     * Handles a SOAP action request.
     *
     * @param soapReq the request received
     */
    private void soapActionRecieved(HTTPRequest soapReq) {
        String uri = soapReq.getURI();
        Service ctlService = getServiceByControlURL(uri);
        if (ctlService != null) {
            ActionRequest crlReq = new ActionRequest(soapReq);
            deviceControlRequestRecieved(crlReq, ctlService);
            return;
        }
        soapBadActionRecieved(soapReq);
    }

    // //////////////////////////////////////////////
    // controlAction
    // //////////////////////////////////////////////

    /**
     * Dispatches a control request to the right service.
     *
     * @param ctlReq the control request
     * @param service the target service
     */
    private void deviceControlRequestRecieved(ControlRequest ctlReq, Service service) {
        if (ctlReq.isQueryControl() == true)
            deviceQueryControlRecieved(new QueryRequest(ctlReq), service);
        else deviceActionControlRecieved(new ActionRequest(ctlReq), service);
    }

    /**
     * Answers a control request naming an invalid action.
     *
     * @param ctlReq the control request
     */
    private void invalidActionControlRecieved(ControlRequest ctlReq) {
        ControlResponse actRes = new ActionResponse();
        actRes.setFaultResponse(UPnPStatus.INVALID_ACTION);
        ctlReq.post(actRes);
    }

    /**
     * Answers a control request with invalid arguments.
     *
     * @param ctlReq the control request
     */
    private void invalidArgumentsControlRecieved(ControlRequest ctlReq) {
        ControlResponse actRes = new ActionResponse();
        actRes.setFaultResponse(UPnPStatus.INVALID_ARGS);
        ctlReq.post(actRes);
    }

    /**
     * Runs an action on the target service.
     *
     * @param ctlReq the action request
     * @param service the target service
     */
    private void deviceActionControlRecieved(ActionRequest ctlReq, Service service) {
        if (Debug.isOn() == true) ctlReq.print();

        String actionName = ctlReq.getActionName();
        Action action = service.getAction(actionName);
        if (action == null) {
            invalidActionControlRecieved(ctlReq);
            return;
        }
        ArgumentList actionArgList = action.getArgumentList();
        ArgumentList reqArgList = ctlReq.getArgumentList();
        try {
            actionArgList.setReqArgs(reqArgList);
        } catch (IllegalArgumentException ex) {
            invalidArgumentsControlRecieved(ctlReq);
            return;
        }
        if (action.performActionListener(ctlReq) == false) invalidActionControlRecieved(ctlReq);
    }

    /**
     * Answers a state variable query.
     *
     * @param ctlReq the query request
     * @param service the target service
     */
    private void deviceQueryControlRecieved(QueryRequest ctlReq, Service service) {
        if (Debug.isOn() == true) ctlReq.print();
        String varName = ctlReq.getVarName();
        if (service.hasStateVariable(varName) == false) {
            invalidActionControlRecieved(ctlReq);
            return;
        }
        StateVariable stateVar = getStateVariable(varName);
        if (stateVar.performQueryListener(ctlReq) == false) invalidActionControlRecieved(ctlReq);
    }

    // //////////////////////////////////////////////
    // eventSubscribe
    // //////////////////////////////////////////////

    /**
     * Answers a subscription request with an error code.
     *
     * @param subReq the subscription request
     * @param code the error code to return
     */
    private void upnpBadSubscriptionRecieved(SubscriptionRequest subReq, int code) {
        SubscriptionResponse subRes = new SubscriptionResponse();
        subRes.setErrorResponse(code);
        subReq.post(subRes);
    }

    /**
     * Dispatches an event subscription request.
     *
     * @param subReq the subscription request
     */
    private void deviceEventSubscriptionRecieved(SubscriptionRequest subReq) {
        String uri = subReq.getURI();
        Service service = getServiceByEventSubURL(uri);
        if (service == null) {
            subReq.returnBadRequest();
            return;
        }
        if (subReq.hasCallback() == false && subReq.hasSID() == false) {
            upnpBadSubscriptionRecieved(subReq, HTTPStatus.PRECONDITION_FAILED);
            return;
        }

        // UNSUBSCRIBE
        if (subReq.isUnsubscribeRequest() == true) {
            deviceEventUnsubscriptionRecieved(service, subReq);
            return;
        }

        // SUBSCRIBE (NEW)
        if (subReq.hasCallback() == true) {
            deviceEventNewSubscriptionRecieved(service, subReq);
            return;
        }

        // SUBSCRIBE (RENEW)
        if (subReq.hasSID() == true) {
            deviceEventRenewSubscriptionRecieved(service, subReq);
            return;
        }

        upnpBadSubscriptionRecieved(subReq, HTTPStatus.PRECONDITION_FAILED);
    }

    /**
     * Creates a new event subscription.
     *
     * @param service the subscribed service
     * @param subReq the subscription request
     */
    private void deviceEventNewSubscriptionRecieved(Service service, SubscriptionRequest subReq) {
        String callback = subReq.getCallback();
        try {
            new URL(callback);
        } catch (Exception e) {
            upnpBadSubscriptionRecieved(subReq, HTTPStatus.PRECONDITION_FAILED);
            return;
        }

        long timeOut = subReq.getTimeout();
        String sid = Subscription.createSID();

        Subscriber sub = new Subscriber();
        sub.setDeliveryURL(callback);
        sub.setTimeOut(timeOut);
        sub.setSID(sid);
        service.addSubscriber(sub);

        SubscriptionResponse subRes = new SubscriptionResponse();
        subRes.setStatusCode(HTTPStatus.OK);
        subRes.setSID(sid);
        subRes.setTimeout(timeOut);
        if (Debug.isOn() == true) subRes.print();
        subReq.post(subRes);

        if (Debug.isOn() == true) subRes.print();

        service.notifyAllStateVariables();
    }

    /**
     * Renews an existing event subscription.
     *
     * @param service the subscribed service
     * @param subReq the subscription request
     */
    private void deviceEventRenewSubscriptionRecieved(Service service, SubscriptionRequest subReq) {
        String sid = subReq.getSID();
        Subscriber sub = service.getSubscriber(sid);

        if (sub == null) {
            upnpBadSubscriptionRecieved(subReq, HTTPStatus.PRECONDITION_FAILED);
            return;
        }

        long timeOut = subReq.getTimeout();
        sub.setTimeOut(timeOut);
        sub.renew();

        SubscriptionResponse subRes = new SubscriptionResponse();
        subRes.setStatusCode(HTTPStatus.OK);
        subRes.setSID(sid);
        subRes.setTimeout(timeOut);
        subReq.post(subRes);

        if (Debug.isOn() == true) subRes.print();
    }

    /**
     * Cancels an event subscription.
     *
     * @param service the subscribed service
     * @param subReq the subscription request
     */
    private void deviceEventUnsubscriptionRecieved(Service service, SubscriptionRequest subReq) {
        String sid = subReq.getSID();
        Subscriber sub = service.getSubscriber(sid);

        if (sub == null) {
            upnpBadSubscriptionRecieved(subReq, HTTPStatus.PRECONDITION_FAILED);
            return;
        }

        service.removeSubscriber(sub);

        SubscriptionResponse subRes = new SubscriptionResponse();
        subRes.setStatusCode(HTTPStatus.OK);
        subReq.post(subRes);

        if (Debug.isOn() == true) subRes.print();
    }

    // //////////////////////////////////////////////
    // Thread
    // //////////////////////////////////////////////

    /**
     * Returns the running HTTP servers, starting them if needed.
     *
     * @return the server list
     */
    private HTTPServerList getHTTPServerList() {
        return getDeviceData().getHTTPServerList();
    }

    /**
     * Set the port the SSDP service binds to.
     *
     * @param port The port to use for binding the SSDP service
     */
    public void setSSDPPort(int port) {
        this.getDeviceData().setSSDPPort(port);
    }

    /**
     * Get the port the SSDP service binds to.
     *
     * @return The port to use for binding the SSDP service
     */
    public int getSSDPPort() {
        return this.getDeviceData().getSSDPPort();
    }

    /**
     * Set the addresses the SSDP service binds to.
     *
     * @param inets The IP that will be used for binding the SSDP service. Use <code>null</code> to
     *              get the default behavior
     */
    public void setSSDPBindAddress(InetAddress[] inets) {
        this.getDeviceData().setSSDPBindAddress(inets);
    }

    /**
     * Get the addresses the SSDP service binds to.
     *
     * @return inets The IP that will be used for binding the SSDP service. null means the default
     *         set by the class UPnP
     */
    public InetAddress[] getSSDPBindAddress() {
        return this.getDeviceData().getSSDPBindAddress();
    }

    /**
     * Set the IPv4 address SSDP multicasts to.
     *
     * @param ip The IPv4 address used for Multicast communication
     */
    public void setMulticastIPv4Address(String ip) {
        this.getDeviceData().setMulticastIPv4Address(ip);
    }

    /**
     * Get the IPv4 address SSDP multicasts to.
     *
     * @return The IPv4 address used for Multicast communication
     */
    public String getMulticastIPv4Address() {
        return this.getDeviceData().getMulticastIPv4Address();
    }

    /**
     * Set the IPv6 address SSDP multicasts to.
     *
     * @param ip The IPv address used for Multicast communication
     */
    public void setMulticastIPv6Address(String ip) {
        this.getDeviceData().setMulticastIPv6Address(ip);
    }

    /**
     * Get the IPv6 address SSDP multicasts to.
     *
     * @return The IPv address used for Multicast communication
     */
    public String getMulticastIPv6Address() {
        return this.getDeviceData().getMulticastIPv6Address();
    }

    /**
     * Returns the SSDP search sockets, starting them if needed.
     *
     * @return the socket list
     */
    private SSDPSearchSocketList getSSDPSearchSocketList() {
        return getDeviceData().getSSDPSearchSocketList();
    }

    /**
     * Sets the advertiser sending alive messages.
     *
     * @param adv the advertiser sending alive messages
     */
    private void setAdvertiser(Advertiser adv) {
        getDeviceData().setAdvertiser(adv);
    }

    /**
     * Returns the advertiser sending alive messages.
     *
     * @return the advertiser sending alive messages
     */
    private Advertiser getAdvertiser() {
        return getDeviceData().getAdvertiser();
    }

    /**
     * Starts the HTTP server and begins SSDP advertising for this device.
     *
     * @return true if the device was started
     */
    public boolean start() {
        stop(true);

        // //////////////////////////////////////
        // HTTP Server
        // //////////////////////////////////////

        int retryCnt = 0;
        int bindPort = getHTTPPort();
        HTTPServerList httpServerList = getHTTPServerList();
        while (httpServerList.open(bindPort) == false) {
            retryCnt++;
            if (UPnP.SERVER_RETRY_COUNT < retryCnt) return false;
            setHTTPPort(bindPort + 1);
            bindPort = getHTTPPort();
        }
        httpServerList.addRequestListener(this);
        httpServerList.start();

        // //////////////////////////////////////
        // SSDP Seach Socket
        // //////////////////////////////////////

        SSDPSearchSocketList ssdpSearchSockList = getSSDPSearchSocketList();
        if (ssdpSearchSockList.open() == false) return false;
        ssdpSearchSockList.addSearchListener(this);
        ssdpSearchSockList.start();

        // //////////////////////////////////////
        // BOOTID/CONFIGID.UPNP.ORG
        // //////////////////////////////////////

        updateBootId();
        updateConfigId();

        // //////////////////////////////////////
        // Announce
        // //////////////////////////////////////

        announce();

        // //////////////////////////////////////
        // Advertiser
        // //////////////////////////////////////

        Advertiser adv = new Advertiser(this);
        setAdvertiser(adv);
        adv.start();

        return true;
    }

    /**
     * Stops the device's servers and advertiser.
     *
     * @param doByeBye true to send byebye notifications
     * @return true on success
     */
    private boolean stop(boolean doByeBye) {
        if (doByeBye == true) byebye();

        HTTPServerList httpServerList = getHTTPServerList();
        httpServerList.stop();
        httpServerList.close();
        httpServerList.clear();

        SSDPSearchSocketList ssdpSearchSockList = getSSDPSearchSocketList();
        ssdpSearchSockList.stop();
        ssdpSearchSockList.close();
        ssdpSearchSockList.clear();

        Advertiser adv = getAdvertiser();
        if (adv != null) {
            adv.stop();
            setAdvertiser(null);
        }

        return true;
    }

    /**
     * Stops SSDP advertising and shuts down the HTTP server.
     *
     * @return true if the device was stopped
     */
    public boolean stop() {
        return stop(true);
    }

    /**
     * Tests whether this device's advertiser is running.
     *
     * @return true if the device is running
     */
    public boolean isRunning() {
        return (getAdvertiser() != null) ? true : false;
    }

    // //////////////////////////////////////////////
    // Interface Address
    // //////////////////////////////////////////////

    /**
     * Gets the local address this device was announced from.
     *
     * @return the address this device was discovered on, or an empty string
     */
    public String getInterfaceAddress() {
        SSDPPacket ssdpPacket = getSSDPPacket();
        if (ssdpPacket == null) return "";
        return ssdpPacket.getLocalAddress();
    }

    // //////////////////////////////////////////////
    // Acion/QueryListener
    // //////////////////////////////////////////////

    /**
     * Installs the action handler on every service of this device.
     *
     * @param listener the handler for incoming action requests
     */
    public void setActionListener(ActionListener listener) {
        ServiceList serviceList = getServiceList();
        int nServices = serviceList.size();
        for (int n = 0; n < nServices; n++) {
            Service service = serviceList.getService(n);
            service.setActionListener(listener);
        }
    }

    /**
     * Installs the query handler on every service of this device.
     *
     * @param listener the handler for incoming variable queries
     */
    public void setQueryListener(QueryListener listener) {
        ServiceList serviceList = getServiceList();
        int nServices = serviceList.size();
        for (int n = 0; n < nServices; n++) {
            Service service = serviceList.getService(n);
            service.setQueryListener(listener);
        }
    }

    // //////////////////////////////////////////////
    // Acion/QueryListener (includeSubDevices)
    // //////////////////////////////////////////////

    /**
     * Installs the action handler on every service, optionally descending into embedded devices.
     *
     * @param listener the handler for incoming action requests
     * @param includeSubDevices true to set the handler on embedded devices too
     */
    public void setActionListener(ActionListener listener, boolean includeSubDevices) {
        setActionListener(listener);
        if (includeSubDevices == true) {
            DeviceList devList = getDeviceList();
            int devCnt = devList.size();
            for (int n = 0; n < devCnt; n++) {
                Device dev = devList.getDevice(n);
                dev.setActionListener(listener, true);
            }
        }
    }

    /**
     * Installs the query handler on every service, optionally descending into embedded devices.
     *
     * @param listener the handler for incoming variable queries
     * @param includeSubDevices true to set the handler on embedded devices too
     */
    public void setQueryListener(QueryListener listener, boolean includeSubDevices) {
        setQueryListener(listener);
        if (includeSubDevices == true) {
            DeviceList devList = getDeviceList();
            int devCnt = devList.size();
            for (int n = 0; n < devCnt; n++) {
                Device dev = devList.getDevice(n);
                dev.setQueryListener(listener, true);
            }
        }
    }

    // //////////////////////////////////////////////
    // userData
    // //////////////////////////////////////////////

    /** Arbitrary data attached by the application. */
    private Object userData = null;

    /**
     * Attaches caller-defined data to this device's node.
     *
     * @param data any object; only read back by {@link #getUserData}
     */
    public void setUserData(Object data) {
        userData = data;
    }

    /**
     * Gets the caller-defined data attached to this device's node.
     *
     * @return whatever was passed to {@link #setUserData(Object)}, or null if
     *         none was
     */
    public Object getUserData() {
        return userData;
    }

    // //////////////////////////////////////////////
    // output
    // //////////////////////////////////////////////

}
