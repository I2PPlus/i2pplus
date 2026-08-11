package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Keep track of a log entry, unformatted.
 *
 */
class LogRecord {
    private final long _date;
    private final Class<?> _source;
    private final String _name;
    private final String _threadName;
    private final int _priority;
    private final String _message;
    private final Throwable _throwable;

    /**
     * LogRecord.
     */
    public LogRecord(Class<?> src, String name, String threadName, int priority, String msg, Throwable t) {
        _date = Clock.getInstance().now();
        _source = src;
        _name = name;
        _threadName = threadName;
        _priority = priority;
        _message = msg;
        _throwable = t;
    }

    /**
     * Time the record was created.
     *
     * @return the date
     */
    public long getDate() {
        return _date;
    }

    /**
     * Class the record is from.
     *
     * @return the source
     */
    public Class<?> getSource() {
        return _source;
    }

    /**
     * Name the record is from.
     *
     * @return the source name
     */
    public String getSourceName() {
        return _name;
    }

    /**
     * Thread the record was created on.
     *
     * @return the thread name
     */
    public String getThreadName() {
        return _threadName;
    }

    /**
     * Log priority of the record.
     *
     * @return the priority
     */
    public int getPriority() {
        return _priority;
    }

    /**
     * Message text.
     *
     * @return the message
     */
    public String getMessage() {
        return _message;
    }

    /**
     * Throwable attached to the record, if any.
     *
     * @return the throwable
     */
    public Throwable getThrowable() {
        return _throwable;
    }

    private static final int MATCH_LEN = 40;

    /**
     *  Matches source class, first part of message string, and throwable class only.
     *  Used only by LogWriter to eliminate dups.
     *
     *  @since 0.9.3
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LogRecord)) return false;
        LogRecord r = (LogRecord) o;
        return _source == r._source && ((_message == null && r._message == null) || (_message != null && r._message != null && ((_message.length() <= MATCH_LEN) ? _message.equals(r._message) : _message.regionMatches(0, r._message, 0, MATCH_LEN)))) && ((_throwable == null && r._throwable == null) || (_throwable != null && r._throwable != null && _throwable.getClass() == r._throwable.getClass()));
    }

    /**
     * Hash code derived from the message.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return _message != null ? _message.hashCode() : 0;
    }
}
