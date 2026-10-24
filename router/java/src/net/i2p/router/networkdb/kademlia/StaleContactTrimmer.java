package net.i2p.router.networkdb.kademlia;

import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.kademlia.KBucket;
import net.i2p.kademlia.KBucketTrimmer;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;

/**
 * Evicts the least-recently-attested peer from a full routing bucket so the
 * netdb routing table rotates toward live peers instead of filling with
 * dead-but-unexpired routers. Liveness is the newest of the peer profile's
 * heard-about / heard-from / successful-send times. A peer with no profile
 * counts as never seen. The oldest entry is only evicted when it is older
 * than the grace window, otherwise the newcomer is rejected. Peers with an
 * established session are never eviction candidates — same protection
 * ExpireRoutersJob gives them.
 *
 * @since 0.9.71
 */
class StaleContactTrimmer implements KBucketTrimmer<Hash> {

    /**
     * How long an entry is protected from eviction even when stale.
     */
    static final long GRACE = 24 * 60 * 60 * 1000L;

    /** Supplies the clock and the peer list. */
    private final RouterContext _context;

    /**
     * Evict stale entries using this context's clock and peer profiles.
     *
     * @param context the router context, for the clock and profile organizer
     */
    StaleContactTrimmer(RouterContext context) {
        _context = context;
    }

    @Override
    public boolean trim(KBucket<Hash> kbucket, Hash toAdd) {
        Set<Hash> entries = kbucket.getEntries();
        Hash oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Hash h : entries) {
            // Never evict a peer we are actively connected to
            if (_context.commSystem().isEstablished(h)) {continue;}
            long last = lastSeen(_context, h);
            if (last < oldestTime) {
                oldestTime = last;
                oldest = h;
            }
        }
        if (oldest != null && oldestTime < _context.clock().now() - GRACE) {
            kbucket.remove(oldest);
            return true;
        }
        return false;
    }

    /**
     * The newest contact evidence for a peer, 0 when there is none.
     * Static and package-visible so ExpireRoutersJob scores staleness the
     * same way.
     *
     * @param context the router context
     * @param peer the peer hash
     * @return context time of the newest evidence, or 0
     * @since 0.9.71+
     */
    static long lastSeen(RouterContext context, Hash peer) {
        PeerProfile prof = context.profileOrganizer().getProfileNonblocking(peer);
        if (prof == null) {return 0;}
        long t = prof.getLastHeardAbout();
        t = Math.max(t, prof.getLastHeardFrom());
        t = Math.max(t, prof.getLastSendSuccessful());
        return t;
    }
}
