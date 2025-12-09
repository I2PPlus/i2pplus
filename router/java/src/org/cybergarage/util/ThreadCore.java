/*
 * CyberUtil for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.util;

/**
 * Base class for managing thread lifecycle and execution. This class implements Runnable and
 * provides methods to start, stop, restart, and manage thread execution in a safe manner using
 * thread interruption.
 */
public class ThreadCore implements Runnable {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /** Creates a new ThreadCore instance. */
    public ThreadCore() {}

    ////////////////////////////////////////////////
    //	Thread
    ////////////////////////////////////////////////

    private java.lang.Thread mThreadObject = null;

    /**
     * Sets the internal Thread object.
     *
     * @param obj the Thread object to set
     */
    public void setThreadObject(java.lang.Thread obj) {
        mThreadObject = obj;
    }

    /**
     * Gets the internal Thread object.
     *
     * @return the Thread object, or null if not set
     */
    public java.lang.Thread getThreadObject() {
        return mThreadObject;
    }

    /**
     * Starts the thread if it's not already running. Creates a new Thread object if one doesn't
     * exist.
     */
    public void start() {
        java.lang.Thread threadObject = getThreadObject();
        if (threadObject == null) {
            threadObject = new java.lang.Thread(this, "Cyber.ThreadCore");
            setThreadObject(threadObject);
            threadObject.start();
        }
    }

    /**
     * The main execution method of the thread. This method should be overridden by subclasses to
     * implement specific functionality.
     */
    public void run() {}

    /**
     * Checks if the current thread is the same as this ThreadCore's thread.
     *
     * @return true if the current thread is running this ThreadCore, false otherwise
     */
    public boolean isRunnable() {
        return (Thread.currentThread() == getThreadObject()) ? true : false;
    }

    /**
     * Stops the thread safely using interruption. This method interrupts the thread and clears the
     * thread object reference.
     */
    public void stop() {
        java.lang.Thread threadObject = getThreadObject();
        if (threadObject != null) {
            // threadObject.destroy();
            // threadObject.stop();

            // Thanks for Kazuyuki Shudo (08/23/07)
            threadObject.interrupt();

            setThreadObject(null);
            // I2P break Disposer out of sleep()
            threadObject.interrupt();
        }
    }

    /** Restarts the thread by stopping it first and then starting it again. */
    public void restart() {
        stop();
        start();
    }
}
