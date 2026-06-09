package net.i2p.router.networkdb.kademlia;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;

/**
 * Periodically probes peers with stale RouterInfos and no recent proof of life
 * to verify they are still alive.
 *
 * Fires a DirectLookupJob for each qualifying peer, which asks them for
 * their own RouterInfo. If they respond, the fresh RouterInfo is stored
 * via HandleFloodfillDatabaseStoreMessageJob (updating lastHeardFrom and
 * lastHeardAbout on their profile), making them pass the proof-of-life check
 * in ProfileOrganizer.isSelectable().
 *
 * @since 0.9.70
 */
class ProbeStalePeerJob extends JobImpl {
    private final Log _log;
    private final FloodfillNetworkDatabaseFacade _facade;
    private final Map<Hash, Long> _lastProbed;

    private static final long CYCLE_INTERVAL = 3 * 60 * 1000;
    private static final int MAX_PROBES_PER_CYCLE = 5;
    private static final long PROBE_COOLDOWN = 10 * 60 * 1000;
    private static final long PROOF_OF_LIFE_WINDOW_MS = 60 * 60 * 1000;
    private static final long STARTUP_GRACE_PERIOD = 10 * 60 * 1000;

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
        if (uptime < STARTUP_GRACE_PERIOD) {
            requeue(CYCLE_INTERVAL);
            return;
        }

        long now = ctx.clock().now();
        long maxAge = ctx.getProperty(
            "profileOrganizer.maxRouterInfoAgeHours", 2) * 3600_000L;

        int probed = 0;
        for (Hash peer : ctx.profileOrganizer().selectAllPeers()) {
            if (probed >= MAX_PROBES_PER_CYCLE) break;

            Long last = _lastProbed.get(peer);
            if (last != null && now - last < PROBE_COOLDOWN) continue;
            if (ctx.commSystem().isEstablished(peer)) continue;

            RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
            if (ri == null || ri.isHidden()) continue;
            if (ri.getPublished() > now - maxAge) continue;

            PeerProfile prof = ctx.profileOrganizer().getProfile(peer);
            if (prof != null) {
                boolean alive = prof.getLastSendSuccessful() > now - PROOF_OF_LIFE_WINDOW_MS ||
                                prof.getLastHeardFrom() > now - PROOF_OF_LIFE_WINDOW_MS ||
                                prof.getLastHeardAbout() > now - PROOF_OF_LIFE_WINDOW_MS;
                if (alive) continue;
            }

            _lastProbed.put(peer, now);
            probed++;

            if (_log.shouldInfo()) {
                _log.info("Pinging stale peer [" + peer.toBase64().substring(0, 6) + "]" +
                          " pub=" + new java.util.Date(ri.getPublished()));
            }

            ctx.profileOrganizer().demoteIfStale(peer);
            _facade.probeStalePeer(peer, ri);
        }

        if (_log.shouldInfo() && probed > 0) {
            _log.info("Pinged " + probed + " stale peers");
        }

        requeue(CYCLE_INTERVAL);
    }
}
