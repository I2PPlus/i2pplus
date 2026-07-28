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
     */
    public LogLimit(String name, int limit) {
        _rootName = name;
        _limit = limit;
    }

    /**
     * @return the root name
     */
    public String getRootName() {
        return _rootName;
    }

    /**
     * @return the limit
     */
    public int getLimit() {
        return _limit;
    }

    /**
     * setLimit.
     */
    public void setLimit(int limit) {
        _limit = limit;
    }

    /**
     * matches.
     */
    public boolean matches(Log log) {
        String name = log.getName();
        if (name == null) return false;
        // exact match or higher in class hierarchy
        // no longer allow foo.bar to match foo.barf
        return name.startsWith(_rootName) && (name.length() == _rootName.length() || name.charAt(_rootName.length()) == '.');
    }

    /**
     * @return whether h code is present
     */
    @Override
    public int hashCode() {
        return _rootName.hashCode();
    }

    /**
     * equals.
     */
    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof LogLimit)) return false;
        return _rootName.equals(((LogLimit) o).getRootName());
    }
}
