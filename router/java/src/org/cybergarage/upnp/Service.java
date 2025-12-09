/******************************************************************
 * CyberLink for Java
 * Copyright (C) Satoshi Konno 2002-2003
 ******************************************************************/

package org.cybergarage.upnp;

import org.cybergarage.http.HTTP;
import org.cybergarage.http.HTTPResponse;
import org.cybergarage.upnp.control.ActionListener;
import org.cybergarage.upnp.control.QueryListener;
import org.cybergarage.upnp.device.InvalidDescriptionException;
import org.cybergarage.upnp.device.NTS;
import org.cybergarage.upnp.device.ST;
import org.cybergarage.upnp.event.NotifyRequest;
import org.cybergarage.upnp.event.Subscriber;
import org.cybergarage.upnp.event.SubscriberList;
import org.cybergarage.upnp.ssdp.SSDPNotifyRequest;
import org.cybergarage.upnp.ssdp.SSDPNotifySocket;
import org.cybergarage.upnp.ssdp.SSDPPacket;
import org.cybergarage.upnp.xml.ServiceData;
import org.cybergarage.util.Debug;
import org.cybergarage.util.Mutex;
import org.cybergarage.util.StringUtil;
import org.cybergarage.xml.Node;
import org.cybergarage.xml.Parser;
import org.cybergarage.xml.ParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class Service {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    /** XML element name for service nodes. */
    public static final String ELEM_NAME = "service";

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    private Node serviceNode;

    /**
     * Gets the XML node representing this service.
     *
     * @return the service XML node
     */
    public Node getServiceNode() {
        return serviceNode;
    }

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////
    /** Root node name for service control point documents. */
    public static final String SCPD_ROOTNODE = "scpd";

    /** Namespace for service control point documents. */
    public static final String SCPD_ROOTNODE_NS = "urn:schemas-upnp-org:service-1-0";

    /** XML element name for specification version. */
    public static final String SPEC_VERSION = "specVersion";

    /** XML element name for major version. */
    public static final String MAJOR = "major";

    /** Default major version value. */
    public static final String MAJOR_VALUE = "1";

    /** XML element name for minor version. */
    public static final String MINOR = "minor";

    /** Default minor version value. */
    public static final String MINOR_VALUE = "0";

    /**
     * Creates a new empty Service instance. This constructor creates a service with default SCPD
     * specification version 1.0 and initializes the required XML structure.
     */
    public Service() {
        this(new Node(ELEM_NAME));

        Node sp = new Node(SPEC_VERSION);

        Node M = new Node(MAJOR);
        M.setValue(MAJOR_VALUE);
        sp.addNode(M);

        Node m = new Node(MINOR);
        m.setValue(MINOR_VALUE);
        sp.addNode(m);

        // Node scpd = new Node(SCPD_ROOTNODE,SCPD_ROOTNODE_NS); wrong!
        Node scpd = new Node(SCPD_ROOTNODE);
        scpd.addAttribute("xmlns", SCPD_ROOTNODE_NS);
        scpd.addNode(sp);
        getServiceData().setSCPDNode(scpd);
    }

    /**
     * Creates a Service instance from an existing XML node.
     *
     * @param node XML node representing the service
     */
    public Service(Node node) {
        serviceNode = node;
    }

    ////////////////////////////////////////////////
    // Mutex
    ////////////////////////////////////////////////

    private Mutex mutex = new Mutex();

    /** Acquires exclusive lock on this service for thread-safe operations. */
    public void lock() {
        mutex.lock();
    }

    /** Releases exclusive lock on this service. */
    public void unlock() {
        mutex.unlock();
    }

    ////////////////////////////////////////////////
    //	isServiceNode
    ////////////////////////////////////////////////

    /**
     * Checks if the given XML node represents a service.
     *
     * @param node the XML node to check
     * @return true if the node is a service node, false otherwise
     */
    public static boolean isServiceNode(Node node) {
        return Service.ELEM_NAME.equals(node.getName());
    }

    ////////////////////////////////////////////////
    //	Device/Root Node
    ////////////////////////////////////////////////

    /**
     * Gets the device node containing this service.
     *
     * @return the device node, or null if not found
     */
    private Node getDeviceNode() {
        Node node = getServiceNode().getParentNode();
        if (node == null) return null;
        return node.getParentNode();
    }

    /**
     * Gets the root node of the device description.
     *
     * @return the root node
     */
    private Node getRootNode() {
        return getServiceNode().getRootNode();
    }

    ////////////////////////////////////////////////
    //	Device
    ////////////////////////////////////////////////

    /**
     * Gets the device containing this service.
     *
     * @return the parent device
     */
    public Device getDevice() {
        return new Device(getRootNode(), getDeviceNode());
    }

    /**
     * Gets the root device containing this service.
     *
     * @return the root device
     */
    public Device getRootDevice() {
        return getDevice().getRootDevice();
    }

    ////////////////////////////////////////////////
    //	serviceType
    ////////////////////////////////////////////////

    /** XML element name for service type. */
    private static final String SERVICE_TYPE = "serviceType";

    /**
     * Sets service type of this service.
     *
     * @param value service type string to set
     */
    public void setServiceType(String value) {
        getServiceNode().setNode(SERVICE_TYPE, value);
    }

    /**
     * Gets the service type of this service.
     *
     * @return service type string, or empty string if not set
     */
    public String getServiceType() {
        return getServiceNode().getNodeValue(SERVICE_TYPE);
    }

    ////////////////////////////////////////////////
    //	serviceID
    ////////////////////////////////////////////////

    /** XML element name for service ID. */
    private static final String SERVICE_ID = "serviceId";

    /**
     * Sets service ID of this service.
     *
     * @param value service ID string to set
     */
    public void setServiceID(String value) {
        getServiceNode().setNode(SERVICE_ID, value);
    }

    /**
     * Gets the service ID of this service.
     *
     * @return service ID string, or empty string if not set
     */
    public String getServiceID() {
        return getServiceNode().getNodeValue(SERVICE_ID);
    }

    ////////////////////////////////////////////////
    //	configID
    ////////////////////////////////////////////////

    /** XML attribute name for configuration ID. */
    private static final String CONFIG_ID = "configId";

    /** Updates the configuration ID for this service based on its SCPD XML. */
    public void updateConfigId() {
        Node scpdNode = getSCPDNode();
        if (scpdNode == null) return;

        String scpdXml = scpdNode.toString();
        int configId = UPnP.caluculateConfigId(scpdXml);
        scpdNode.setAttribute(CONFIG_ID, configId);
    }

    /**
     * Gets the configuration ID for this service.
     *
     * @return the configuration ID, or 0 if not set
     */
    public int getConfigId() {
        Node scpdNode = getSCPDNode();
        if (scpdNode == null) return 0;
        return scpdNode.getAttributeIntegerValue(CONFIG_ID);
    }

    ////////////////////////////////////////////////
    //	isURL
    ////////////////////////////////////////////////

    /**
     * Checks if the given URL matches the reference URL, considering both absolute and relative
     * URLs.
     *
     * @param referenceUrl the reference URL to compare against
     * @param url the URL to check
     * @return true if the URLs match, false otherwise
     */
    // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (09/03/03)
    private boolean isURL(String referenceUrl, String url) {
        if (referenceUrl == null || url == null) return false;
        boolean ret = url.equals(referenceUrl);
        if (ret == true) return true;
        String relativeRefUrl = HTTP.toRelativeURL(referenceUrl, false);
        ret = url.equals(relativeRefUrl);
        if (ret == true) return true;
        return false;
    }

    ////////////////////////////////////////////////
    //	SCPDURL
    ////////////////////////////////////////////////

    /** XML element name for service control point document URL. */
    private static final String SCPDURL = "SCPDURL";

    /**
     * Sets the SCPD URL for this service.
     *
     * @param value the SCPD URL to set
     */
    public void setSCPDURL(String value) {
        getServiceNode().setNode(SCPDURL, value);
    }

    /**
     * Gets the SCPD URL for this service.
     *
     * @return the SCPD URL
     */
    public String getSCPDURL() {
        return getServiceNode().getNodeValue(SCPDURL);
    }

    /**
     * Checks if the given URL matches this service's SCPD URL.
     *
     * @param url the URL to check
     * @return true if the URL matches the SCPD URL, false otherwise
     */
    public boolean isSCPDURL(String url) {
        return isURL(getSCPDURL(), url);
    }

    ////////////////////////////////////////////////
    //	controlURL
    ////////////////////////////////////////////////

    /** XML element name for control URL. */
    private static final String CONTROL_URL = "controlURL";

    /**
     * Sets the control URL for this service.
     *
     * @param value the control URL to set
     */
    public void setControlURL(String value) {
        getServiceNode().setNode(CONTROL_URL, value);
    }

    /**
     * Gets the control URL for this service.
     *
     * @return the control URL
     */
    public String getControlURL() {
        return getServiceNode().getNodeValue(CONTROL_URL);
    }

    /**
     * Checks if the given URL matches this service's control URL.
     *
     * @param url the URL to check
     * @return true if the URL matches the control URL, false otherwise
     */
    public boolean isControlURL(String url) {
        return isURL(getControlURL(), url);
    }

    ////////////////////////////////////////////////
    //	eventSubURL
    ////////////////////////////////////////////////

    /** XML element name for event subscription URL. */
    private static final String EVENT_SUB_URL = "eventSubURL";

    /**
     * Sets the event subscription URL for this service.
     *
     * @param value the event subscription URL to set
     */
    public void setEventSubURL(String value) {
        getServiceNode().setNode(EVENT_SUB_URL, value);
    }

    /**
     * Gets the event subscription URL for this service.
     *
     * @return the event subscription URL
     */
    public String getEventSubURL() {
        return getServiceNode().getNodeValue(EVENT_SUB_URL);
    }

    /**
     * Checks if the given URL matches this service's event subscription URL.
     *
     * @param url the URL to check
     * @return true if the URL matches the event subscription URL, false otherwise
     */
    public boolean isEventSubURL(String url) {
        return isURL(getEventSubURL(), url);
    }

    ////////////////////////////////////////////////
    //	SCPD node
    ////////////////////////////////////////////////

    /**
     * Loads service control point description (SCPD) from a string.
     *
     * @param scpdStr the SCPD XML string to load
     * @return true if SCPD was loaded successfully, false otherwise
     * @throws InvalidDescriptionException if SCPD cannot be parsed
     */
    public boolean loadSCPD(String scpdStr) throws InvalidDescriptionException {
        try {
            Parser parser = UPnP.getXMLParser();
            Node scpdNode = parser.parse(scpdStr);
            if (scpdNode == null) return false;
            ServiceData data = getServiceData();
            data.setSCPDNode(scpdNode);
        } catch (ParserException e) {
            throw new InvalidDescriptionException(e);
        }

        return true;
    }

    /**
     * Loads service control point description (SCPD) from a file.
     *
     * @param file file containing SCPD XML
     * @return true if SCPD was loaded successfully, false otherwise
     * @throws ParserException if SCPD cannot be parsed
     */
    public boolean loadSCPD(File file) throws ParserException {
        Parser parser = UPnP.getXMLParser();
        Node scpdNode = parser.parse(file);
        if (scpdNode == null) return false;

        ServiceData data = getServiceData();
        data.setSCPDNode(scpdNode);

        return true;
    }

    /**
     * Loads service description (SCPD) from an input stream.
     *
     * @param input input stream containing SCPD XML
     * @return true if SCPD was loaded successfully, false otherwise
     * @throws ParserException if SCPD cannot be parsed
     */
    public boolean loadSCPD(InputStream input) throws ParserException {
        Parser parser = UPnP.getXMLParser();
        Node scpdNode = parser.parse(input);
        if (scpdNode == null) return false;

        ServiceData data = getServiceData();
        data.setSCPDNode(scpdNode);

        return true;
    }

    /**
     * Sets the description URL for this service.
     *
     * @param value the description URL to set
     */
    public void setDescriptionURL(String value) {
        getServiceData().setDescriptionURL(value);
    }

    /**
     * Gets the description URL for this service.
     *
     * @return the description URL
     */
    public String getDescriptionURL() {
        return getServiceData().getDescriptionURL();
    }

    /**
     * Gets the SCPD node by parsing from a URL.
     *
     * @param scpdUrl the URL to parse SCPD from
     * @return the parsed SCPD node
     * @throws ParserException if parsing fails
     */
    private Node getSCPDNode(URL scpdUrl) throws ParserException {
        Parser parser = UPnP.getXMLParser();
        return parser.parse(scpdUrl);
    }

    /**
     * Gets the SCPD node by parsing from a file.
     *
     * @param scpdFile the file to parse SCPD from
     * @return the parsed SCPD node
     * @throws ParserException if parsing fails
     */
    private Node getSCPDNode(File scpdFile) throws ParserException {
        Parser parser = UPnP.getXMLParser();
        return parser.parse(scpdFile);
    }

    /**
     * Gets the SCPD node for this service, loading it if necessary.
     *
     * @return the SCPD node, or null if not available
     */
    private Node getSCPDNode() {
        ServiceData data = getServiceData();
        Node scpdNode = data.getSCPDNode();
        if (scpdNode != null) return scpdNode;

        // Thanks for Jaap (Sep 18, 2010)
        Device rootDev = getRootDevice();
        if (rootDev == null) return null;

        String scpdURLStr = getSCPDURL();

        try {
            URL scpdUrl = new URL(rootDev.getAbsoluteURL(scpdURLStr));
            scpdNode = getSCPDNode(scpdUrl);
            if (scpdNode != null) {
                data.setSCPDNode(scpdNode);
                return scpdNode;
            }
        } catch (Exception e) {
            // I2P
            Debug.warning(e);
        }

        return null;
    }

    /**
     * Gets the SCPD data for this service as a byte array.
     *
     * @return the SCPD XML data as bytes, or empty array if not available
     */
    public byte[] getSCPDData() {
        Node scpdNode = getSCPDNode();
        if (scpdNode == null) return new byte[0];
        // Thanks for Mikael Hakman (04/25/05)
        String desc = new String();
        desc += UPnP.XML_DECLARATION;
        desc += "\n";
        desc += scpdNode.toString();
        return desc.getBytes(StandardCharsets.UTF_8);
    }

    ////////////////////////////////////////////////
    //	actionList
    ////////////////////////////////////////////////

    /**
     * Gets the list of actions provided by this service.
     *
     * @return ActionList containing all actions of this service, empty list if no actions
     */
    public ActionList getActionList() {
        ActionList actionList = new ActionList();
        Node scdpNode = getSCPDNode();
        if (scdpNode == null) return actionList;
        Node actionListNode = scdpNode.getNode(ActionList.ELEM_NAME);
        if (actionListNode == null) return actionList;
        int nNode = actionListNode.getNNodes();
        for (int n = 0; n < nNode; n++) {
            Node node = actionListNode.getNode(n);
            if (Action.isActionNode(node) == false) continue;
            Action action = new Action(serviceNode, node);
            actionList.add(action);
        }
        return actionList;
    }

    /**
     * Finds an action by name in this service.
     *
     * @param actionName name of action to find
     * @return Action matching specified name, or null if not found
     */
    public Action getAction(String actionName) {
        ActionList actionList = getActionList();
        int nActions = actionList.size();
        for (int n = 0; n < nActions; n++) {
            Action action = actionList.getAction(n);
            String name = action.getName();
            if (name == null) continue;
            if (name.equals(actionName) == true) return action;
        }
        return null;
    }

    /**
     * Adds an action to this service.
     *
     * <p>This method sets service reference for all arguments in action and adds action to the
     * service's SCPD structure.
     *
     * @param a Action to add to this service
     */
    public void addAction(Action a) {
        Iterator<Argument> i = a.getArgumentList().iterator();
        while (i.hasNext()) {
            Argument arg = i.next();
            arg.setService(this);
        }

        Node scdpNode = getSCPDNode();
        Node actionListNode = scdpNode.getNode(ActionList.ELEM_NAME);
        if (actionListNode == null) {
            actionListNode = new Node(ActionList.ELEM_NAME);
            scdpNode.addNode(actionListNode);
        }
        actionListNode.addNode(a.getActionNode());
    }

    ////////////////////////////////////////////////
    //	serviceStateTable
    ////////////////////////////////////////////////

    public ServiceStateTable getServiceStateTable() {
        ServiceStateTable stateTable = new ServiceStateTable();
        Node stateTableNode = getSCPDNode().getNode(ServiceStateTable.ELEM_NAME);
        if (stateTableNode == null) return stateTable;
        Node serviceNode = getServiceNode();
        int nNode = stateTableNode.getNNodes();
        for (int n = 0; n < nNode; n++) {
            Node node = stateTableNode.getNode(n);
            if (StateVariable.isStateVariableNode(node) == false) continue;
            StateVariable serviceVar = new StateVariable(serviceNode, node);
            stateTable.add(serviceVar);
        }
        return stateTable;
    }

    public StateVariable getStateVariable(String name) {
        ServiceStateTable stateTable = getServiceStateTable();
        int tableSize = stateTable.size();
        for (int n = 0; n < tableSize; n++) {
            StateVariable var = stateTable.getStateVariable(n);
            String varName = var.getName();
            if (varName == null) continue;
            if (varName.equals(name) == true) return var;
        }
        return null;
    }

    public boolean hasStateVariable(String name) {
        return (getStateVariable(name) != null) ? true : false;
    }

    ////////////////////////////////////////////////
    //	UserData
    ////////////////////////////////////////////////

    public boolean isService(String name) {
        if (name == null) return false;
        if (name.endsWith(getServiceType()) == true) return true;
        if (name.endsWith(getServiceID()) == true) return true;
        return false;
    }

    ////////////////////////////////////////////////
    //	UserData
    ////////////////////////////////////////////////

    private ServiceData getServiceData() {
        Node node = getServiceNode();
        ServiceData userData = (ServiceData) node.getUserData();
        if (userData == null) {
            userData = new ServiceData();
            node.setUserData(userData);
            userData.setNode(node);
        }
        return userData;
    }

    ////////////////////////////////////////////////
    //	Notify
    ////////////////////////////////////////////////

    private String getNotifyServiceTypeNT() {
        return getServiceType();
    }

    private String getNotifyServiceTypeUSN() {
        return getDevice().getUDN() + "::" + getServiceType();
    }

    /**
     * Announces this service to the network using SSDP.
     *
     * <p>This method sends SSDP NOTIFY messages to announce the service's presence. The
     * announcement includes the service type, unique service identifier, and location of the root
     * device containing this service.
     *
     * @param bindAddr bind address to send announcement from
     */
    public void announce(String bindAddr) {
        // uuid:device-UUID::urn:schemas-upnp-org:service:serviceType:v
        Device rootDev = getRootDevice();
        String devLocation = rootDev.getLocationURL(bindAddr);
        String serviceNT = getNotifyServiceTypeNT();
        String serviceUSN = getNotifyServiceTypeUSN();

        Device dev = getDevice();

        SSDPNotifyRequest ssdpReq = new SSDPNotifyRequest();
        ssdpReq.setServer(UPnP.getServerName());
        ssdpReq.setLeaseTime(dev.getLeaseTime());
        ssdpReq.setLocation(devLocation);
        ssdpReq.setNTS(NTS.ALIVE);
        ssdpReq.setNT(serviceNT);
        ssdpReq.setUSN(serviceUSN);

        // I2P
        try {
            SSDPNotifySocket ssdpSock = new SSDPNotifySocket(bindAddr);
            Device.notifyWait();
            ssdpSock.post(ssdpReq);
        } catch (IOException ioe) {
            Debug.warning("Failed announce from " + bindAddr, ioe);
        }
    }

    public void byebye(String bindAddr) {
        // uuid:device-UUID::urn:schemas-upnp-org:service:serviceType:v

        String devNT = getNotifyServiceTypeNT();
        String devUSN = getNotifyServiceTypeUSN();

        SSDPNotifyRequest ssdpReq = new SSDPNotifyRequest();
        ssdpReq.setNTS(NTS.BYEBYE);
        ssdpReq.setNT(devNT);
        ssdpReq.setUSN(devUSN);

        // I2P
        try {
            SSDPNotifySocket ssdpSock = new SSDPNotifySocket(bindAddr);
            Device.notifyWait();
            ssdpSock.post(ssdpReq);
        } catch (IOException ioe) {
            Debug.warning("Failed announce from " + bindAddr, ioe);
        }
    }

    public boolean serviceSearchResponse(SSDPPacket ssdpPacket) {
        String ssdpST = ssdpPacket.getST();

        if (ssdpST == null) return false;

        Device dev = getDevice();

        String serviceNT = getNotifyServiceTypeNT();
        String serviceUSN = getNotifyServiceTypeUSN();

        if (ST.isAllDevice(ssdpST) == true) {
            dev.postSearchResponse(ssdpPacket, serviceNT, serviceUSN);
        } else if (ST.isURNService(ssdpST) == true) {
            String serviceType = getServiceType();
            if (ssdpST.equals(serviceType) == true)
                dev.postSearchResponse(ssdpPacket, serviceType, serviceUSN);
        }

        return true;
    }

    ////////////////////////////////////////////////
    // QueryListener
    ////////////////////////////////////////////////

    public void setQueryListener(QueryListener queryListener) {
        ServiceStateTable stateTable = getServiceStateTable();
        int tableSize = stateTable.size();
        for (int n = 0; n < tableSize; n++) {
            StateVariable var = stateTable.getStateVariable(n);
            var.setQueryListener(queryListener);
        }
    }

    ////////////////////////////////////////////////
    //	Subscription
    ////////////////////////////////////////////////

    public SubscriberList getSubscriberList() {
        return getServiceData().getSubscriberList();
    }

    public void addSubscriber(Subscriber sub) {
        getSubscriberList().add(sub);
    }

    public void removeSubscriber(Subscriber sub) {
        getSubscriberList().remove(sub);
    }

    public Subscriber getSubscriber(String name) {
        SubscriberList subList = getSubscriberList();
        int subListCnt = subList.size();
        for (int n = 0; n < subListCnt; n++) {
            Subscriber sub = subList.getSubscriber(n);
            if (sub == null) continue;
            String sid = sub.getSID();
            if (sid == null) continue;
            if (sid.equals(name) == true) return sub;
        }
        return null;
    }

    private boolean notify(Subscriber sub, StateVariable stateVar) {
        String varName = stateVar.getName();
        String value = stateVar.getValue();

        String host = sub.getDeliveryHost();
        int port = sub.getDeliveryPort();

        NotifyRequest notifyReq = new NotifyRequest();
        notifyReq.setRequest(sub, varName, value);

        HTTPResponse res = notifyReq.post(host, port);
        if (res.isSuccessful() == false) return false;

        sub.incrementNotifyCount();

        return true;
    }

    public void notify(StateVariable stateVar) {
        SubscriberList subList = getSubscriberList();
        int subListCnt;
        Subscriber subs[];

        // Remove expired subscribers.
        subListCnt = subList.size();
        subs = new Subscriber[subListCnt];
        for (int n = 0; n < subListCnt; n++) subs[n] = subList.getSubscriber(n);
        for (int n = 0; n < subListCnt; n++) {
            Subscriber sub = subs[n];
            if (sub == null) continue;
            if (sub.isExpired() == true) removeSubscriber(sub);
        }

        // Notify to subscribers.
        subListCnt = subList.size();
        subs = new Subscriber[subListCnt];
        for (int n = 0; n < subListCnt; n++) subs[n] = subList.getSubscriber(n);
        for (int n = 0; n < subListCnt; n++) {
            Subscriber sub = subs[n];
            if (sub == null) continue;
            if (notify(sub, stateVar) == false) {
                /* Don't remove for NMPR specification.
                removeSubscriber(sub);
                */
            }
        }
    }

    public void notifyAllStateVariables() {
        ServiceStateTable stateTable = getServiceStateTable();
        int tableSize = stateTable.size();
        for (int n = 0; n < tableSize; n++) {
            StateVariable var = stateTable.getStateVariable(n);
            if (var.isSendEvents() == true) notify(var);
        }
    }

    ////////////////////////////////////////////////
    // SID
    ////////////////////////////////////////////////

    public String getSID() {
        return getServiceData().getSID();
    }

    public void setSID(String id) {
        getServiceData().setSID(id);
    }

    public void clearSID() {
        setSID("");
        setTimeout(0);
    }

    public boolean hasSID() {
        return StringUtil.hasData(getSID());
    }

    public boolean isSubscribed() {
        return hasSID();
    }

    ////////////////////////////////////////////////
    // Timeout
    ////////////////////////////////////////////////

    public long getTimeout() {
        return getServiceData().getTimeout();
    }

    public void setTimeout(long value) {
        getServiceData().setTimeout(value);
    }

    ////////////////////////////////////////////////
    // AcionListener
    ////////////////////////////////////////////////

    public void setActionListener(ActionListener listener) {
        ActionList actionList = getActionList();
        int nActions = actionList.size();
        for (int n = 0; n < nActions; n++) {
            Action action = actionList.getAction(n);
            action.setActionListener(listener);
        }
    }

    /**
     * Add the StateVariable to the service.<br>
     * <br>
     * Note: This method should be used to create a dynamic<br>
     * Device withtout writing any XML that describe the device<br>
     * . <br>
     * Note: that no control for duplicate StateVariable is done.
     *
     * @param var StateVariable that will be added
     */
    public void addStateVariable(StateVariable var) {
        // TODO Some test are done not stable
        Node stateTableNode = getSCPDNode().getNode(ServiceStateTable.ELEM_NAME);
        if (stateTableNode == null) {
            stateTableNode = new Node(ServiceStateTable.ELEM_NAME);
            /*
             * Force the node <serviceStateTable> to be the first node inside <scpd>
             */
            // getSCPDNode().insertNode(stateTableNode,0);
            getSCPDNode().addNode(stateTableNode);
        }
        var.setServiceNode(getServiceNode());
        stateTableNode.addNode(var.getStateVariableNode());
    }

    ////////////////////////////////////////////////
    //	userData
    ////////////////////////////////////////////////

    private Object userData = null;

    public void setUserData(Object data) {
        userData = data;
    }

    public Object getUserData() {
        return userData;
    }
}
