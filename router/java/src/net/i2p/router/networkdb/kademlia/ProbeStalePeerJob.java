package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 * Probes peers to verify they are still alive, using DirectLookupJob to ask
 * for their RouterInfo.  If they respond, lastHeardFrom/lastHeardAbout are
 * updated, making them pass the proof-of-life check in
 * ProfileOrganizer.isSelectable().
 *
 * During startup, probes ALL peers aggressively to quickly identify which
 * cached routers are still online.  After startup, falls back to probing
 * only stale peers (old RouterInfo, no recent proof of life).
 *
 * @since 0.9.70
 */
class ProbeStalePeerJob extends JobImpl {
    private final Log _log;
    private final FloodfillNetworkDatabaseFacade _facade;
    private final Map<Hash, Long> _lastProbed;

    private static final long CYCLE_INTERVAL = 60 * 1000;
    private static final int MAX_PROBES_PER_CYCLE = 20;
    private static final long PROBE_COOLDOWN = 10 * 60 * 1000;
    private static final long PROOF_OF_LIFE_WINDOW_MS = 60 * 60 * 1000;
    private static final long STARTUP_BURST_PERIOD = 5 * 60 * 1000;

    public ProbeStalePeerJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ProbeStalePeerJob.class);
        _facade = facade;
        _lastProbed = new ConcurrentHashMap<Hash, Long>();
    }

    public String getName() { return "Probe Stale Peers"; }

    @Override
    public void runJob() {
        RouterContext ctx = getContext();
        long uptime = ctx.router().getUptime();
        long now = ctx.clock().now();

        List<Hash> candidates = new ArrayList<Hash>();
        long maxAge = ctx.getProperty(
            "profileOrganizer.maxRouterInfoAgeHours", 2) * 3600_000L;
        boolean isStartupBurst = uptime > 0 && uptime < STARTUP_BURST_PERIOD;

        for (Hash peer : ctx.profileOrganizer().selectAllPeers()) {
            if (candidates.size() >= MAX_PROBES_PER_CYCLE * 2) break;

            Long last = _lastProbed.get(peer);
            if (last != null && now - last < PROBE_COOLDOWN) continue;
            if (ctx.commSystem().isEstablished(peer)) continue;

            RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
            if (ri == null || ri.isHidden()) continue;

            if (isStartupBurst) {
                // During startup: probe all peers regardless of RouterInfo age.
                // Stale-only filtering would miss offline peers with fresh caches.
                PeerProfile prof = ctx.profileOrganizer().getProfile(peer);
                if (prof != null && prof.getPeerTestTimeAverage() > 0) {
                    // Already ping-tested in this session — skip
                    continue;
                }
                candidates.add(peer);
            } else {
                // Normal operation: only probe peers with stale RouterInfo
                // and no recent proof of life.
                if (ri.getPublished() > now - maxAge) continue;
                PeerProfile prof = ctx.profileOrganizer().getProfile(peer);
                if (prof != null) {
                    boolean alive = prof.getLastSendSuccessful() > now - PROOF_OF_LIFE_WINDOW_MS ||
                                    prof.getLastHeardFrom() > now - PROOF_OF_LIFE_WINDOW_MS ||
                                    prof.getLastHeardAbout() > now - PROOF_OF_LIFE_WINDOW_MS;
                    if (alive) continue;
                }
                candidates.add(peer);
            }
        }

        // During startup burst, prioritize high-bandwidth reachable peers first
        if (isStartupBurst && candidates.size() > 1) {
            Collections.sort(candidates, new Comparator<Hash>() {
                public int compare(Hash a, Hash b) {
                    return scorePeer(ctx, b) - scorePeer(ctx, a);
                }
            });
        }

        int probed = 0;
        for (Hash peer : candidates) {
            if (probed >= MAX_PROBES_PER_CYCLE) break;

            _lastProbed.put(peer, now);
            probed++;

            RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
            if (ri != null && _log.shouldInfo()) {
                _log.info("Pinging " + (isStartupBurst ? "startup" : "stale") +
                          " peer [" + peer.toBase64().substring(0, 6) + "]" +
                          " pub=" + new java.util.Date(ri.getPublished()));
            }

            ctx.profileOrganizer().demoteIfStale(peer);
            _facade.probeStalePeer(peer, ri);
        }

        if (_log.shouldInfo() && probed > 0) {
            _log.info("Pinged " + probed + " " +
                      (isStartupBurst ? "startup" : "stale") + " peers" +
                      (!isStartupBurst ? "" : " (" + candidates.size() + " candidates remaining)"));
        }

        long interval = isStartupBurst ? 15 * 1000 : CYCLE_INTERVAL;
        requeue(interval);
    }

    /**
     * Score a peer for startup probing priority.
     * Higher score = probe sooner.
     */
    private static int scorePeer(RouterContext ctx, Hash peer) {
        int score = 0;
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
        if (ri == null) return 0;

        String cap = ri.getCapabilities();
        if (cap != null) {
            if (cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0) score += 10;
            if (cap.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0) score += 5;
        }

        String bw = ri.getBandwidthTier();
        if (bw != null) {
            if (bw.equals("X")) score += 8;
            else if (bw.equals("P")) score += 6;
            else if (bw.equals("O")) score += 4;
        }

        return score;
    }
}
