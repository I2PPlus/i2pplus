/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */
package org.klomp.snark;

import java.util.Map;
import java.util.Properties;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Base64;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Periodically ramp the tunnel count with connected peers, and reduce it once nothing is running.
 * We can't use the I2CP idle detector because it's based on traffic, so DHT and announces would
 * keep it non-idle.
 *
 * <p>The floor is keyed on whether a torrent is *running*, not on the peer count: a started
 * torrent with no peers yet is still announcing, still DHT-serving and still holding a LeaseSet,
 * and dropping its tunnels out from under it would stall every one of those. Peers only ever
 * raise a count above its floor.
 *
 * <p>Each session is floored independently at {@link #MIN_TUNNEL_QTY} in each direction. In
 * single-destination mode that is one shared session, which also carries the trackers, the DHT
 * and the announces, so it is floored whenever any torrent runs at all. Under multi-dest the
 * shared session is joined by one destination per pool (or per torrent), and each of those keeps
 * its own floor for as long as it still has torrents assigned - a pool is never dragged below 2/2
 * because a sibling pool is busy, nor raised because one is.
 *
 * <p>Reducing is hysteretic and one-directional per check: growth applies immediately, while a
 * reduction has to be wanted for {@link #SHRINK_HYSTERESIS} consecutive checks. BitTorrent peer
 * counts swing across any threshold within a few seconds, so recomputing a target from
 * {@code peerCount / 2} every {@link #CHECK_TIME} ms and pushing it at the router made the pool
 * oscillate, and every oscillation is a ReconfigureSessionMessage that destroys the surplus
 * tunnels outright rather than letting them expire.
 *
 * @since 0.9.7
 */
class IdleChecker extends SimpleTimer2.TimedEvent {

    private final I2PSnarkUtil _util;
    private final PeerCoordinatorSet _pcs;
    private final Log _log;
    /** Consecutive checks in which no torrent was running; drives the shrink hysteresis. */
    private int _noRunningConsec;
    /** Quantity last pushed to the shared session, which is also its floor. */
    private int _sharedQuantity = MIN_TUNNEL_QTY;
    private final Object _lock = new Object();

    private static final long CHECK_TIME = (long) 63 * 1000;
    /**
     * Consecutive checks with no running torrent required before reducing. Growth is immediate;
     * only the destructive direction waits.
     */
    private static final int SHRINK_HYSTERESIS = 3;
    /** Connected peers per tunnel; two peers share one tunnel in each direction. */
    private static final int PEERS_PER_TUNNEL = 2;
    /**
     * Tunnels held in each direction while any torrent is running. Matches the cap
     * {@link I2PSnarkUtil} applies when the session is created, so the floor is never a surprise.
     */
    private static final int MIN_TUNNEL_QTY = 2;
    /** {@link #decideQuantity} return value meaning the session should be left alone. */
    static final int NO_CHANGE = -1;

    /**
     * Caller must schedule
     *
     * @param mgr the SnarkManager providing the util and owning this timer
     * @param pcs the peer set whose connectivity state decides whether tunnels may be released
     */
    public IdleChecker(SnarkManager mgr, PeerCoordinatorSet pcs) {
        super(mgr.util().getContext().simpleTimer2());
        _util = mgr.util();
        _log = _util.getContext().logManager().getLog(IdleChecker.class);
        _pcs = pcs;
    }

    @Override
    public void timeReached() {
        synchronized (_lock) {
            locked_timeReached();
        }
    }

    private void locked_timeReached() {
        if (_util.connected()) {
            // A started-but-peerless torrent still needs its tunnels, so activity is keyed on the
            // coordinator being live rather than on the peer count. Counting the coordinators up
            // front also means the pool grouping below is not recomputed per candidate.
            int poolCount = _util.getMultiDest() ? _util.getPoolCount() : 0;
            // One flag per pool slot, plus one for the dedicated (pool-less) destinations, so a
            // single pass over the coordinators classifies every destination at once instead of
            // re-deriving the pool index per torrent per pool below.
            boolean[] poolHasRunning = new boolean[poolCount + 1];
            final int dedicatedSlot = poolCount;
            boolean anyRunning = false;
            int peerCount = 0;
            for (PeerCoordinator pc : _pcs) {
                if (pc.halted()) {
                    continue;
                }
                anyRunning = true;
                peerCount += pc.getPeers();
                if (poolCount > 0) {
                    int idx = _util.getPoolIndex(pc.getInfoHash());
                    poolHasRunning[idx >= 0 && idx < poolCount ? idx : dedicatedSlot] = true;
                }
            }
            _noRunningConsec = anyRunning ? 0 : _noRunningConsec + 1;

            // The shared session carries the trackers, the DHT and the announces, so it holds the
            // floor whenever anything at all is running.
            applyToSharedSession(anyRunning, peerCount);

            // Under multi-dest each pool carries its own torrents, and each is floored on its own
            // peers rather than inheriting the shared session's count.
            if (poolCount > 0) {
                for (TorrentDest td : _util.getTorrentDests()) {
                    if (td == null) {
                        continue;
                    }
                    int idx = td.getPoolIndex();
                    int slot = idx >= 0 && idx < poolCount ? idx : dedicatedSlot;
                    // A pool with a live torrent keeps its floor even before a peer connects; a
                    // pool with none sheds its tunnels.
                    boolean live = poolHasRunning[slot];
                    applyToPool(td, live, peersIn(td, idx));
                }
            }
        } else {
            // Not connected: nothing to hold open, and the next session starts at the floor.
            _noRunningConsec = 0;
            _sharedQuantity = MIN_TUNNEL_QTY;
        }
        schedule(CHECK_TIME);
    }

    /**
     * Applies the decided quantity to the shared session, if it changed.
     *
     * @param anyRunning whether any torrent is running
     * @param peerCount peers across all running torrents
     */
    private void applyToSharedSession(boolean anyRunning, int peerCount) {
        int current = _sharedQuantity;
        int target = decideQuantity(anyRunning ? peerCount : 0, current, _noRunningConsec);
        if (target == NO_CHANGE) {
            return;
        }
        if (target < current && _util.isLookupPending()) {
            // A tracker lookup is answered over this same session, so hold the floor for as long
            // as one is outstanding. Letting the count fall now would leave the lookup with no
            // usable path, which is what makes a tracker resubmit and re-enter this branch.
            return;
        }
        if (target > current) {
            setTunnels(target, true);
        } else {
            reduceTunnels(current);
        }
    }

    /**
     * Applies the decided quantity to one pool's own session, if it changed.
     *
     * @param td the pool
     * @param live whether the pool has a running torrent
     * @param peers peers connected across the pool's torrents
     */
    private void applyToPool(TorrentDest td, boolean live, int peers) {
        int current = td.getAppliedTunnelQuantity();
        int target = decideQuantity(live ? peers : 0, current, _noRunningConsec);
        if (target == NO_CHANGE) {
            return;
        }
        setTunnels(td, target, target > current);
    }

    /**
     * Connected peers belonging to torrents assigned to a pool, so each pool's tunnel count is
     * driven by its own members rather than by the whole client.
     *
     * @param td the pool
     * @return peers connected across the pool's torrents
     */
    private int peersIn(TorrentDest td, int poolIndex) {
        int count = 0;
        for (PeerCoordinator pc : _pcs) {
            if (pc.halted()) {
                continue;
            }
            if (poolIndex < 0) {
                // A dedicated destination serves exactly one torrent.
                if (Base64.encode(pc.getInfoHash()).equals(td.getKey())) {
                    count += pc.getPeers();
                }
            } else if (_util.getPoolIndex(pc.getInfoHash()) == poolIndex) {
                count += pc.getPeers();
            }
        }
        return count;
    }

    /**
     * The tunnel count to hold in each direction given the connected peers, never below the floor.
     *
     * @param peerCount total connected peers across all running torrents
     * @return the desired quantity in each direction, at least {@link #MIN_TUNNEL_QTY}
     */
    static int desiredQuantity(int peerCount) {
        return Math.max(peerCount / PEERS_PER_TUNNEL, MIN_TUNNEL_QTY);
    }

    /**
     * Decide whether to reconfigure the session's tunnel quantity.
     *
     * <p>Pure decision logic, split out so it can be exercised without a router. The quantity
     * currently applied is taken as a parameter rather than read from the configured options:
     * {@link I2PSnarkUtil} caps the session at {@link #MIN_TUNNEL_QTY} when it is created, while
     * the configured options still read the operator's larger figure. Comparing against the
     * configured value made every check believe the pool was oversized and reissue a
     * reconfiguration, so the applied quantity is passed in.
     *
     * @param peerCount total connected peers across all running torrents
     * @param currentQuantity quantity most recently applied to the session
     * @param noRunningConsec consecutive checks that have found no running torrent
     * @return the new quantity to apply in each direction, or {@link #NO_CHANGE} to leave the
     *         session alone
     */
    static int decideQuantity(int peerCount, int currentQuantity, int noRunningConsec) {
        int desired = desiredQuantity(peerCount);
        if (desired > currentQuantity) {
            // Growth is cheap and keeps up with a swarm filling up; apply it at once.
            return desired;
        }
        if (peerCount > 0 || noRunningConsec < SHRINK_HYSTERESIS) {
            // Peers present, or the reduction has not been wanted for long enough. Peer counts
            // cross any threshold within seconds, so shrinking on a single low reading tore the
            // pool down and rebuilt it again on the next swing.
            return NO_CHANGE;
        }
        if (desired == currentQuantity) {
            // Already where the reduction would land. Re-sending the same count still costs a
            // ReconfigureSessionMessage, and the router rebuilds the pool either way.
            return NO_CHANGE;
        }
        return desired;
    }

    /**
     * Reduces the shared session to the floor of 2 in / 2 out once nothing is running, so tracker
     * and DHT traffic keep a usable path while idle. Never re-sends a value the session already
     * holds, because the reconfigure handler rebuilds the pool regardless of whether anything
     * changed.
     *
     * @param current the quantity the shared session currently holds
     */
    private void reduceTunnels(int current) {
        if (current <= MIN_TUNNEL_QTY) {
            return;
        }
        String msg =
                "Connection is idle -> Reducing inbound / outbound tunnel count to "
                        + MIN_TUNNEL_QTY
                        + "...";
        if (_log.shouldInfo()) {
            _log.info("[I2PSnark] " + msg);
        }
        if (!_util.getContext().isRouterContext()) {
            System.out.println(" • " + msg);
        }
        setTunnels(MIN_TUNNEL_QTY, false);
    }

    /**
     * Apply a quantity to the shared session, logging growth. The shared session also carries the
     * trackers, DHT and announces, so it is floored whenever any torrent is running.
     *
     * @param target the new quantity in each direction
     * @param increased whether this is a growth, for logging
     */
    private void setTunnels(int target, boolean increased) {
        Map<String, String> opts = _util.getI2CPOptions();
        int inB = backupQuantity(opts.get("inbound.backupQuantity"));
        int outB = backupQuantity(opts.get("outbound.backupQuantity"));
        applyTunnels(target, target, inB, outB, _util.getSocketManager());
        _sharedQuantity = target;
        if (increased) {
            logGrowth(target);
        }
    }

    /**
     * Apply a quantity to one pool's own session. Each pool is floored independently, so this
     * never writes to the shared session or to any sibling pool.
     *
     * @param td the pool
     * @param target the new quantity in each direction
     * @param increased whether this is a growth, for logging
     */
    private void setTunnels(TorrentDest td, int target, boolean increased) {
        Map<String, String> opts = _util.getI2CPOptions();
        int inB = backupQuantity(opts.get("inbound.backupQuantity"));
        int outB = backupQuantity(opts.get("outbound.backupQuantity"));
        applyTunnels(target, target, inB, outB, td.getSocketManager());
        td.setAppliedTunnelQuantity(target);
        if (increased) {
            logGrowth(target);
        }
    }

    /**
     * Parse a configured backup quantity, defaulting to zero when absent or unparseable.
     *
     * @param raw the configured value, may be null
     * @return the backup quantity, never negative
     */
    private static int backupQuantity(String raw) {
        try {
            int v = Integer.parseInt(raw);
            return v > 0 ? v : 0;
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    /**
     * Note a tunnel increase for the console or the standalone log.
     *
     * @param target the new quantity in each direction
     */
    private void logGrowth(int target) {
        if (_log.shouldInfo()) {
            _log.info(
                    "Peer activity detected -> Increasing tunnel count to "
                            + target
                            + " inbound / "
                            + target
                            + " outbound");
        }
        if (!_util.getContext().isRouterContext()) {
            System.out.println(
                    " • Peer activity detected -> Increasing tunnel count to "
                            + target
                            + " inbound / "
                            + target
                            + " outbound");
        }
    }

    /**
     * Send one reconfiguration to a single session. Deliberately one session at a time: batching
     * every pool onto one ReconfigureSessionMessage is what made a busy sibling look like an
     * idle one.
     *
     * @param inQty the inbound tunnel quantity
     * @param outQty the outbound tunnel quantity
     * @param inBackup the inbound backup quantity
     * @param outBackup the outbound backup quantity
     * @param mgr the manager of the session, or null
     */
    private void applyTunnels(
            int inQty, int outQty, int inBackup, int outBackup, I2PSocketManager mgr) {
        if (mgr == null) {
            return;
        }
        I2PSession sess = mgr.getSession();
        if (sess == null) {
            return;
        }
        if (_log.shouldInfo()) {
            _log.info(
                    "Tunnel settings updated: ["
                            + inQty
                            + " inbound / "
                            + outQty
                            + " outbound / "
                            + inBackup
                            + " inbound backup / "
                            + outBackup
                            + " outbound backup]");
        }
        Properties newProps = new Properties();
        newProps.setProperty("inbound.quantity", Integer.toString(inQty));
        newProps.setProperty("outbound.quantity", Integer.toString(outQty));
        newProps.setProperty("inbound.backupQuantity", Integer.toString(inBackup));
        newProps.setProperty("outbound.backupQuantity", Integer.toString(outBackup));
        sess.updateOptions(newProps);
    }

    /**
     * The inbound tunnel count currently applied to the shared session.
     *
     * @return the active inbound count, never below {@link #MIN_TUNNEL_QTY}
     * @since 0.9.66+
     */
    public int getActiveInboundCount() {
        return _sharedQuantity;
    }

    /**
     * The outbound tunnel count currently applied to the shared session. Inbound and outbound are
     * always pushed together, so this matches {@link #getActiveInboundCount()}.
     *
     * @return the active outbound count, never below {@link #MIN_TUNNEL_QTY}
     * @since 0.9.66+
     */
    public int getActiveOutboundCount() {
        return _sharedQuantity;
    }
}
