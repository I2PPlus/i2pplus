/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */
package org.klomp.snark;

import java.util.Map;
import java.util.Properties;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Periodically check for idle condition based on connected peers, and reduce/restore tunnel count
 * as necessary. We can't use the I2CP idle detector because it's based on traffic, so DHT and
 * announces would keep it non-idle.
 *
 * @since 0.9.7
 */
class IdleChecker extends SimpleTimer2.TimedEvent {

    private final I2PSnarkUtil _util;
    private final PeerCoordinatorSet _pcs;
    private final Log _log;
    private int _consec;
    private boolean _isIdle;
    private String _lastInbound = DEFAULT_QTY;
    private String _lastOutbound = DEFAULT_QTY;
    private final Object _lock = new Object();

    private static final long CHECK_TIME = (long) 63 * 1000;
    private static final int MAX_CONSEC_IDLE = 4;
    private static final String DEFAULT_QTY = "2";

    /** Caller must schedule */
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
            int peerCount = 0;
            for (PeerCoordinator pc : _pcs) {
                if (!pc.halted()) {
                    peerCount += pc.getPeers();
                }
            }

            if (peerCount > 0) {
                restoreTunnels(peerCount);
            } else {
                if (!_isIdle) {
                    if (_consec++ >= MAX_CONSEC_IDLE) {
                        reduceTunnels();
                    } else {
                        restoreTunnels(1);
                    } // pretend we have one peer for now
                }
            }
        } else {
            _isIdle = false;
            _consec = 0;
            _lastInbound = DEFAULT_QTY;
            _lastOutbound = DEFAULT_QTY;
        }
        schedule(CHECK_TIME);
    }

    /**
     * Reduces tunnel count to the minimum of 2 in / 2 out when idle, so tracker
     * and DHT traffic keep a usable path while no peers are connected.
     */
    private void reduceTunnels() {
        _isIdle = true;
        boolean isStandalone = !_util.getContext().isRouterContext();
        int ibtunnels;
        int obtunnels;
        try {
            ibtunnels = Integer.parseInt(_util.getI2CPOptions().get("inbound.quantity"));
        } catch (NumberFormatException nfe) {
            ibtunnels = 1;
        }
        try {
            obtunnels = Integer.parseInt(_util.getI2CPOptions().get("outbound.quantity"));
        } catch (NumberFormatException nfe) {
            obtunnels = 1;
        }
        int minTunnels = 2;
        if (ibtunnels > minTunnels || obtunnels > minTunnels) {
            String msg =
                    "Connection is idle -> Reducing inbound / outbound tunnel count to "
                            + minTunnels
                            + "...";
            if (_log.shouldInfo()) {
                _log.info("[I2PSnark] " + msg);
            }
            if (isStandalone) {
                System.out.println(" • " + msg);
            }
            setTunnels("2", "2", "0", "0");
        }
    }

    /**
     * Restore or adjust tunnel count based on current peer count
     *
     * @param peerCount greater than zero
     */
    private void restoreTunnels(int peerCount) {
        _isIdle = false;
        boolean isStandalone = !_util.getContext().isRouterContext();
        Map<String, String> opts = _util.getI2CPOptions();
        String inQty = opts.get("inbound.quantity");

        if (inQty == null) {
            inQty = Integer.toString(SnarkManager.DEFAULT_TUNNEL_QUANTITY);
        }
        String outQty = opts.get("outbound.quantity");
        if (outQty == null) {
            outQty = Integer.toString(SnarkManager.DEFAULT_TUNNEL_QUANTITY);
        }
        String inBackup = opts.get("inbound.backupQuantity");
        if (inBackup == null) {
            inBackup = "0";
        }
        String outBackup = opts.get("outbound.backupQuantity");
        if (outBackup == null) {
            outBackup = "0";
        }

        // We don't need more tunnels than we have peers, reduce if so reduce to max(peerCount / 2,
        // 2)
        int inTunnels;
        int outTunnels;
        try {
            inTunnels = Integer.parseInt(inQty);
        } catch (NumberFormatException nfe) {
            inTunnels = 3;
        }
        try {
            outTunnels = Integer.parseInt(outQty);
        } catch (NumberFormatException nfe) {
            outTunnels = 3;
        }
        int target = Math.max(peerCount / 2, 2);

        boolean increasedCount = false;
        if (target > inTunnels || target > outTunnels) {
            increasedCount = true;
        }

        if (target < inTunnels && inTunnels > 2) {
            inTunnels = target;
            inQty = Integer.toString(inTunnels);
        }
        if (target < outTunnels && outTunnels > 2) {
            outTunnels = target;
            outQty = Integer.toString(outTunnels);
        }
        if (!(_lastInbound.equals(inQty) && _lastOutbound.equals(outQty))) {
            setTunnels(inQty, outQty, inBackup, outBackup);
            if (increasedCount) {
                String msg =
                        "Peer activity detected -> Increasing tunnel count to "
                                + inQty
                                + " inbound / "
                                + outQty
                                + " outbound";
                if (_log.shouldInfo()) {
                    _log.info(msg);
                }
                if (isStandalone) {
                    System.out.println(" • " + msg);
                }
            }
        }
    }

    /**
     * Configure the inbound/outbound tunnel counts and backup quantities on the shared session and
     * every torrent's own session (multi-dest), so they all scale with usage and shrink back
     * down when idle.
     *
     * @param inboundQty the inbound tunnel quantity
     * @param outboundQty the outbound tunnel quantity
     * @param inboundBackup the inbound backup quantity
     * @param outboundBackup the outbound backup quantity
     */
    private void setTunnels(
            String inboundQty,
            String outboundQty,
            String inboundBackup,
            String outboundBackup) {
        _consec = 0;
        Properties newProps = new Properties();
        newProps.setProperty("inbound.quantity", inboundQty);
        newProps.setProperty("outbound.quantity", outboundQty);
        newProps.setProperty("inbound.backupQuantity", inboundBackup);
        newProps.setProperty("outbound.backupQuantity", outboundBackup);
        if (_log.shouldInfo()) {
            _log.info(
                    "Tunnel settings updated: ["
                            + inboundQty
                            + " inbound / "
                            + outboundQty
                            + " outbound / "
                            + inboundBackup
                            + " inbound backup / "
                            + outboundBackup
                            + " outbound backup]");
        }
        applyTunnels(newProps, _util.getSocketManager());
        for (TorrentDest td : _util.getTorrentDests()) {
            applyTunnels(newProps, td.getSocketManager());
        }
        _lastInbound = inboundQty;
        _lastOutbound = outboundQty;
    }

    /**
     * Apply the tunnel quantity options to one session, if connected.
     *
     * @param settings the tunnel quantity options to apply
     * @param mgr the manager of the session, or null
     */
    private void applyTunnels(Properties settings, I2PSocketManager mgr) {
        if (mgr == null) {
            return;
        }
        I2PSession sess = mgr.getSession();
        if (sess != null) {
            sess.updateOptions(settings);
        }
    }

    /**
     * Return the current inbound tunnel count
     *
     * @return the active inbound count
     * @since 0.9.66+
     */
    public int getActiveInboundCount() {
        try {
            return Integer.parseInt(_lastInbound);
        } catch (NumberFormatException nfe) {
            return 2;
        }
    }

    /**
     * Return the current outbound tunnel count
     *
     * @return the active outbound count
     * @since 0.9.66+
     */
    public int getActiveOutboundCount() {
        try {
            return Integer.parseInt(_lastOutbound);
        } catch (NumberFormatException nfe) {
            return 2;
        }
    }
}
