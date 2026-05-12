package net.i2p.router.networkdb.kademlia;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.TreeSet;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.kademlia.XORComparator;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Manages state for network database search operations.
 * <p>
 * Tracks peer interaction status, timing information, and search progress
 * for iterative Kademlia searches. Maintains separate sets for pending,
 * attempted, failed, successful, and replied peers to coordinate
 * concurrent search operations and prevent duplicate queries.
 * <p>
 * Thread-safe implementation using synchronized collections and atomic
 * counters for safe concurrent access from multiple search jobs.
 *
 * @since 0.9.16
 */
class SearchState {
    private final RouterContext _context;
    private final Set<Hash> _pendingPeers;
    private final Map<Hash, Long> _pendingPeerTimes;
    private final Set<Hash> _attemptedPeers;
    private final Set<Hash> _failedPeers;
    private final Set<Hash> _successfulPeers;
    private final Set<Hash> _repliedPeers;
    private final Hash _searchKey;
    private volatile long _completed;
    private volatile long _started;
    private volatile boolean _aborted;
    private final Log _log;
    private final Map<Hash, LeaseSet> _leaseSetResponses;
    private long _firstLeaseSetTime;

    private final AtomicInteger _initialResponseCount = new AtomicInteger();
    private volatile long _initialResponseStart;
    private static final int INITIAL_RESPONSE_TARGET = 3;
    private static final long INITIAL_RESPONSE_TIMEOUT = 3 * 1000;
    private volatile long _storedLeaseDate;

    public SearchState(RouterContext context, Hash key) {
        _log = context.logManager().getLog(SearchState.class);
        _context = context;
        _searchKey = key;
        _pendingPeers = new HashSet<Hash>(16);
        _attemptedPeers = new HashSet<Hash>(16);
        _failedPeers = new HashSet<Hash>(16);
        _successfulPeers = new HashSet<Hash>(16);
        _pendingPeerTimes = new HashMap<Hash, Long>(16);
        _repliedPeers = new HashSet<Hash>(16);
        _leaseSetResponses = new HashMap<Hash, LeaseSet>(8);
        _firstLeaseSetTime = 0;
        _completed = -1;
        _started = _context.clock().now();
    }

    public Hash getTarget() {return _searchKey;}
    public Set<Hash> getPending() {
        synchronized (_pendingPeers) {
            return new HashSet<Hash>(_pendingPeers);
        }
    }
    public Set<Hash> getAttempted() {
        synchronized (_attemptedPeers) {
            return new HashSet<Hash>(_attemptedPeers);
        }
    }
    public Set<Hash> getClosestAttempted(int max) {
        synchronized (_attemptedPeers) {
            return locked_getClosest(_attemptedPeers, max, _searchKey);
        }
    }

    private Set<Hash> locked_getClosest(Set<Hash> peers, int max, Hash target) {
        if (_attemptedPeers.size() <= max) {return new HashSet<Hash>(_attemptedPeers);}
        TreeSet<Hash> closest = new TreeSet<Hash>(new XORComparator<Hash>(target));
        closest.addAll(_attemptedPeers);
        Set<Hash> rv = new HashSet<Hash>(max);
        int i = 0;
        for (Iterator<Hash> iter = closest.iterator(); iter.hasNext() && i < max; i++) {
            rv.add(iter.next());
        }
        return rv;
    }

    public boolean wasAttempted(Hash peer) {
        synchronized (_attemptedPeers) {
            return _attemptedPeers.contains(peer);
        }
    }
    public Set<Hash> getSuccessful() {
        synchronized (_successfulPeers) {
            return new HashSet<Hash>(_successfulPeers);
        }
    }
    public Set<Hash> getFailed() {
        synchronized (_failedPeers) {
            return new HashSet<Hash>(_failedPeers);
        }
    }

    public boolean completed() {return _completed != -1;}

