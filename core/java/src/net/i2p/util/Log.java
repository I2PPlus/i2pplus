package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;

import java.util.Locale;

/**
 * Wrapper class for whatever logging system I2P uses.  This class should be
 * instantiated and kept as a variable for each class it is used by, e.g.
 *  <code>private final Log _log = context.logManager().getLog(MyClassName.class);</code>
 *
 * If there is anything in here that doesn't make sense, turn off your computer and go fly a kite.
 *
 * @author jrandom
 */
public class Log {
    private final Class<?> _class;
    private final String _className;
    private final String _name;
    private int _minPriority;
    private final LogScope _scope;
    private final LogManager _manager;

    /**
     * DEBUG.
     */
    public static final int DEBUG = 10;
    /**
     * INFO.
     */
    public static final int INFO = 20;
    /**
     * WARN.
     */
    public static final int WARN = 30;
    /**
     * ERROR.
     */
    public static final int ERROR = 40;
    /**
     * CRIT.
     */
    public static final int CRIT = 50;

    /**
     * STR_DEBUG.
     */
    public static final String STR_DEBUG = "DEBUG";
    /**
     * STR_INFO.
     */
    public static final String STR_INFO = "INFO";
    /**
     * STR_WARN.
     */
    public static final String STR_WARN = "WARN";
    /**
     * STR_ERROR.
     */
    public static final String STR_ERROR = "ERROR";
    /**
     * STR_CRIT.
     */
    public static final String STR_CRIT = "CRIT";

    /**
     * Get the log level integer for a string level name.
     *
     * @param level the level
     * @return the integer level
     */
    public static int getLevel(String level) {
        if (level == null) return ERROR;
        level = level.toUpperCase(Locale.US);
        if (STR_DEBUG.startsWith(level)) return DEBUG;
        if (STR_INFO.startsWith(level)) return INFO;
        if (STR_WARN.startsWith(level)) return WARN;
        if (STR_ERROR.startsWith(level)) return ERROR;
        if (STR_CRIT.startsWith(level)) return CRIT;
        return ERROR;
    }

    /**
     * Convert a log level integer to its string representation.
     *
     * @param level the integer level
     * @return the level string
     */
    public static String toLevelString(int level) {
        switch (level) {
            case DEBUG: return STR_DEBUG;
            case INFO: return STR_INFO;
            case WARN: return STR_WARN;
            case ERROR: return STR_ERROR;
            case CRIT: return STR_CRIT;
        }
        return (level > CRIT ? STR_CRIT : STR_DEBUG);
    }

    /**
     *  Warning - not recommended.
     *  Use I2PAppContext.getGlobalContext().logManager().getLog(cls)
     */
    public Log(Class<?> cls) {
        this(I2PAppContext.getGlobalContext().logManager(), cls, null);
        _manager.addLog(this);
    }

    /**
     *  Warning - not recommended.
     *  Use I2PAppContext.getGlobalContext().logManager().getLog(name)
     */
    public Log(String name) {
        this(I2PAppContext.getGlobalContext().logManager(), null, name);
        _manager.addLog(this);
    }

    /** Log */
    Log(LogManager manager, Class<?> cls) {
        this(manager, cls, null);
    }

    /** Log */
    Log(LogManager manager, String name) {
        this(manager, null, name);
    }

    /** Log */
    Log(LogManager manager, Class<?> cls, String name) {
        _manager = manager;
        _class = cls;
        _className = cls != null ? cls.getName() : null;
        _name = name;
        _minPriority = DEBUG;
        _scope = new LogScope(name, cls);
    }

    /**
     * Log a message at the given priority.
     *
     * @param priority the priority level
     * @param msg the message
     */
    public void log(int priority, String msg) {
        if (priority >= _minPriority) {
            _manager.addRecord(new LogRecord(_class, _name, Thread.currentThread().getName(), priority, msg, null));
        }
    }

    /**
     * Log a message with a throwable at the given priority.
     *
     * @param priority the priority level
     * @param msg the message
     * @param t the throwable
     */
    public void log(int priority, String msg, Throwable t) {
        if (priority >= _minPriority) {
            _manager.addRecord(new LogRecord(_class, _name, Thread.currentThread().getName(), priority, msg, t));
        }
    }

    /**
     *  Always log this message with the given priority, ignoring current minimum priority level.
     *  This allows an INFO message about changing port numbers, for example, to always be logged.
     *
     *  @since 0.8.2
     */
    public void logAlways(int priority, String msg) {
        _manager.addRecord(new LogRecord(_class, _name, Thread.currentThread().getName(), priority, msg, null));
    }

    /**
     * Log a debug message.
     *
     * @param msg the message
     */
    public void debug(String msg) {
        log(DEBUG, msg);
    }

