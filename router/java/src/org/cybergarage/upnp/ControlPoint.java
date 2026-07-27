/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.upnp;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.Addresses;
import org.cybergarage.http.HTTPRequest;
import org.cybergarage.http.HTTPRequestListener;
import org.cybergarage.http.HTTPServerList;
import org.cybergarage.net.HostInterface;
import org.cybergarage.upnp.control.RenewSubscriber;
import org.cybergarage.upnp.device.DeviceChangeListener;
import org.cybergarage.upnp.device.Disposer;
import org.cybergarage.upnp.device.NotifyListener;
import org.cybergarage.upnp.device.ST;
import org.cybergarage.upnp.device.SearchResponseListener;
import org.cybergarage.upnp.device.USN;
import org.cybergarage.upnp.event.EventListener;
import org.cybergarage.upnp.event.NotifyRequest;
import org.cybergarage.upnp.event.Property;
import org.cybergarage.upnp.event.PropertyList;
import org.cybergarage.upnp.event.Subscription;
import org.cybergarage.upnp.event.SubscriptionRequest;
import org.cybergarage.upnp.event.SubscriptionResponse;
import org.cybergarage.upnp.ssdp.SSDP;
import org.cybergarage.upnp.ssdp.SSDPNotifySocketList;
import org.cybergarage.upnp.ssdp.SSDPPacket;
import org.cybergarage.upnp.ssdp.SSDPSearchRequest;
import org.cybergarage.upnp.ssdp.SSDPSearchResponseSocketList;
import org.cybergarage.util.Debug;
import org.cybergarage.util.ListenerList;
import org.cybergarage.util.Mutex;
import org.cybergarage.xml.Node;
import org.cybergarage.xml.NodeList;
import org.cybergarage.xml.Parser;
import org.cybergarage.xml.ParserException;

