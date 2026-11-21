package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;

/**
 * Decides whether a soft data-phase send failure is evidence against the tunnel
 * that carried it, or against the destination it was sent to — the I2CP status
 * in question is 3, a best-effort send that was never ACKed.
 * <p>
 * A status-3 timeout says the message was never ACKed by the far end. It does
 * not say which of the two is at fault, and until now every one of them was
 * charged to the carrying tunnel, so a single unreachable destination slowly
 * retired every egress tunnel that happened to try it. Measured on a live
 * router: 8,513 send timeouts in three hours, of which the top three
 * destinations alone accounted for 2,760, and the same pool logged 30 outbound
 * tunnels condemned as "failing after N soft failures" while its replacement
 * builds were only 64% successful. The tunnels were not the problem, and
 * condemning them made the pool shorter, which made the next send time out too.
 * <p>
 * So a destination is allowed to cost a pool only a bounded amount of tunnel
 * credit. Its first {@link #DEST_CONTRIBUTION_CAP} timeouts in a window count
 * against whichever tunnel carried them, which is still enough to retire a
 * tunnel that fails against many different destinations. Past that the
 * destination is the common factor, and its timeouts stop eroding the pool.
 * <p>
 * Stateless with respect to the pool: it holds only a bounded tally of recent
 * destinations, so it is shared by one pool's inbound and outbound tunnels and
 * needs no router context to test.
 *
 * @since 0.9.72+
 */
class SoftFailureAttribution {

    /**
     * How many soft failures one destination may contribute to tunnel
     * condemnation inside {@link #WINDOW_MS}. Low enough that a tunnel failing
     * against several destinations is still retired promptly, high enough that
     * one dead peer cannot spend a whole pool.
     */
    static final int DEST_CONTRIBUTION_CAP = 3;

    /**
     * How long a destination stays in the tally, and how long a quiet
     * destination's history is forgotten so a peer that came back can charge
     * tunnels again. Matches {@code TunnelCreatorConfig.SOFT_FAILURE_WINDOW_MS}
     * so the two counters decay on the same clock.
     */
    static final long WINDOW_MS = 10 * 60 * 1000L;

    /**
     * Ceiling on tracked destinations. A busy router sends to far more
     * destinations than this in ten minutes, and the tally only has to cover
     * the ones failing often enough to matter, so the map is pruned to the
     * most recent entries rather than allowed to grow with the address book.
     */
    static final int MAX_TRACKED_DESTINATIONS = 256;

    /**
     * One destination's recent soft-failure history. Mutated only inside
     * {@link ConcurrentHashMap#compute}, which holds the bin lock, so the
     * fields need no volatile.
     */
    private static final class History {
        int _count;
        long _lastSeen;
    }

    private final ConcurrentHashMap<Hash, History> _histories =
            new ConcurrentHashMap<Hash, History>();

    /**
     * One pool's tally of recent soft send failures per destination. Starts
     * empty: no destination is exempt from blame until it has timed out often
     * enough to be the common factor rather than the tunnel it was sent through.
     */
    SoftFailureAttribution() {}

    /**
     * Record one soft send failure and decide whether it counts against the
     * tunnel that carried it.
     *
     * @param dest destination the failed message was addressed to; null when
     *             the caller cannot say, which is charged to the tunnel as
     *             before because there is nothing else to blame
     * @param now current time in ms
     * @return true if the failure should increment the tunnel's soft-failure
     *         streak, false if it is charged to the destination instead
     */
    boolean countsAgainstTunnel(Hash dest, long now) {
        if (dest == null) {return true;}
        final int[] seen = new int[1];
        _histories.compute(dest, (key, prior) -> {
            History h = prior != null ? prior : new History();
            // Quiet for a window: the destination gets a fresh allowance, so a
            // peer that recovered is not permanently exempt from blame.
            if (now - h._lastSeen > WINDOW_MS) {h._count = 0;}
            h._lastSeen = now;
            if (h._count < Integer.MAX_VALUE) {h._count++;}
            seen[0] = h._count;
            return h;
        });
        if (_histories.size() > MAX_TRACKED_DESTINATIONS) {pruneStale(now);}
        return seen[0] <= DEST_CONTRIBUTION_CAP;
    }

    /**
     * How many soft failures this destination has produced in the current
     * window, for logging.
     *
     * @param dest destination to look up, may be null
     * @param now current time in ms
     * @return the count, 0 if the destination is unknown or its history decayed
     */
    int failuresFrom(Hash dest, long now) {
        if (dest == null) {return 0;}
        History h = _histories.get(dest);
        return h == null || now - h._lastSeen > WINDOW_MS ? 0 : h._count;
    }

    /**
     * Number of destinations currently tracked, for tests and diagnostics.
     *
     * @return the map size
     */
    int trackedDestinations() {return _histories.size();}

    /**
     * Drop destinations whose history decayed, and if the tally is still over
     * the ceiling, the quietest ones. Only reached once the map passes
     * {@link #MAX_TRACKED_DESTINATIONS}, so the sort is not on the hot path.
     *
     * @param now current time in ms
     */
    private void pruneStale(long now) {
        _histories.entrySet().removeIf(e -> now - e.getValue()._lastSeen > WINDOW_MS);
        int over = _histories.size() - MAX_TRACKED_DESTINATIONS;
        if (over <= 0) {return;}
        List<Map.Entry<Hash, History>> entries =
                new ArrayList<Map.Entry<Hash, History>>(_histories.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue()._lastSeen));
        for (int i = 0; i < over && i < entries.size(); i++) {
            _histories.remove(entries.get(i).getKey());
        }
    }
}
