/******************************************************************
 * CyberUtil for Java
 * Copyright (C) Satoshi Konno 2002-2004
 ******************************************************************/

package org.cybergarage.util;

/**
 * A simple mutex implementation for thread synchronization. This class provides basic locking
 * mechanisms using synchronized methods and wait/notify for inter-thread communication.
 */
public class Mutex {
    private boolean syncLock;

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /** Creates a new Mutex instance in the unlocked state. */
    public Mutex() {
        syncLock = false;
    }

    ////////////////////////////////////////////////
    //	lock
    ////////////////////////////////////////////////

    /**
     * Acquires the lock, blocking if necessary until the lock is available. If the lock is already
     * held, the calling thread will wait until it's released.
     */
    public synchronized void lock() {
        while (syncLock == true) {
            try {
                wait();
            } catch (Exception e) {
                Debug.warning(e);
            }
            ;
        }
        syncLock = true;
    }

    /**
     * Releases the lock and notifies all waiting threads. This method should only be called by the
     * thread that currently holds the lock.
     */
    public synchronized void unlock() {
        syncLock = false;
        notifyAll();
    }
}