    public void complete() {
        _completed = _context.clock().now();
        synchronized (_leaseSetResponses) {
            _leaseSetResponses.clear();
            _firstLeaseSetTime = 0;
        }
    }

    /** @since 0.9.16 */
    public boolean isAborted() {return _aborted;}

    /** @since 0.9.16 */
    public void abort() {_aborted = true;}

    public long getWhenStarted() {return _started;}
    public long getWhenCompleted() {return _completed;}

    public void addPending(Collection<Hash> pending) {
        synchronized (_pendingPeers) {
            _pendingPeers.addAll(pending);
            for (Hash peer : pending) {
                _pendingPeerTimes.put(peer, Long.valueOf(_context.clock().now()));
            }
        }
        synchronized (_attemptedPeers) {_attemptedPeers.addAll(pending);}
    }
    public void addPending(Hash peer) {
        synchronized (_pendingPeers) {
            _pendingPeers.add(peer);
            _pendingPeerTimes.put(peer, Long.valueOf(_context.clock().now()));
        }
        synchronized (_attemptedPeers) {_attemptedPeers.add(peer);}
    }
    /** we didn't actually want to add this peer as part of the pending list... */
    public void removePending(Hash peer) {
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            _pendingPeerTimes.remove(peer);
        }
        synchronized (_attemptedPeers) {_attemptedPeers.remove(peer);}
    }

    /** how long did it take to get the reply, or -1 if we don't know */
    public long dataFound(Hash peer) {
        long rv = -1;
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            Long when = _pendingPeerTimes.remove(peer);
            if (when != null) {rv = _context.clock().now() - when.longValue();}
        }
        synchronized (_successfulPeers) {_successfulPeers.add(peer);}
        return rv;
    }

    /** how long did it take to get the reply, or -1 if we dont know */
    public long replyFound(Hash peer) {
        synchronized (_repliedPeers) {_repliedPeers.add(peer);}
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            Long when = _pendingPeerTimes.remove(peer);
            if (when != null) {return _context.clock().now() - when.longValue();}
            else {return -1;}
        }
    }

    public Set<Hash> getRepliedPeers() {
        synchronized (_repliedPeers) {return new HashSet<Hash>(_repliedPeers);}
    }

    public void replyTimeout(Hash peer) {
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            _pendingPeerTimes.remove(peer);
        }
        synchronized (_failedPeers) {_failedPeers.add(peer);}
    }

    public void addLeaseSetResponse(Hash peer, LeaseSet ls) {
        long leaseDate = ls.getLatestLeaseDate();
        synchronized (_leaseSetResponses) {
            if (_firstLeaseSetTime == 0) {
                _firstLeaseSetTime = leaseDate;
                if (_log.shouldInfo()) {
                    _log.info("First LeaseSet response from [" + peer.toBase64().substring(0,6)
                              + "] with latest lease date: " + leaseDate);
                }
            }
            _leaseSetResponses.put(peer, ls);
        }
    }

    public LeaseSet getNewestLeaseSet() {
        synchronized (_leaseSetResponses) {
            if (_leaseSetResponses.isEmpty()) {
                return null;
            }
            LeaseSet newest = null;
            long newestDate = 0;
            for (Map.Entry<Hash, LeaseSet> entry : _leaseSetResponses.entrySet()) {
                LeaseSet ls = entry.getValue();
                long date = ls.getLatestLeaseDate();
                if (date > newestDate) {
                    newestDate = date;
                    newest = ls;
                }
            }
            if (newest != null && newestDate > _firstLeaseSetTime) {
                if (_log.shouldInfo()) {
                    _log.info("Found newer LeaseSet, updating from " + _firstLeaseSetTime + " to " + newestDate);
                }
            }
            return newest;
        }
    }

    public void addInitialLeaseSetResponse(Hash peer, LeaseSet ls) {
        long now = _context.clock().now();
        synchronized (_leaseSetResponses) {
            if (_initialResponseStart <= 0) {
                _initialResponseStart = now;
            }
            _initialResponseCount.incrementAndGet();
            if (_log.shouldInfo()) {
                _log.info("Initial LeaseSet response " + _initialResponseCount + " from [" + peer.toBase64().substring(0,6)
                          + "] with latest lease date: " + ls.getLatestLeaseDate());
            }
            _leaseSetResponses.put(peer, ls);
            _firstLeaseSetTime = ls.getLatestLeaseDate();
        }
    }

    public boolean shouldStoreInitial() {
        if (_initialResponseStart <= 0) {
            return false;
        }
        long now = _context.clock().now();
        return _initialResponseCount.get() >= INITIAL_RESPONSE_TARGET
               || (now - _initialResponseStart) >= INITIAL_RESPONSE_TIMEOUT;
    }

    public LeaseSet getBestInitialLeaseSet() {
        synchronized (_leaseSetResponses) {
            if (_leaseSetResponses.isEmpty()) {
                return null;
            }
            LeaseSet best = null;
            long bestDate = 0;
            for (Map.Entry<Hash, LeaseSet> entry : _leaseSetResponses.entrySet()) {
                LeaseSet ls = entry.getValue();
                long date = ls.getLatestLeaseDate();
                if (date > bestDate) {
                    bestDate = date;
                    best = ls;
                }
            }
            if (_log.shouldInfo() && best != null) {
                _log.info("Best initial LeaseSet with latest lease date: " + bestDate);
            }
            return best;
        }
    }

    public void clearInitialTracking() {
        synchronized (_leaseSetResponses) {
            _initialResponseStart = -1;
            _initialResponseCount.set(-1);
        }
    }

    public long getStoredLeaseDate() {
        return _storedLeaseDate;
    }

    public void setStoredLeaseDate(long date) {
        _storedLeaseDate = date;
    }

    public boolean shouldUpdateStored(LeaseSet ls) {
        return ls.getLatestLeaseDate() > _storedLeaseDate;
    }

    @Override
    public String toString() {
        Boolean debug = _log.shouldDebug();
        StringBuilder buf = new StringBuilder(256);
        buf.append(" Search for [").append(_searchKey.toBase64().substring(0,6)).append("]");
        if (_successfulPeers.size() <= 0) {buf.append(" in progress...");}
        else {buf.append(" completed");}
        if (_aborted) {buf.append(" aborted");}
        // this _should_ only show the following if debug logging is enabled, but currently it supresses it
        if (debug) {
            if (_attemptedPeers.size() > 0) {
                buf.append("\n* Queried: ");
                synchronized (_attemptedPeers) {
                    buf.append(_attemptedPeers.size()).append(' ');
                    for (Hash peer : _attemptedPeers) {
                        buf.append("[").append(peer.toBase64().substring(0,6)).append("] ");
                    }
                }
            }
            if (_pendingPeers.size() > 0) {
                buf.append("\n* Pending: ");
                synchronized (_pendingPeers) {
                    buf.append(_pendingPeers.size()).append(' ');
                    for (Hash peer : _pendingPeers) {
                        buf.append("[").append(peer.toBase64().substring(0,6)).append("] ");
                    }
                }
            }
            if (_failedPeers.size() > 0) {
                buf.append("\n* Failed: ");
                synchronized (_failedPeers) {
                    buf.append(_failedPeers.size()).append(' ');
                    for (Hash peer : _failedPeers) {
                        buf.append("[").append(peer.toBase64().substring(0,6)).append("] ");
                    }
                }
            }
            if (_successfulPeers.size() > 0) {
                buf.append("\n* Successful: ");
                synchronized (_successfulPeers) {
                    buf.append(_successfulPeers.size()).append(' ');
                    for (Hash peer : _successfulPeers) {
                        buf.append("[").append(peer.toBase64().substring(0,6)).append("] ");
                    }
                }
            }
        }
        return buf.toString();
    }
}