    /**
     * Log a debug message with a throwable.
     *
     * @param msg the message
     * @param t the throwable
     */
    public void debug(String msg, Throwable t) {
        log(DEBUG, msg, t);
    }

    /**
     * Log an info message.
     *
     * @param msg the message
     */
    public void info(String msg) {
        log(INFO, msg);
    }

    /**
     * Log an info message with a throwable.
     *
     * @param msg the message
     * @param t the throwable
     */
    public void info(String msg, Throwable t) {
        log(INFO, msg, t);
    }

    /**
     * Log a warning message.
     *
     * @param msg the message
     */
    public void warn(String msg) {
        log(WARN, msg);
    }

    /**
     * Log a warning message with a throwable.
     *
     * @param msg the message
     * @param t the throwable
     */
    public void warn(String msg, Throwable t) {
        log(WARN, msg, t);
    }

    /**
     * Log an error message.
     *
     * @param msg the message
     */
    public void error(String msg) {
        log(ERROR, msg);
    }

    /**
     * Log an error message with a throwable.
     *
     * @param msg the message
     * @param t the throwable
     */
    public void error(String msg, Throwable t) {
        log(ERROR, msg, t);
    }

    /**
     * Get the minimum priority for logging.
     *
     * @return the minimum priority
     */
    public int getMinimumPriority() {
        return _minPriority;
    }

    /**
     * Set the minimum priority for logging.
     *
     * @param priority the minimum priority
     */
    public void setMinimumPriority(int priority) {
        _minPriority = priority;
    }

    /**
     * Check if messages at the given priority should be logged.
     *
     * @param priority the priority level
     * @return true if messages at this priority will be logged
     */
    public boolean shouldLog(int priority) {
        return priority >= _minPriority;
    }

    /**
     * Check if DEBUG level logging is enabled.
     *
     * @since 0.9.20
     * @return whether debug
     */
    public boolean shouldDebug() {
        return DEBUG >= _minPriority;
    }

    /**
     * Check if INFO level logging is enabled.
     *
     * @since 0.9.20
     * @return whether info
     */
    public boolean shouldInfo() {
        return INFO >= _minPriority;
    }

    /**
     * Check if WARN level logging is enabled.
     *
     * @since 0.9.20
     * @return whether warn
     */
    public boolean shouldWarn() {
        return WARN >= _minPriority;
    }

    /**
     * Check if ERROR level logging is enabled.
     *
     * @since 0.9.20
     * @return whether error
     */
    public boolean shouldError() {
        return ERROR >= _minPriority;
    }

    /**
     * logs a loop when closing a resource with level DEBUG
     * This method is for debugging purposes only and
     * is subject to change or removal w/o notice.
     * NOT a supported API.
     *
     * @param desc vararg description
     * @since 0.9.8
     */
    public void logCloseLoop(Object... desc) {
        logCloseLoop(Log.DEBUG, desc);
    }

    /**
     * Logs a close loop when closing a resource
     * This method is for debugging purposes only and
     * is subject to change or removal w/o notice.
     * NOT a supported API.
     *
     * @param desc vararg description of the resource
     * @param level level at which to log
     * @since 0.9.8
     */
    public void logCloseLoop(int level, Object... desc) {
        if (!shouldLog(level)) {
            return;
        }
        // catenate all toString()s
        StringBuilder builder = new StringBuilder();
        builder.append("close() loop in");
        for (Object o : desc) {
            builder.append(" ");
            builder.append(String.valueOf(o));
        }
        Exception e = new Exception("check stack trace") {
            /**
             * fillInStackTrace.
             */
            @Override
            public Throwable fillInStackTrace() {
                return this;
            }
        };
        log(level, builder.toString(), e);
    }

    /**
     * Get the logger name.
     *
     * @return the name
     */
    public String getName() {
        if (_className != null) {
            return _className;
        }
        return _name;
    }

    /**
     * Returns the LogScope (private class).
     *
     * @return the LogScope
     */
    public Object getScope() {
        return _scope;
    }

    /**
     * Get the scope string for a name and class.
     *
     * @param name the logger name
     * @param cls the class
     * @return the scope string
     */
    static String getScope(String name, Class<?> cls) {
        if ((name == null) && (cls == null)) {
            return "f00";
        }
        if (cls == null) {
            return name;
        }
        if (name == null) {
            return cls.getName();
        }
        return name + "" + cls.getName();
    }

    private static final class LogScope {
        private final String _scopeCache;

        /**
         * @param name the logger name
         * @param cls the class
         */
        public LogScope(String name, Class<?> cls) {
            _scopeCache = getScope(name, cls);
        }

        /**
         * Based on the scope cache string.
         * @return whether h code is present
         */
        @Override
        public int hashCode() {
            return _scopeCache.hashCode();
        }

        /**
         * Compare scope cache strings.
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            LogScope other = (LogScope) obj;
            return _scopeCache.equals(other._scopeCache);
        }
    }
}