/**
 * Main UPnP control point implementation for device discovery and control.
 *
 * <p>This class implements HTTPRequestListener to provide comprehensive UPnP control point
 * functionality including device discovery, service enumeration, action invocation, and event
 * subscription management. It serves as the primary interface for UPnP control applications.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>SSDP device discovery and advertisement monitoring
 *   <li>HTTP server for receiving control requests
 *   <li>Device and service lifecycle management
 *   <li>Event subscription and notification handling
 *   <li>SOAP action invocation and response processing
 *   <li>Multi-homed network interface support
 *   <li>Expired device cleanup and monitoring
 * </ul>
 *
 * <p>This class is used by UPnP control applications to discover, control, and monitor UPnP devices
 * on the network, providing a complete framework for building UPnP control point functionality.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class ControlPoint implements HTTPRequestListener {
    private static final int DEFAULT_EVENTSUB_PORT = 8058;
    private static final int DEFAULT_SSDP_PORT = 8008;
    private static final int DEFAULT_EXPIRED_DEVICE_MONITORING_INTERVAL = 60;

    private static final String DEFAULT_EVENTSUB_URI = "/evetSub";

    // I2P
    private static final boolean ALLOW_IPV6_LOCATION = true;

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    private SSDPNotifySocketList ssdpNotifySocketList;
    private SSDPSearchResponseSocketList ssdpSearchResponseSocketList;

    /**
     *  Get the SSDP notify socket list.
     *
     *  @return the SSDP notify socket list
     */
    protected SSDPNotifySocketList getSSDPNotifySocketList() {
        return ssdpNotifySocketList;
    }

    /**
     *  Get the SSDP search response socket list.
     *
     *  @return the SSDP search response socket list
     */
    protected SSDPSearchResponseSocketList getSSDPSearchResponseSocketList() {
        return ssdpSearchResponseSocketList;
    }

    ////////////////////////////////////////////////
    //	Initialize
    ////////////////////////////////////////////////

    static {
        UPnP.initialize();
    }

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     *  Create a control point with specified ports and network bindings.
     *
     *  @param ssdpPort the SSDP port
     *  @param httpPort the HTTP port
     *  @param binds the network interfaces to bind to, or null for all
     */
    public ControlPoint(int ssdpPort, int httpPort, InetAddress[] binds) {
        ssdpNotifySocketList = new SSDPNotifySocketList(binds);
        ssdpSearchResponseSocketList = new SSDPSearchResponseSocketList(binds);

        setSSDPPort(ssdpPort);
        setHTTPPort(httpPort);

        setDeviceDisposer(null);
        setExpiredDeviceMonitoringInterval(DEFAULT_EXPIRED_DEVICE_MONITORING_INTERVAL);

        setRenewSubscriber(null);

        setNMPRMode(false);
        setRenewSubscriber(null);
    }

    /**
     *  Create a control point with specified SSDP and HTTP ports.
     *
     *  @param ssdpPort the SSDP port
     *  @param httpPort the HTTP port
     */
    public ControlPoint(int ssdpPort, int httpPort) {
        this(ssdpPort, httpPort, null);
    }

    /** Default constructor using default ports */
    public ControlPoint() {
        this(DEFAULT_SSDP_PORT, DEFAULT_EVENTSUB_PORT);
    }

    public void finalize() {
        stop();
    }

    ////////////////////////////////////////////////
    // Mutex
    ////////////////////////////////////////////////

    private Mutex mutex = new Mutex();

    /** Lock the mutex */
    public void lock() {
        mutex.lock();
    }

    /** Unlock the mutex */
    public void unlock() {
        mutex.unlock();
    }

    ////////////////////////////////////////////////
    //	Port (SSDP)
    ////////////////////////////////////////////////

    private int ssdpPort = 0;

    /** Get the SSDP port. @return the SSDP port */
    public int getSSDPPort() {
        return ssdpPort;
    }

    /** Set the SSDP port. @param port the SSDP port */
    public void setSSDPPort(int port) {
        ssdpPort = port;
    }

    ////////////////////////////////////////////////
    //	Port (EventSub)
    ////////////////////////////////////////////////

    private int httpPort = 0;

    /** Get the HTTP port. @return the HTTP port */
    public int getHTTPPort() {
        return httpPort;
    }

    /** Set the HTTP port. @param port the HTTP port */
    public void setHTTPPort(int port) {
        httpPort = port;
    }

    ////////////////////////////////////////////////
    //	NMPR
    ////////////////////////////////////////////////

    private boolean nmprMode;

    /** Set the NMPR mode flag. @param flag the NMPR mode flag */
    public void setNMPRMode(boolean flag) {
        nmprMode = flag;
    }

    /** Check if NMPR mode is enabled. @return true if NMPR mode is enabled */
    public boolean isNMPRMode() {
        return nmprMode;
    }

    ////////////////////////////////////////////////
    //	Device List
    ////////////////////////////////////////////////

    private NodeList devNodeList = new NodeList();

    private void addDevice(Node rootNode) {
        devNodeList.add(rootNode);
    }

    private synchronized void addDevice(SSDPPacket ssdpPacket) {
        if (ssdpPacket.isRootDevice() == false) return;

        String usn = ssdpPacket.getUSN();

        String location = ssdpPacket.getLocation();
        try {
            URL locationUrl = new URL(location);
            // I2P
            // Roku fake json port, the real UPnP port is 8060
            if (locationUrl.getPort() == 9080) {
                String lcusn = usn.toLowerCase(Locale.US);
                if (lcusn.contains("rku") || lcusn.contains("roku")) {
                    Debug.warning("Ignoring Roku at " + location);
                    return;
                }
            }
            // I2P
            // We duplicate all the checks in Parser.parse() because they
            // are bypassed for a known device.
            // Devices may send two SSDP responses, one with an IPv4 location
            // and one with an IPv6 location.
            // Do these check BEFORE we call dev.setSSDPPacket() so we don't
            // overwrite the SSDPPacket in DeviceData.
            // TODO handle multiple locations in DeviceData.
            String host = locationUrl.getHost();
            if (host == null) {
                Debug.warning("Ignoring device with bad URL at " + location);
                return;
            }
            if (host.startsWith("127.")) {
                Debug.warning("Ignoring localhost device at " + location);
                return;
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                if (!ALLOW_IPV6_LOCATION) {
                    Debug.warning("Ignoring IPv6 device at " + location);
                    return;
                }
                // fixup for valid checks below
                host = host.substring(1, host.length() - 1);
            }
            if (!"http".equals(locationUrl.getProtocol())) {
                Debug.warning("Ignoring non-http device at " + location);
                return;
            }
            if (!Addresses.isIPv4Address(host)
                    && (!ALLOW_IPV6_LOCATION || !Addresses.isIPv6Address(host))) {
                Debug.warning("Ignoring non-IPv4 address at " + location);
                return;
            }
            byte[] ip = Addresses.getIP(host);
            if (ip == null) {
                Debug.warning("Ignoring bad IP at " + location);
                return;
            }
            if (TransportUtil.isPubliclyRoutable(ip, ALLOW_IPV6_LOCATION)) {
                Debug.warning("Ignoring public address at " + location);
                return;
            }
            String udn = USN.getUDN(usn);
            Device dev = getDevice(udn);
            if (dev != null) {
                Debug.message("Additional SSDP for " + udn + " at " + location);
                dev.setSSDPPacket(ssdpPacket);
                return;
            }

            Parser parser = UPnP.getXMLParser();
            Node rootNode = parser.parse(locationUrl);
            Device rootDev = getDevice(rootNode);
            if (rootDev == null) return;
            rootDev.setSSDPPacket(ssdpPacket);
            Debug.warning(
                    "Add root device at " + location,
                    new Exception("received on " + ssdpPacket.getLocalAddress()));
            addDevice(rootNode);

            // Thanks for Oliver Newell (2004/10/16)
            // After node is added, invoke the AddDeviceListener to notify high-level
            // control point application that a new device has been added. (The
            // control point application must implement the DeviceChangeListener interface
            // to receive the notifications)
            performAddDeviceListener(rootDev);
        } catch (MalformedURLException me) {
            Debug.warning("Bad location: " + location, me);
        } catch (ParserException pe) {
            Debug.warning("Error parsing data at location: " + location, pe);
        }
    }

    private Device getDevice(Node rootNode) {
        if (rootNode == null) return null;
        Node devNode = rootNode.getNode(Device.ELEM_NAME);
        if (devNode == null) return null;
        return new Device(rootNode, devNode);
    }

    /** Get the list of discovered devices. @return the list of discovered devices */
    public DeviceList getDeviceList() {
        DeviceList devList = new DeviceList();
        int nRoots = devNodeList.size();
        for (int n = 0; n < nRoots; n++) {
            // AIOOB was thrown from here, maybe would be better to
            // copy the list before traversal?
            Node rootNode;
            try {
                rootNode = devNodeList.getNode(n);
            } catch (ArrayIndexOutOfBoundsException aioob) {
                break;
            }
            Device dev = getDevice(rootNode);
            if (dev == null) continue;
            devList.add(dev);
        }
        return devList;
    }

    /**
     *  Get a device by its name or UDN.
     *
     *  @param name the device name or UDN
     *  @return the device, or null
     */
    public Device getDevice(String name) {
        int nRoots = devNodeList.size();
        for (int n = 0; n < nRoots; n++) {
            // AIOOB was thrown from here, maybe would be better to
            // copy the list before traversal?
            Node rootNode;
            try {
                rootNode = devNodeList.getNode(n);
            } catch (ArrayIndexOutOfBoundsException aioob) {
                break;
            }
            Device dev = getDevice(rootNode);
            if (dev == null) continue;
            if (dev.isDevice(name) == true) return dev;
            Device cdev = dev.getDevice(name);
            if (cdev != null) return cdev;
        }
        return null;
    }

    /**
     *  Check if a device with the given name or UDN exists.
     *
     *  @param name the device name or UDN
     *  @return true if a device with that name exists
     */
    public boolean hasDevice(String name) {
        return (getDevice(name) != null) ? true : false;
    }

    private void removeDevice(Node rootNode) {
        // Thanks for Oliver Newell (2004/10/16)
        // Invoke device removal listener prior to actual removal so Device node
        // remains valid for the duration of the listener (application may want
        // to access the node)
        Device dev = getDevice(rootNode);
        if (dev != null && dev.isRootDevice()) performRemoveDeviceListener(dev);

        devNodeList.remove(rootNode);
    }

    /** Remove a device from the device list. @param dev the device to remove */
    protected void removeDevice(Device dev) {
        if (dev == null) return;
        removeDevice(dev.getRootNode());
    }

    /** Remove a device by its name or UDN. @param name the device name to remove */
    protected void removeDevice(String name) {
        Device dev = getDevice(name);
        removeDevice(dev);
    }

    private void removeDevice(SSDPPacket packet) {
        if (packet.isByeBye() == false) return;
        String usn = packet.getUSN();
        String udn = USN.getUDN(usn);
        removeDevice(udn);
    }

    ////////////////////////////////////////////////
    //	Expired Device
    ////////////////////////////////////////////////

    private Disposer deviceDisposer;
    private long expiredDeviceMonitoringInterval;

    /** Remove all expired devices */
    public void removeExpiredDevices() {
        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        Device[] dev = new Device[devCnt];
        for (int n = 0; n < devCnt; n++) dev[n] = devList.getDevice(n);
        for (int n = 0; n < devCnt; n++) {
            if (dev[n].isExpired() == true) {
                Debug.message("Expired device = " + dev[n].getFriendlyName());
                removeDevice(dev[n]);
            }
        }
    }

    /** Set the expired device monitoring interval. @param interval the monitoring interval */
    public void setExpiredDeviceMonitoringInterval(long interval) {
        expiredDeviceMonitoringInterval = interval;
    }

    /** Get the expired device monitoring interval. @return the expired device monitoring interval */
    public long getExpiredDeviceMonitoringInterval() {
        return expiredDeviceMonitoringInterval;
    }

    /** Set the device disposer. @param disposer the device disposer */
    public void setDeviceDisposer(Disposer disposer) {
        deviceDisposer = disposer;
    }

    /** Get the device disposer. @return the device disposer */
    public Disposer getDeviceDisposer() {
        return deviceDisposer;
    }

    ////////////////////////////////////////////////
    //	Notify
    ////////////////////////////////////////////////

    private ListenerList deviceNotifyListenerList = new ListenerList();

    /** Add a notify listener. @param listener the notify listener to add */
    public void addNotifyListener(NotifyListener listener) {
        deviceNotifyListenerList.add(listener);
    }

    /** Remove a notify listener. @param listener the notify listener to remove */
    public void removeNotifyListener(NotifyListener listener) {
        deviceNotifyListenerList.remove(listener);
    }

    /** Notify all registered notify listeners of a received SSDP packet. @param ssdpPacket the received SSDP packet */
    public void performNotifyListener(SSDPPacket ssdpPacket) {
        int listenerSize = deviceNotifyListenerList.size();
        for (int n = 0; n < listenerSize; n++) {
            NotifyListener listener = (NotifyListener) deviceNotifyListenerList.get(n);
            try {
                listener.deviceNotifyReceived(ssdpPacket);
            } catch (Exception e) {
                Debug.warning("NotifyListener returned an error:", e);
            }
        }
    }

    ////////////////////////////////////////////////
    //	SearchResponse
    ////////////////////////////////////////////////

    private ListenerList deviceSearchResponseListenerList = new ListenerList();

    /** @param listener the search response listener to add */
    public void addSearchResponseListener(SearchResponseListener listener) {
        deviceSearchResponseListenerList.add(listener);
    }

    /** @param listener the search response listener to remove */
    public void removeSearchResponseListener(SearchResponseListener listener) {
        deviceSearchResponseListenerList.remove(listener);
    }

    /** @param ssdpPacket the received SSDP packet */
    public void performSearchResponseListener(SSDPPacket ssdpPacket) {
        int listenerSize = deviceSearchResponseListenerList.size();
        for (int n = 0; n < listenerSize; n++) {
            SearchResponseListener listener =
                    (SearchResponseListener) deviceSearchResponseListenerList.get(n);
            try {
                listener.deviceSearchResponseReceived(ssdpPacket);
            } catch (Exception e) {
                Debug.warning("SearchResponseListener returned an error:", e);
            }
        }
    }

    /////////////////////////////////////////////////////////////////////
    // Device status changes (device added or removed)
    // Applications that support the DeviceChangeListener interface are
    // notified immediately when a device is added to, or removed from,
    // the control point.
    /////////////////////////////////////////////////////////////////////

    ListenerList deviceChangeListenerList = new ListenerList();

    /** @param listener the device change listener to add */
    public void addDeviceChangeListener(DeviceChangeListener listener) {
        deviceChangeListenerList.add(listener);
    }

    /** @param listener the device change listener to remove */
    public void removeDeviceChangeListener(DeviceChangeListener listener) {
        deviceChangeListenerList.remove(listener);
    }

    /** @param dev the device that was added */
    public void performAddDeviceListener(Device dev) {
        int listenerSize = deviceChangeListenerList.size();
        for (int n = 0; n < listenerSize; n++) {
            DeviceChangeListener listener = (DeviceChangeListener) deviceChangeListenerList.get(n);
            listener.deviceAdded(dev);
        }
    }

    /** @param dev the device that was removed */
    public void performRemoveDeviceListener(Device dev) {
        int listenerSize = deviceChangeListenerList.size();
        for (int n = 0; n < listenerSize; n++) {
            DeviceChangeListener listener = (DeviceChangeListener) deviceChangeListenerList.get(n);
            listener.deviceRemoved(dev);
        }
    }

    ////////////////////////////////////////////////
    //	SSDPPacket
    ////////////////////////////////////////////////

    /**
     *  @param packet the received SSDP packet
     */
    public void notifyReceived(SSDPPacket packet) {
        if (packet.isRootDevice() == true) {
            if (packet.isAlive() == true) {
                addDevice(packet);
            } else if (packet.isByeBye() == true) {
                removeDevice(packet);
            }
        }
        performNotifyListener(packet);
    }

    /** @param packet the search response packet */
    public void searchResponseReceived(SSDPPacket packet) {
        if (packet.isRootDevice() == true) addDevice(packet);
        performSearchResponseListener(packet);
    }

    ////////////////////////////////////////////////
    //	M-SEARCH
    ////////////////////////////////////////////////

    private int searchMx = SSDP.DEFAULT_MSEARCH_MX;

    /** @return the search MX value */
    public int getSearchMx() {
        return searchMx;
    }

    /** @param mx the search MX value */
    public void setSearchMx(int mx) {
        searchMx = mx;
    }

    /**
     *  @param target the search target
     *  @param mx the maximum wait time
     */
    public void search(String target, int mx) {
        SSDPSearchRequest msReq = new SSDPSearchRequest(target, mx);
        SSDPSearchResponseSocketList ssdpSearchResponseSocketList =
                getSSDPSearchResponseSocketList();
        ssdpSearchResponseSocketList.post(msReq);
    }

    /** @param target the search target */
    public void search(String target) {
        search(target, SSDP.DEFAULT_MSEARCH_MX);
    }

    /** Search for all root devices */
    public void search() {
        search(ST.ROOT_DEVICE, SSDP.DEFAULT_MSEARCH_MX);
    }

    ////////////////////////////////////////////////
    //	EventSub HTTPServer
    ////////////////////////////////////////////////

    private HTTPServerList httpServerList = new HTTPServerList();

    /**
     *  I2P was private
     *
     *  @return the HTTP server list
     */
    protected HTTPServerList getHTTPServerList() {
        return httpServerList;
    }

    public void httpRequestRecieved(HTTPRequest httpReq) {
        if (Debug.isOn() == true) httpReq.print();

        // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (09/08/03)
        if (httpReq.isNotifyRequest() == true) {
            NotifyRequest notifyReq = new NotifyRequest(httpReq);
            String uuid = notifyReq.getSID();
            long seq = notifyReq.getSEQ();
            PropertyList props = notifyReq.getPropertyList();
            int propCnt = props.size();
            for (int n = 0; n < propCnt; n++) {
                Property prop = props.getProperty(n);
                String varName = prop.getName();
                String varValue = prop.getValue();
                performEventListener(uuid, seq, varName, varValue);
            }
            httpReq.returnOK();
            return;
        }

        httpReq.returnBadRequest();
    }

    ////////////////////////////////////////////////
    //	Event Listener
    ////////////////////////////////////////////////

    private ListenerList eventListenerList = new ListenerList();

    /** @param listener the event listener to add */
    public void addEventListener(EventListener listener) {
        eventListenerList.add(listener);
    }

    /** @param listener the event listener to remove */
    public void removeEventListener(EventListener listener) {
        eventListenerList.remove(listener);
    }

    /**
     *  @param uuid the subscription ID
     *  @param seq the sequence number
     *  @param name the property name
     *  @param value the property value
     */
    public void performEventListener(String uuid, long seq, String name, String value) {
        int listenerSize = eventListenerList.size();
        for (int n = 0; n < listenerSize; n++) {
            EventListener listener = (EventListener) eventListenerList.get(n);
            listener.eventNotifyReceived(uuid, seq, name, value);
        }
    }

    ////////////////////////////////////////////////
    //	Subscription
    ////////////////////////////////////////////////

    private String eventSubURI = DEFAULT_EVENTSUB_URI;

    /** @return the event subscription URI */
    public String getEventSubURI() {
        return eventSubURI;
    }

    /** @param url the event subscription URI */
    public void setEventSubURI(String url) {
        eventSubURI = url;
    }

    private String getEventSubCallbackURL(String host) {
        return HostInterface.getHostURL(host, getHTTPPort(), getEventSubURI());
    }

    /**
     *  @param service the service to subscribe to
     *  @param timeout the subscription timeout
     *  @return true if successful
     */
    public boolean subscribe(Service service, long timeout) {
        if (service.isSubscribed() == true) {
            String sid = service.getSID();
            return subscribe(service, sid, timeout);
        }

        Device rootDev = service.getRootDevice();
        if (rootDev == null) return false;
        String ifAddress = rootDev.getInterfaceAddress();
        SubscriptionRequest subReq = new SubscriptionRequest();
        subReq.setSubscribeRequest(service, getEventSubCallbackURL(ifAddress), timeout);
        SubscriptionResponse subRes = subReq.post();
        if (subRes.isSuccessful() == true) {
            service.setSID(subRes.getSID());
            service.setTimeout(subRes.getTimeout());
            return true;
        }
        service.clearSID();
        return false;
    }

    /** @param service the service to subscribe to */
    public boolean subscribe(Service service) {
        return subscribe(service, Subscription.INFINITE_VALUE);
    }

    /**
     *  @param service the service
     *  @param uuid the subscription ID
     *  @param timeout the timeout
     *  @return true if successful
     */
    public boolean subscribe(Service service, String uuid, long timeout) {
        SubscriptionRequest subReq = new SubscriptionRequest();
        subReq.setRenewRequest(service, uuid, timeout);
        if (Debug.isOn() == true) subReq.print();
        SubscriptionResponse subRes = subReq.post();
        if (Debug.isOn() == true) subRes.print();
        if (subRes.isSuccessful() == true) {
            service.setSID(subRes.getSID());
            service.setTimeout(subRes.getTimeout());
            return true;
        }
        service.clearSID();
        return false;
    }

    /**
     *  @param service the service
     *  @param uuid the subscription ID
     *  @return true if successful
     */
    public boolean subscribe(Service service, String uuid) {
        return subscribe(service, uuid, Subscription.INFINITE_VALUE);
    }

    /**
     *  @param service the service
     *  @return true if subscribed
     */
    public boolean isSubscribed(Service service) {
        if (service == null) return false;
        return service.isSubscribed();
    }

    /**
     *  @param service the service
     *  @return true if successful
     */
    public boolean unsubscribe(Service service) {
        SubscriptionRequest subReq = new SubscriptionRequest();
        subReq.setUnsubscribeRequest(service);
        SubscriptionResponse subRes = subReq.post();
        if (subRes.isSuccessful() == true) {
            service.clearSID();
            return true;
        }
        return false;
    }

    /** @param device the device to unsubscribe */
    public void unsubscribe(Device device) {
        ServiceList serviceList = device.getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            if (service.hasSID() == true) unsubscribe(service);
        }

        DeviceList childDevList = device.getDeviceList();
        int childDevCnt = childDevList.size();
        for (int n = 0; n < childDevCnt; n++) {
            Device cdev = childDevList.getDevice(n);
            unsubscribe(cdev);
        }
    }

    /** Unsubscribe from all services */
    public void unsubscribe() {
        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            unsubscribe(dev);
        }
    }

    ////////////////////////////////////////////////
    //	getSubscriberService
    ////////////////////////////////////////////////

    /**
     *  @param uuid the subscription ID
     *  @return the service with that subscription, or null
     */
    public Service getSubscriberService(String uuid) {
        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            Service service = dev.getSubscriberService(uuid);
            if (service != null) return service;
        }
        return null;
    }

    ////////////////////////////////////////////////
    //	getSubscriberService
    ////////////////////////////////////////////////

    /**
     *  @param dev the device whose subscriptions to renew
     *  @param timeout the subscription timeout
     */
    public void renewSubscriberService(Device dev, long timeout) {
        ServiceList serviceList = dev.getServiceList();
        int serviceCnt = serviceList.size();
        for (int n = 0; n < serviceCnt; n++) {
            Service service = serviceList.getService(n);
            if (service.isSubscribed() == false) continue;
            String sid = service.getSID();
            boolean isRenewed = subscribe(service, sid, timeout);
            if (isRenewed == false) subscribe(service, timeout);
        }

        DeviceList cdevList = dev.getDeviceList();
        int cdevCnt = cdevList.size();
        for (int n = 0; n < cdevCnt; n++) {
            Device cdev = cdevList.getDevice(n);
            renewSubscriberService(cdev, timeout);
        }
    }

    /** @param timeout the subscription timeout */
    public void renewSubscriberService(long timeout) {
        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            renewSubscriberService(dev, timeout);
        }
    }

    /** Renew all subscriber services with infinite timeout */
    public void renewSubscriberService() {
        renewSubscriberService(Subscription.INFINITE_VALUE);
    }

    ////////////////////////////////////////////////
    //	Subscriber
    ////////////////////////////////////////////////

    private RenewSubscriber renewSubscriber;

    /** @param sub the renew subscriber */
    public void setRenewSubscriber(RenewSubscriber sub) {
        renewSubscriber = sub;
    }

    /** @return the renew subscriber */
    public RenewSubscriber getRenewSubscriber() {
        return renewSubscriber;
    }

    ////////////////////////////////////////////////
    //	run
    ////////////////////////////////////////////////

    /**
     *  @param target the search target
     *  @param mx the maximum wait time for responses
     *  @return true if started successfully
     */
    public boolean start(String target, int mx) {
        stop();

        ////////////////////////////////////////
        // HTTP Server
        ////////////////////////////////////////

        int retryCnt = 0;
        int bindPort = getHTTPPort();
        HTTPServerList httpServerList = getHTTPServerList();
        while (httpServerList.open(bindPort) == false) {
            retryCnt++;
            if (UPnP.SERVER_RETRY_COUNT < retryCnt) {
                Debug.warning("Failed to open HTTP event listener port " + bindPort);
                // I2P do we really need this, or can we just break ?
                return false;
            }
            // I2P go down not up so we don't run into other I2P things
            setHTTPPort(bindPort - 1);
            bindPort = getHTTPPort();
        }
        httpServerList.addRequestListener(this);
        httpServerList.start();

        ////////////////////////////////////////
        // Notify Socket
        ////////////////////////////////////////

        SSDPNotifySocketList ssdpNotifySocketList = getSSDPNotifySocketList();
        if (ssdpNotifySocketList.open() == false) {
            Debug.warning("Failed to open SSDP notify port 1900");
            return false;
        }
        ssdpNotifySocketList.setControlPoint(this);
        ssdpNotifySocketList.start();

        ////////////////////////////////////////
        // SeachResponse Socket
        ////////////////////////////////////////

        int ssdpPort = getSSDPPort();
        retryCnt = 0;
        SSDPSearchResponseSocketList ssdpSearchResponseSocketList =
                getSSDPSearchResponseSocketList();
        while (ssdpSearchResponseSocketList.open(ssdpPort) == false) {
            retryCnt++;
            if (UPnP.SERVER_RETRY_COUNT < retryCnt) {
                Debug.warning("Failed to open SSDP search response port " + ssdpPort);
                return false;
            }
            // I2P go down not up so we don't run into other I2P things
            setSSDPPort(ssdpPort - 1);
            ssdpPort = getSSDPPort();
        }
        ssdpSearchResponseSocketList.setControlPoint(this);
        ssdpSearchResponseSocketList.start();

        ////////////////////////////////////////
        // search root devices
        ////////////////////////////////////////

        search(target, mx);

        ////////////////////////////////////////
        // Disposer
        ////////////////////////////////////////

        Disposer disposer = new Disposer(this);
        setDeviceDisposer(disposer);
        disposer.start();

        ////////////////////////////////////////
        // Subscriber
        ////////////////////////////////////////

        if (isNMPRMode() == true) {
            RenewSubscriber renewSub = new RenewSubscriber(this);
            setRenewSubscriber(renewSub);
            renewSub.start();
        }

        return true;
    }

    /** @param target the search target */
    public boolean start(String target) {
        return start(target, SSDP.DEFAULT_MSEARCH_MX);
    }

    /** Start with default search target */
    public boolean start() {
        return start(ST.ROOT_DEVICE, SSDP.DEFAULT_MSEARCH_MX);
    }

    /** @return true if stopped successfully */
    public boolean stop() {
        unsubscribe();

        SSDPNotifySocketList ssdpNotifySocketList = getSSDPNotifySocketList();
        ssdpNotifySocketList.stop();
        ssdpNotifySocketList.close();
        ssdpNotifySocketList.clear();

        SSDPSearchResponseSocketList ssdpSearchResponseSocketList =
                getSSDPSearchResponseSocketList();
        ssdpSearchResponseSocketList.stop();
        ssdpSearchResponseSocketList.close();
        ssdpSearchResponseSocketList.clear();

        HTTPServerList httpServerList = getHTTPServerList();
        httpServerList.stop();
        httpServerList.close();
        httpServerList.clear();

        ////////////////////////////////////////
        // Disposer
        ////////////////////////////////////////

        Disposer disposer = getDeviceDisposer();
        if (disposer != null) {
            disposer.stop();
            setDeviceDisposer(null);
        }

        ////////////////////////////////////////
        // Subscriber
        ////////////////////////////////////////

        RenewSubscriber renewSub = getRenewSubscriber();
        if (renewSub != null) {
            renewSub.stop();
            setRenewSubscriber(null);
        }

        // I2P so we will re-notify on restart
        DeviceList dl = getDeviceList();
        for (int i = 0; i < dl.size(); i++) {
            removeDevice(dl.getDevice(i));
        }

        return true;
    }

    ////////////////////////////////////////////////
    //	userData
    ////////////////////////////////////////////////

    private Object userData = null;

    /** @param data user-defined data */
    public void setUserData(Object data) {
        userData = data;
    }

    /** @return user-defined data */
    public Object getUserData() {
        return userData;
    }

    ////////////////////////////////////////////////
    //	print
    ////////////////////////////////////////////////

    public void print() {
        DeviceList devList = getDeviceList();
        int devCnt = devList.size();
        Debug.message("Device Num = " + devCnt);
        for (int n = 0; n < devCnt; n++) {
            Device dev = devList.getDevice(n);
            Debug.message(
                    "["
                            + n
                            + "] "
                            + dev.getFriendlyName()
                            + ", "
                            + dev.getLeaseTime()
                            + ", "
                            + dev.getElapsedTime());
        }
    }
}
