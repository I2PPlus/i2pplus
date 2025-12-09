/******************************************************************
 * CyberUtil for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.util;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Debug utility class for the CyberLink framework.
 *
 * <p>This class provides debug logging functionality that integrates with the I2P logging system.
 * It supports conditional debug output based on configuration settings.
 *
 * <p>All methods are static to provide convenient access throughout the framework. Debug output can
 * be enabled/disabled at runtime.
 *
 * @author Satoshi Konno
 * @author I2P modifications
 * @version 1.0
 * @since 1.0
 */
public final class Debug {
    private static Log _log;

    /**
     * Initializes debug system with I2P application context.
     *
     * @param ctx I2P application context for logging
     */
    public static void initialize(I2PAppContext ctx) {
        // don't keep static ref on android, just skip it
        if (SystemVersion.isAndroid()) return;
        _log = ctx.logManager().getLog(Debug.class);
        // org.cybergarage.util.Debug=DEBUG at startup
        enabled = _log.shouldDebug();
    }

    /**
     * Flag indicating whether debug output is enabled. When true, debug messages will be logged;
     * when false, they are suppressed.
     */
    public static boolean enabled = false;

    /** Enables debug output. */
    public static final void on() {
        enabled = true;
    }

    /** Disables debug output. */
    public static final void off() {
        enabled = false;
    }

    /**
     * Checks if debug output is enabled.
     *
     * @return true if debug is enabled, false otherwise
     */
    public static boolean isOn() {
        return enabled;
    }

    /**
     * Logs a debug message.
     *
     * @param s message to log
     */
    public static final void message(String s) {
        if (_log != null) _log.debug(s);
    }

    /**
     * Logs two debug messages.
     *
     * @param m1 first message to log
     * @param m2 second message to log
     */
    public static final void message(String m1, String m2) {
        if (_log != null) {
            _log.debug(m1);
            _log.debug(m2);
        }
    }

    /**
     * Logs a warning message.
     *
     * @param s warning message to log
     */
    public static final void warning(String s) {
        if (_log != null) _log.warn(s);
    }

    /**
     * Logs a warning message with exception.
     *
     * @param m warning message to log
     * @param e exception to log
     */
    public static final void warning(String m, Exception e) {
        if (_log != null) _log.warn(m, e);
    }

    /**
     * Logs an exception as a warning.
     *
     * @param e exception to log
     */
    public static final void warning(Exception e) {
        if (_log != null) _log.warn("", e);
    }
}
