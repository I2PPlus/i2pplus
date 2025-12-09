/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.xml;

import org.cybergarage.xml.*;

/**
 * Base data container for UPnP XML node information.
 *
 * <p>This class provides a foundation for UPnP data structures that are associated with XML nodes.
 * It maintains a reference to the underlying XML node and provides basic data management
 * functionality.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>XML node association
 *   <li>Base data container functionality
 *   <li>Node lifecycle management
 *   <li>XML integration layer
 *   <li>Extensible data structure
 * </ul>
 *
 * <p>This class serves as the base class for more specific UPnP data containers like DeviceData,
 * ServiceData, and ActionData, providing common XML node management functionality across the
 * framework.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class NodeData {
    public NodeData() {
        setNode(null);
    }

    ////////////////////////////////////////////////
    // Node
    ////////////////////////////////////////////////

    private Node node;

    public void setNode(Node node) {
        this.node = node;
    }

    public Node getNode() {
        return node;
    }
}
