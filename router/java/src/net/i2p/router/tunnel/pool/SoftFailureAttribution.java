package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * That exemption needs a second dimension, or it exempts everything. Once a
 * destination is the common factor its failures are free, and nothing asks
 * whether they all rode the same tunnel. Measured on a live router on
 * 2026-11-25, 68 soft failures were spread over ten different first hops while
 * one destination alone had absorbed 1,040 of them, so every failure was blamed
 * on a destination and no tunnel was charged for any of them — and a destination
 * that stopped answering cannot be told apart from a path that swallows everything
 * sent to it. So a hop that carries {@link #HOP_EXEMPT_CAP} exempt failures
 * for {@link #HOP_DISTINCT_DESTS} different destinations is itself the common
 * factor, and the tunnel carrying them is charged again.
 * <p>
 * Stateless with respect to the pool: it holds only bounded tallies of recent
 * destinations and recent hops, so it is shared by one pool's inbound and
 * outbound tunnels and needs no router context to test.
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
     * Destination-exempt soft failures one tunnel hop may carry before the
     * tunnel carrying them is charged again.
     * <p>Higher than {@link #DEST_CONTRIBUTION_CAP}, because these are failures
     * that were already excused once: the point is to catch the pile-up, not to
     * second-guess a single exemption. Measured on 2026-11-25, one destination
     * had 1,040 soft failures in the tally and every failure past the third was
     * free, so a hop carrying all of them was never asked about.
     */
    static final int HOP_EXEMPT_CAP = 8;

    /**
     * Distinct destinations an exempt pile-up has to span before it is evidence
     * against the hop rather than the destinations.
     * <p>Two, not more. One destination is what {@link #DEST_CONTRIBUTION_CAP}
     * already excuses; two different destinations timing out through the same
     * hop is the shape of a hop that does not deliver.
     */
    static final int HOP_DISTINCT_DESTS = 2;

    /**
     * Distinct destinations remembered per hop. The decision only needs to know
     * whether the pile-up spans {@link #HOP_DISTINCT_DESTS} of them, so the
     * set is capped rather than allowed to hold every destination the hop ever
     * carried.
     */
    static final int HOP_DESTS_TRACKED = 8;

    /**
     * Ceiling on tracked hops, sized from the working set rather than guessed at
     * it. A pool keeps a dozen tunnels and rebuilds them every few minutes, so
     * the hops it has sent through inside one window are a multiple of the pool
     * size: the 2026-11-25 audit window saw 133 distinct first hops across the
     * pools. A ceiling below that evicts the quietest entry on every insert past
     * it, and the quietest entry is the hop whose pile-up is still accumulating
     * between bursts, so the count the decision reads would restart.
     */
    static final int MAX_TRACKED_HOPS = 256;

    /**
     * The part of a tally entry the decay and the prune both need: when the key
     * last produced a failure. Mutated only inside
     * {@link ConcurrentHashMap#compute}, which holds the bin lock, so the field
     * needs no volatile.
     */
    private static class Tracked {
        /** When this key last produced a failure, in ms. */
        long _lastSeen;
    }

    /**
     * One destination's recent soft-failure history.
     */
    private static final class History extends Tracked {
        /** Failures attributed to this destination in the current window. */
        int _count;
    }

    /**
     * One hop's recent history of destination-exempt soft failures.
     */
    private static final class HopHistory extends Tracked {
        /** Exempt failures carried in the current window. */
        int _exempt;
        /** Which destinations those failures were addressed to, capped. */
        final Set<Hash> _dests = new HashSet<Hash>(4);
    }

    private final ConcurrentHashMap<Hash, History> _histories =
            new ConcurrentHashMap<Hash, History>();

    /**
     * Recent destination-exempt soft failures per tunnel hop, the second
     * dimension of the attribution.
     */
    private final ConcurrentHashMap<Hash, HopHistory> _hops =
            new ConcurrentHashMap<Hash, HopHistory>();

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
        return countsAgainstTunnel(dest, null, now);
    }

    /**
     * Record one soft send failure, naming the tunnel hop it rode, and decide
     * whether it counts against the tunnel that carried it.
     * <p>The hop is what makes the destination exemption safe on its own. A
     * destination past {@link #DEST_CONTRIBUTION_CAP} is excused for the rest of
     * the window, and if every one of those excused failures went out the same
     * hop, the pool has been told twice that the hop does not deliver and has
     * charged nothing for it.
     *
     * @param dest destination the failed message was addressed to; null when
     *             the caller cannot say, which is charged to the tunnel as
     *             before because there is nothing else to blame
     * @param hop the hop adjacent to us on the tunnel that carried the message,
     *            or null if the caller cannot name one, which leaves the hop
     *            dimension unmeasured and the destination rule in charge
     * @param now current time in ms
     * @return true if the failure should increment the tunnel's soft-failure
     *         streak, false if it is charged to the destination instead
     * @since 0.9.72+
     */
    boolean countsAgainstTunnel(Hash dest, Hash hop, long now) {
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
        pruneIfNeeded(_histories, MAX_TRACKED_DESTINATIONS, now);
        if (seen[0] <= DEST_CONTRIBUTION_CAP) {return true;}
        return hopCarriesThePileUp(hop, dest, now);
    }

    /**
     * Record an exempt failure against the hop that carried it, and say whether
     * the hop has become the common factor.
     *
     * @param hop hop adjacent to us on the carrying tunnel, may be null
     * @param dest destination the failure was addressed to, not null
     * @param now current time in ms
     * @return true if the hop has carried enough exempt failures for enough
     *         different destinations that the tunnel through it is to blame
     */
    private boolean hopCarriesThePileUp(Hash hop, Hash dest, long now) {
        if (hop == null) {return false;}
        final boolean[] blame = new boolean[1];
        _hops.compute(hop, (key, prior) -> {
            HopHistory h = prior != null ? prior : new HopHistory();
            // Quiet for a window: the hop starts clean, so a hop that was
            // condemned and came back is not condemned in advance.
            if (now - h._lastSeen > WINDOW_MS) {
                h._exempt = 0;
                h._dests.clear();
            }
            h._lastSeen = now;
            if (h._dests.size() < HOP_DESTS_TRACKED || h._dests.contains(dest)) {
                h._dests.add(dest);
            }
            if (h._exempt < Integer.MAX_VALUE) {h._exempt++;}
            blame[0] = h._exempt >= HOP_EXEMPT_CAP &&
                       h._dests.size() >= HOP_DISTINCT_DESTS;
            return h;
        });
        pruneIfNeeded(_hops, MAX_TRACKED_HOPS, now);
        return blame[0];
    }

    /**
     * How many destination-exempt soft failures this hop has carried in the
     * current window, for logging. This is the count the hop dimension decides
     * on, and it is invisible in the destination tally, which reports the
     * destination's own total instead.
     *
     * @param hop hop to look up, may be null
     * @param now current time in ms
     * @return the count, 0 if the hop is unknown or its history decayed
     * @since 0.9.72+
     */
    int exemptFailuresThrough(Hash hop, long now) {
        if (hop == null) {return 0;}
        HopHistory h = _hops.get(hop);
        return h == null || now - h._lastSeen > WINDOW_MS ? 0 : h._exempt;
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
     * Number of hops currently tracked, for tests and diagnostics.
     *
     * @return the map size
     * @since 0.9.72+
     */
    int trackedHops() {return _hops.size();}

    /**
     * Prune a tally that has grown past its ceiling, with a slack band above the
     * ceiling so the prune is not paid on every failure once the map is sitting
     * at the ceiling. Without the band, a working set at the ceiling means one
     * insert, one prune, one sort, per failure, for as long as the burst lasts.
     *
     * @param <T> the tally's value type, a {@link Tracked} history record decayed
     *            out of the map when it stops being used
     * @param map the tally to prune, keyed by destination or by hop
     * @param ceiling entries to keep once pruned
     * @param now current time in ms
     */
    private static <T extends Tracked> void pruneIfNeeded(Map<Hash, T> map,
                                                          int ceiling, long now) {
        if (map.size() <= ceiling + (ceiling >> 2)) {return;}
        pruneStale(map, ceiling, now);
    }

    /**
     * Drop tally entries whose history decayed, and if the map is still over its
     * ceiling, the quietest ones. Only reached once a map passes its ceiling plus
     * the slack band, so the sort is not on the hot path.
     *
     * @param <T> the tally's value type, a {@link Tracked} history record decayed
     *            out of the map when it stops being used
     * @param map the tally to prune, keyed by destination or by hop
     * @param ceiling entries to keep
     * @param now current time in ms
     */
    private static <T extends Tracked> void pruneStale(Map<Hash, T> map,
                                                       int ceiling, long now) {
        map.entrySet().removeIf(e -> now - e.getValue()._lastSeen > WINDOW_MS);
        int over = map.size() - ceiling;
        if (over <= 0) {return;}
        List<Map.Entry<Hash, T>> entries =
                new ArrayList<Map.Entry<Hash, T>>(map.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue()._lastSeen));
        for (int i = 0; i < over && i < entries.size(); i++) {
            map.remove(entries.get(i).getKey());
        }
    }
}
