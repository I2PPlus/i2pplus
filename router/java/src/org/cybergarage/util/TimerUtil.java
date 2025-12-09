/******************************************************************
 * CyberUtil for Java
 * Copyright (C) Satoshi Konno 2002-2003
 ******************************************************************/

package org.cybergarage.util;

/**
 * Utility class providing timer and thread sleeping functionality. This class contains static
 * methods for pausing execution and generating random wait times, useful for timing control and
 * thread management.
 */
public final class TimerUtil {
    /**
     * Pauses the current thread for the specified amount of time.
     *
     * @param waitTime the time to wait in milliseconds
     */
    public static final void wait(int waitTime) {
        try {
            Thread.sleep(waitTime);
        } catch (Exception e) {
            // Ignore InterruptedException and other sleep exceptions
        }
    }

    /**
     * Pauses the current thread for a random amount of time up to the specified maximum.
     *
     * @param time the maximum time to wait in milliseconds
     */
    public static final void waitRandom(int time) {
        int waitTime = (int) (Math.random() * (double) time);
        try {
            Thread.sleep(waitTime);
        } catch (Exception e) {
            // Ignore InterruptedException and other sleep exceptions
        }
    }
}
