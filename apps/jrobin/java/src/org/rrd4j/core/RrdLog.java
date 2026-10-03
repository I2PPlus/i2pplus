package org.rrd4j.core;

/**
 * Logging hook for the vendored rrd4j tree.
 *
 * <p>This library is built standalone (see {@code apps/jrobin/java/build.xml},
 * which compiles with no classpath), so it cannot reference I2P's LogManager
 * directly. Instead the embedder installs a delegate at startup and the library
 * routes its diagnostics through it. With no delegate installed every call is a
 * no-op, which is the correct default for standalone use.
 *
 * <p>Added because rrd4j runs scheduled work whose failure mode is invisible:
 * {@link java.util.concurrent.ScheduledExecutorService#scheduleWithFixedDelay}
 * cancels all future executions when the task throws, so a single failed flush
 * silently ends all later ones. Without a log line there is no way to tell that
 * from "nothing to write".
 */
public final class RrdLog {

    /** Sink for rrd4j diagnostics. */
    public interface Delegate {
        /** @param severity one of "debug", "warn", "error" */
        void log(String severity, String message, Throwable cause);
    }

    private static volatile Delegate _delegate;

    private RrdLog() {}

    /**
     * Install the sink for rrd4j diagnostics.
     *
     * @param delegate the sink, or null to silence rrd4j again
     */
    public static void setDelegate(Delegate delegate) {
        _delegate = delegate;
    }

    /** @return the installed sink, or null if none */
    public static Delegate getDelegate() {
        return _delegate;
    }

    /** Log at debug severity. */
    public static void debug(String message) {
        log("debug", message, null);
    }

    /** Log at warning severity. */
    public static void warn(String message, Throwable cause) {
        log("warn", message, cause);
    }

    /** Log at error severity. */
    public static void error(String message, Throwable cause) {
        log("error", message, cause);
    }

    private static void log(String severity, String message, Throwable cause) {
        Delegate d = _delegate;
        if (d == null) {
            return;
        }
        try {
            d.log(severity, message, cause);
        } catch (Throwable ignored) {
            // A broken delegate must never break the caller, which in this
            // library is often a scheduled task whose failure is unrecoverable.
        }
    }
}