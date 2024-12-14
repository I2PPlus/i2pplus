package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.util.Log;

/**
 *  Stores in-memory only. See extension.
 */
class TransientDataStore implements DataStore {
    protected final Log _log;
    private final ConcurrentHashMap<Hash, DatabaseEntry> _data;
    protected final RouterContext _context;

    public TransientDataStore(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _data = new ConcurrentHashMap<Hash, DatabaseEntry>(1024);
    }

    public boolean isInitialized() {return true;}
    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    public boolean enableReverseLookups() {return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);}
    public void stop() {_data.clear();}
    public void rescan() {}

    /**
     *  @return total size (RI and LS)
     *  @since 0.8.8
     */
    public int size() {return _data.size();}

    /**
     *  @return Unmodifiable view, not a copy
     */
    public Set<Hash> getKeys() {
        return Collections.unmodifiableSet(_data.keySet());
    }

    /**
     *  @return Unmodifiable view, not a copy
     *  @since 0.8.3
     */
    public Collection<DatabaseEntry> getEntries() {
        return Collections.unmodifiableCollection(_data.values());
    }

    /**
     *  @return Unmodifiable view, not a copy
     *  @since 0.8.3
     */
    public Set<Map.Entry<Hash, DatabaseEntry>> getMapEntries() {
        return Collections.unmodifiableSet(_data.entrySet());
    }

    /** for PersistentDataStore only - don't use here
      * @throws UnsupportedOperationException always
      */
    public DatabaseEntry get(Hash key, boolean persist) {
        throw new UnsupportedOperationException();
    }

    public DatabaseEntry get(Hash key) {
        if (key != null) {return _data.get(key);}
        else {return null;}
    }

    public boolean isKnown(Hash key) {
        return _data.containsKey(key);
    }

    public int countLeaseSets() {
        int count = 0;
        for (DatabaseEntry d : _data.values()) {
            if (d.isLeaseSet()) {count++;}
        }
        return count;
    }

    /** for PersistentDataStore only - don't use here
      * @throws UnsupportedOperationException always
      */
    public boolean put(Hash key, DatabaseEntry data, boolean persist) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @param data must be validated before here
     *  @return success
     */
    public boolean put(Hash key, DatabaseEntry data) {
        int type = data.getType();
        boolean isRI = type == DatabaseEntry.KEY_TYPE_ROUTERINFO;
        if (data == null) return false;
        if (_log.shouldDebug()) {
            _log.debug("Saving " + (isRI ? "RouterInfo" : "LeaseSet") + " [" +
                       (isRI ? key.toBase64().substring(0,6) : key.toBase32().substring(0,8)) + "] to persistent datastore...");
        }
        DatabaseEntry old = _data.putIfAbsent(key, data);
        boolean rv = false;
        if (isRI) {
            RouterInfo ri = (RouterInfo)data;
            String v = ri.getVersion();
            String caps = ri.getCapabilities();
            if (old != null) {
                RouterInfo ori = (RouterInfo) old;
                if (ri.getPublished() > ori.getPublished()) {
                    if (_log.shouldInfo())
                        _log.info("Received updated RouterInfo [" + key.toBase64().substring(0,6) + "] -> " + v + " / " + caps +
                                  "\n* Old: " + new Date(ori.getPublished()) + "\n* New: " + new Date(ri.getPublished()));
                    _data.put(key, data);
                    rv = true;
                }
            } else {
                if (_log.shouldInfo())
                    _log.info("Received new RouterInfo [" + key.toBase64().substring(0,6) + "] -> " + v + " / " + caps +
                              "\n* Published: " + new Date(ri.getPublished()));
                rv = true;
                long uptime = _context.router().getUptime();
                if (enableReverseLookups() && uptime > 30*1000) {
                    String ip = net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(ri));
                    ip = (ri != null) ? net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(ri)) : null;
                    String rl = ip != null ? _context.commSystem().getCanonicalHostName(ip) : null;
                }
            }
        } else if (DatabaseEntry.isLeaseSet(type)) {
            LeaseSet ls = (LeaseSet)data;
            String receivedAs = ls.getReceivedAsPublished() ? " -> Published via peer" : ls.getReceivedAsReply() ? " -> In response to query" : "";

            if (old != null) {
                LeaseSet ols = (LeaseSet) old;
                long oldDate, newDate;
                if (type != DatabaseEntry.KEY_TYPE_LEASESET &&
                ols.getType() != DatabaseEntry.KEY_TYPE_LEASESET) {
                    if (ls instanceof LeaseSet2 && ols instanceof LeaseSet2) {
                        LeaseSet2 ls2 = (LeaseSet2) ls;
                        LeaseSet2 ols2 = (LeaseSet2) ols;
                        oldDate = ols2.getPublished();
                        newDate = ls2.getPublished();
                    } else {throw new IllegalArgumentException("Expected LeaseSet2 but got a different type");}
                } else {
                    oldDate = ols.getEarliestLeaseDate();
                    newDate = ls.getEarliestLeaseDate();
                }

                if (newDate < oldDate) {
                    if (_log.shouldDebug()) {
                        _log.debug("Almost clobbered a LeaseSet! [" + key.toBase32().substring(0,8) +
                                   "]\n* Old: " + new Date(ols.getEarliestLeaseDate()) +
                                   "\n* New: " + new Date(ls.getEarliestLeaseDate()));
                    }
                } else if (newDate == oldDate) {
                    if (_log.shouldDebug()) {
                        _log.debug("Received duplicate LeaseSet [" + key.toBase32().substring(0,8) + "] -> Not updating");
                    }
                } else {
                    if (_log.shouldInfo()) {
                        _log.info("Received updated LeaseSet [" + key.toBase32().substring(0,8) + "]" + receivedAs +
                                   "\n* Old: " + new Date(ols.getEarliestLeaseDate()) +
                                   "\n* New: " + new Date(newDate));
                    }
                    _data.put(key, data);
                    rv = true;
                }
            } else {
                if (_log.shouldInfo()) {
                    _log.info("Received new LeaseSet [" + key.toBase32().substring(0,8) + "]" + receivedAs +
                              "\n* Expires: " + new Date(ls.getEarliestLeaseDate()));
                }
                rv = true;
            }
        }
        return rv;
    }

    /*
     *  Unconditionally store, bypass all newer/older checks
     *
     *  @return success
     *  @param key non-null
     *  @param data non-null
     *  @since 0.9.64
     */
    @Override
    public boolean forcePut(Hash key, DatabaseEntry data) {
        _data.put(key, data);
        return true;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("Transient DataStore: ").append(_data.size()).append("\n* Keys: ");
        for (Map.Entry<Hash, DatabaseEntry> e : _data.entrySet()) {
            Hash key = e.getKey();
            DatabaseEntry dp = e.getValue();
            buf.append("\n* Key: ").append(key.toString()).append("\n* Content: ").append(dp.toString());
        }
        buf.append("\n");
        return buf.toString();
    }

    /** for PersistentDataStore only - don't use here
      * @throws UnsupportedOperationException always
      */
    public DatabaseEntry remove(Hash key, boolean persist) {
        throw new UnsupportedOperationException();
    }

    public DatabaseEntry remove(Hash key) {
        return _data.remove(key);
    }
}
