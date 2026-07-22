package net.i2p.router.networkdb.kademlia;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Lightweight background job that refreshes RouterInfo for peers we recently
 *  interacted with.  When we receive a DatabaseStore (or other direct contact)
 *  from a peer, we enqueue them here.  On each cycle, we process a batch:
 *  if the peer's stored RouterInfo is stale (published &gt; 1h ago) or missing,
 *  we proactively refresh it via DirectLookupJob.
 *
 *  This is the contact-driven half of proactive RouterInfo freshness:
 *  peers we actively communicate with are likely to be re-selected as tunnel
 *  hops, so keeping their RI current prevents build-time next-hop lookup failures.
 *
 *  @since 0.9.70+
 */
class ContactDrivenRefreshJob extends JobImpl {
    private final Log _log;
    private final FloodfillNetworkDatabaseFacade _facade;

    /** Peers we've heard from recently that may need a RI refresh */
    private final ConcurrentLinkedQueue<Hash> _pending;

    /** Cooldown tracker: peer hash -&gt; last refresh time (ms) */
    private final Map<Hash, Long> _lastRefresh;

    /** Max peers to process per cycle */
    private static final int MAX_BATCH = 10;

    /** Run interval when idle */
    private static final long CYCLE_MS = 10 * 1000L;

    /** Don't re-refresh the same peer more often than this */
    private static final long COOLDOWN_MS = 15L * 60 * 1000;

    /** Max cooldown map entries to prevent unbounded growth */
    private static final int MAX_COOLDOWN_ENTRIES = 4096;
    /** Max pending queue size — reject offers past this to prevent OOM under heavy store load */
    private static final int MAX_PENDING = 8192;

    ContactDrivenRefreshJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ContactDrivenRefreshJob.class);
        _facade = facade;
        _pending = new ConcurrentLinkedQueue<>();
        _lastRefresh = new ConcurrentHashMap<>();
    }

    @Override
    public String getName() {
        return "Contact-Driven RouterInfo Refresh";
    }

    /**
     *  Called when we hear from or about a peer.
     *  Safe to call from any thread (e.g., message handler).
     *  Drops the entry if the pending queue is full to prevent unbounded memory growth.
     */
    void heardFrom(Hash peer) {
        if (peer == null || peer.equals(getContext().routerHash()))
            return;
        if (_pending.size() >= MAX_PENDING) return;
        _pending.offer(peer);
    }

    @Override
    public void runJob() {
        RouterContext ctx = getContext();
        long now = ctx.clock().now();

        if (!_facade.isInitialized()
                || ctx.jobQueue().getMaxLag() > 500
                || ctx.commSystem().getStatus() == CommSystemFacade.Status.DISCONNECTED) {
            requeue(CYCLE_MS);
            return;
        }

        int processed = 0;
        int refreshed = 0;

        for (int i = 0; i < MAX_BATCH; i++) {
            Hash peer = _pending.poll();
            if (peer == null)
                break;

            processed++;

            // Cooldown: don't re-refresh too often
            Long last = _lastRefresh.get(peer);
            if (last != null && now - last < COOLDOWN_MS)
                continue;

            // Already connected — they'll send us their RI via transport keepalive
            if (ctx.commSystem().isEstablished(peer))
                continue;

            // Check if we have a valid RouterInfo and whether it's fresh enough
            RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
            if (ri != null && ri.getPublished() > now - FloodfillNetworkDatabaseFacade.MAX_RI_AGE_BEFORE_REFRESH_MS)
                continue;

            _lastRefresh.put(peer, now);

            // Prune cooldown map periodically
            if (_lastRefresh.size() > MAX_COOLDOWN_ENTRIES) {
                long cutoff = now - COOLDOWN_MS;
                _lastRefresh.entrySet().removeIf(e -> e.getValue() < cutoff);
            }

            refreshed++;

            if (ri != null) {
                // We have a (stale) RouterInfo — use DirectLookupJob to ask the peer directly
                _facade.probeStalePeer(peer, ri);
                if (_log.shouldInfo()) {
                    _log.info("Contact-driven refresh: [" + peer.toBase64().substring(0, 6)
                              + "] stale RI pub=" + new java.util.Date(ri.getPublished()));
                }
            } else {
                // No RI at all — full Kademlia search
                if (_log.shouldInfo()) {
                    _log.info("Contact-driven refresh: [" + peer.toBase64().substring(0, 6)
                              + "] no RI, starting search");
                }
                _facade.search(peer, null, null,
                               FloodfillNetworkDatabaseFacade.MAX_RI_AGE_BEFORE_REFRESH_MS, false);
            }
        }

        if (processed > 0 && _log.shouldInfo()) {
            _log.info("Contact-driven refresh: processed " + processed
                      + " peers, refreshed " + refreshed);
        }

        requeue(CYCLE_MS);
    }
}
