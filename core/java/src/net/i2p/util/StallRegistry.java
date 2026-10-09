package net.i2p.util;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;

/**
 * Records which destinations had a streaming stall, so the router can prefer a
 * different outbound tunnel when sending to them again.
 *
 * <p>The signal lives in core because the two layers that need it cannot see
 * each other: {@code apps/streaming} is compiled against core and
 * ministreaming only, so it cannot reference a router class, and the router
 * cannot reach into a streaming connection. Core is the one place both already
 * depend on, so the channel is this small static registry.
 *
 * <p>Keyed by the remote destination only. The streaming layer can identify
 * its peer but not its own local destination — {@code I2PSession} does not
 * expose it and {@code I2PAppContext} has no accessor — so a local/remote pair
 * key could not be built from the streaming side. A stalled path is a property
 * of the path, not of which local client happened to use it, so the shared key
 * is also the more honest one.
 *
 * <p>Deliberately tunnel-agnostic: the registry records that a destination
 * stalled and when; the router decides what to do, because tunnel selection is
 * a router concern.
 *
 * <p>This is a hint, never a veto. A destination whose every tunnel is inside
 * its cooldown must still be sent to, so the registry only answers "was this
 * marked" and the caller is responsible for falling back.
 *
 * @since 0.9.71+
 */
public class StallRegistry {

    /**
     * Upper bound on tracked destinations. Entries are tiny, but the key space
     * is every destination this router streams to, so the map is capped and
     * pruned rather than allowed to grow with that count.
     */
    private static final int MAX_ENTRIES = 4096;

    /**
     * Marks older than this are dropped on the next prune. Must exceed the
     * longest cooldown a caller may ask about, or a mark still in effect would
     * be collected.
     */
    private static final long PRUNE_AGE_MS = 30 * 60 * 1000L;

    private static final Map<Hash, Long> _stalls = new ConcurrentHashMap<>(256, 0.9f, 16);

    private StallRegistry() {}

    /**
     * Note that a stream to this destination stopped making progress.
     *
     * @param remote the remote destination hash; ignored when null
     * @param now    current time in ms
     * @since 0.9.71+
     */
    public static void markStalled(Hash remote, long now) {
        if (remote == null) {return;}
        if (_stalls.size() >= MAX_ENTRIES) {prune(now);}
        _stalls.put(remote, now);
    }

    /**
     * Whether this destination was marked within the cooldown, meaning a
     * caller may prefer a different outbound tunnel.
     *
     * @param remote     the remote destination hash; ignored when null
     * @param now        current time in ms
     * @param cooldownMs how long a mark stays in effect; non-positive disables
     * @return true if the destination was marked and the mark is still in effect
     * @since 0.9.71+
     */
    public static boolean recentlyStalled(Hash remote, long now, long cooldownMs) {
        return stallAge(remote, now, cooldownMs) < cooldownMs;
    }

    /**
     * How long ago this destination was marked, clamped to the cooldown.
     *
     * @param remote     the remote destination hash; ignored when null
     * @param now        current time in ms
     * @param cooldownMs how long a mark stays in effect; non-positive disables
     * @return elapsed ms since the mark, or {@code cooldownMs} when unmarked,
     * disabled, or long expired — never less than the cooldown
     * @since 0.9.71+
     */
    public static long stallAge(Hash remote, long now, long cooldownMs) {
        if (remote == null || cooldownMs <= 0) {return cooldownMs;}
        Long at = _stalls.get(remote);
        if (at == null) {return cooldownMs;}
        long age = now - at;
        return (age < 0 || age >= cooldownMs) ? cooldownMs : age;
    }

    /**
     * Drop marks that can no longer be in effect for any cooldown a caller may
     * use, so the map stays bounded without discarding a live signal. A
     * pathological set of marks all younger than the prune age would survive,
     * so the excess is dropped too rather than growing without bound.
     *
     * @param now current time in ms
     * @since 0.9.71+
     */
    private static void prune(long now) {
        long cutoff = now - PRUNE_AGE_MS;
        _stalls.entrySet().removeIf(e -> e.getValue() < cutoff);
        while (_stalls.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<Hash, Long>> it = _stalls.entrySet().iterator();
            if (!it.hasNext()) {break;}
            it.next();
            it.remove();
        }
    }

    /**
     * Number of tracked destinations. For tests and diagnostics.
     *
     * @return current entry count
     * @since 0.9.71+
     */
    public static int size() {return _stalls.size();}

    /**
     * Drop all marks. For tests.
     *
     * @since 0.9.71+
     */
    public static void clear() {_stalls.clear();}
}
