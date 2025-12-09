/******************************************************************
 *
 *	CyberUtil for Java
 *
 *	Copyright (C) Satoshi Konno 2002
 *
 *	File: ListenerList.java
 *
 *	Revision;
 *
 *	12/30/02
 *		- first revision.
 *
 ******************************************************************/

package org.cybergarage.util;

import java.util.Vector;

/**
 * A thread-safe list for managing event listeners.
 * This class extends Vector to provide a collection that prevents duplicate
 * listener objects from being added.
 */
public class ListenerList extends Vector<Object> {
    /**
     * Adds a listener object to the list if it's not already present.
     * 
     * @param obj the listener object to add
     * @return true if the object was added, false if it was already in the list
     */
    public boolean add(Object obj) {
        if (0 <= indexOf(obj)) return false;
        return super.add(obj);
    }
}
