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
 * Defines the log limit for a particular set of logs
 *
 */
class LogLimit {
    private final String _rootName;
    private int _limit;

    /**
     * LogLimit.
     *
     * @param name the root log name this override applies to, matched by class hierarchy
     * @param limit the minimum priority level, from {@link Log#toLevelString}, that
     * logs under this root name will emit
     */
    public LogLimit(String name, int limit) {
        _rootName = name;
        _limit = limit;
    }

    /**
     * Root name matched by this limit.
     *
     * @return the root name
     */
    public String getRootName() {
        return _rootName;
    }

    /**
     * Priority limit applied to matching logs.
     *
     * @return the limit
     */
    public int getLimit() {
        return _limit;
    }

    /**
     * Configure the priority limit.
     *
     * @param limit the minimum priority level that logs under this root name emit
     */
    public void setLimit(int limit) {
        _limit = limit;
    }

    /**
     * Whether the log matches this limit, by name or class hierarchy.
     *
     * @param log the log to test, whose class name is compared against the root name
     * @return true if this limit applies to that log, false if the name is absent or
     * is a different log entirely
     */
    public boolean matches(Log log) {
        String name = log.getName();
        if (name == null) return false;
        // exact match or higher in class hierarchy
        // no longer allow foo.bar to match foo.barf
        return name.startsWith(_rootName) && (name.length() == _rootName.length() || name.charAt(_rootName.length()) == '.');
    }

    /**
     * Hash code derived from the root name.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return _rootName.hashCode();
    }

    /**
     * Compare by root name.
     */
    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof LogLimit)) return false;
        return _rootName.equals(((LogLimit) o).getRootName());
    }
}
