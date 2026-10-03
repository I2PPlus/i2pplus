package net.i2p.stat;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.text.Collator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinate the management of various frequencies and rates within I2P components,
 * both allowing central update and retrieval, as well as distributed creation and
 * use.  This does not provide any persistence, but the data structures exposed can be
 * read and updated to manage the complete state.
 *
 */
public class StatManager {
    private final I2PAppContext _context;
    private final Log _log;

    /** Stat name to FrequencyStat. */
    private final ConcurrentHashMap<String, FrequencyStat> _frequencyStats;

    /** Stat name to RateStat. */
    private final ConcurrentHashMap<String, RateStat> _rateStats;

    /**
     * Off-timer queue for summary listener delivery, shared by every rate this
     * manager creates. One thread serves the whole table; a per-rate queue would
     * mean a thread per rate.
     */
    private final RateSampleDelivery _delivery;

    /**
     * Total coalesce failures isolated since startup, across all stats.
     *
     * <p>Nonzero means at least one stat stopped recording. It is reported rather
     * than absorbed because the failure it counts is silent by nature: a skipped
     * rate produces no exception for anyone to notice.
     */
    private final AtomicLong _coalesceFailures = new AtomicLong();

    /**
     * Log one coalesce-failure line per this many failures. The first is always
     * logged; after that a stat failing every cycle would otherwise emit one ERROR
     * per coalesce period for the life of the router.
     */
    private static final long COALESCE_FAILURE_LOG_INTERVAL = 1000;

    private int coalesceCounter;

    /** Frequency stats are coalesced every this many minutes. */
    private static final int FREQ_COALESCE_RATE = 9;

    /** Enables full stat collection; default false. */
    public static final String PROP_STAT_FULL = "stat.full";

    /**
     * The stat manager should only be constructed and accessed through the
     * application context.  This constructor should only be used by the
     * appropriate application context itself.
     *
     * @param context the application context
     */
    public StatManager(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(getClass());
        _frequencyStats = new ConcurrentHashMap<>(8);
        _rateStats = new ConcurrentHashMap<>(128);
        _delivery = new RateSampleDelivery(context);
    }

    /**
     * Shut down the statistics manager and clear all statistics.
     *
     * @since 0.8.8
     */
    public synchronized void shutdown() {
        // Stop the delivery thread first: it holds references to listeners whose
        // databases are being torn down as the maps below are cleared.
        _delivery.shutdown();
        _frequencyStats.clear();
        _rateStats.clear();
    }

    /**
     * Create a new statistic to monitor the frequency of some event.
     * The stat is ONLY created if the stat.full property is true or we are not in the router context.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     */
    public void createFrequencyStat(String name, String description, String group, long[] periods) {
        if (ignoreStat(name)) return;
        createRequiredFrequencyStat(name, description, group, periods);
    }

    /**
     * Create a new statistic to monitor the frequency of some event.
     * The stat is always created, independent of the stat.full setting or context.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     * @since 0.8.7
     */
    public void createRequiredFrequencyStat(String name, String description, String group, long[] periods) {
        if (_frequencyStats.containsKey(name)) return;
        _frequencyStats.putIfAbsent(name, new FrequencyStat(name, description, group, periods));
    }

    /**
     * Create a new statistic to monitor the average value and confidence of some action.
     * The stat is ONLY created if the stat.full property is true or we are not in the router context.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     */
    public void createRateStat(String name, String description, String group, long[] periods) {
        if (ignoreStat(name)) return;
        createRequiredRateStat(name, description, group, periods);
    }

    /**
     * Create a new statistic to monitor the average value and confidence of some action.
     * The stat is always created, independent of the stat.full setting or context.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     * @since 0.8.7
     */
    public void createRequiredRateStat(String name, String description, String group, long[] periods) {
        if (_rateStats.containsKey(name)) return;
        RateStat rs = new RateStat(name, description, group, periods);
        rs.setSampleDelivery(_delivery);
        _rateStats.putIfAbsent(name, rs);
    }

    /**
     * Sum of {@link Rate#getCoalesceSkips()} across every rate in the table.
     *
     * <p>A rate normally skips one coalesce per period, so this grows steadily and
     * is not by itself a problem. It is reported so that a rate which stopped
     * coalescing entirely can be told apart from one that is merely visited early.
     *
     * @return total coalesce skips across all rates
     * @since 0.9.71+
     */
    public long getCoalesceSkips() {
        long total = 0;
        for (RateStat rs : _rateStats.values())
            total += rs.getCoalesceSkips();
        return total;
    }

    /**
     * Sum of {@link Rate#getCoalesceBacklogCollapses()} across every rate in the
     * table.
     *
     * <p>Unlike a skip, a collapse is permanent: the steps it abandoned can never
     * be written, so every count here is a gap in the graph data. Zero is the
     * healthy state. A nonzero value points at the shared coalesce timer rather
     * than at any one rate, since all rates are coalesced from the same
     * {@link net.i2p.util.SimpleTimer2} pool.
     *
     * @return total backlog collapses across all rates
     * @since 0.9.71+
     */
    public long getCoalesceBacklogCollapses() {
        long total = 0;
        for (RateStat rs : _rateStats.values())
            total += rs.getCoalesceBacklogCollapses();
        return total;
    }

    /**
     * Samples evicted because the delivery queue hit its hard capacity.
     *
     * <p>Unlike {@link RateSampleDelivery#getSuperseded()} this must never happen:
     * same-step supersession means a slow consumer does not grow the queue, so
     * reaching the cap means the consumer is not draining at all. Surfaced here
     * because the delivery queue is package-private and the console watchdog,
     * which is the only thing positioned to act on it, lives in another package.
     *
     * @return eviction count since startup, zero in the healthy state
     * @since 0.9.71+
     */
    public long getSampleOverruns() {
        return _delivery.getOverruns();
    }

    /**
     * Samples replaced in place by a newer value for the same archive step.
     *
     * <p>High but non-zero is normal under load and is not loss - only one value
     * per step is storable and the newest is the correct one. A count that stops
     * advancing while {@link #getSamplePending()} climbs means the consumer has
     * stopped draining.
     *
     * @return supersession count since startup
     * @since 0.9.71+
     */
    public long getSampleSuperseded() {
        return _delivery.getSuperseded();
    }

    /**
     * Coalesced samples waiting to be handed to their listener.
     *
     * @return current queue depth, zero when delivery is keeping up
     * @since 0.9.71+
     */
    public int getSamplePending() {
        return _delivery.getPending();
    }

    /**
     * Age of the oldest sample still waiting for delivery.
     *
     * <p>The direct measure of delivery lag, and therefore the only signal that
     * distinguishes a stalled consumer from a rate that has stopped coalescing:
     * both leave the graph frozen, but only the second one drains this queue.
     *
     * @return ms since the oldest pending sample was submitted, zero when idle
     * @since 0.9.71+
     */
    public long getSampleOldestPendingAgeMs() {
        return _delivery.getOldestPendingAgeMs();
    }

    /**
     * Remove a rate stat by name.
     *
     * @param name the stat name to remove
     */
    public void removeRateStat(String name) {
        _rateStats.remove(name);
    }

    /**
     * Update the given frequency statistic, taking note that an event occurred (and recalculating all frequencies).
     *
     * @param name the stat name
     */
    public void updateFrequency(String name) {
        FrequencyStat freq = _frequencyStats.get(name);
        if (freq != null) freq.eventOccurred();
        else if (_log.shouldLog(Log.DEBUG)) _log.debug("Invalid frequency stat : " + name);
    }

    /**
     * Update the given rate statistic, taking note that the given data point was received (and recalculating all rates).
     *
     * @param name the stat name
     * @param data the data point value
     * @param eventDuration how long the event took, or 0
     */
    public void addRateData(String name, long data, long eventDuration) {
        RateStat stat = _rateStats.get(name); // unsynchronized
        if (stat != null) stat.addData(data, eventDuration);
        else if (_log.shouldLog(Log.DEBUG)) _log.debug("Invalid rate stat : " + name);
    }

    /**
     * Update the given rate statistic, taking note that the given data point was received (and recalculating all rates).
     * Zero duration.
     *
     * @since 0.8.10
     */
    public void addRateData(String name, long data) {
        RateStat stat = _rateStats.get(name); // unsynchronized
        if (stat != null) stat.addData(data);
        else if (_log.shouldLog(Log.DEBUG)) _log.warn("Invalid rate stat : " + name);
    }

    /**
     * Coalesce all rate stats and periodically coalesce frequency stats, isolating
     * every stat from every other.
     *
     * <p>Isolation is the whole point of this method. It runs as one pass over the
     * whole table on the shared coalesce timer, so a single exception escaping a
     * stat would abandon every stat later in the pass: their rates would stop
     * coalescing, their listeners would receive nothing, and their graphs would
     * freeze while every health check still reported them healthy. One broken stat
     * must therefore be able to affect only itself.
     */
    public synchronized void coalesceStats() {
        if (++coalesceCounter % FREQ_COALESCE_RATE == 0) {
            for (Map.Entry<String, FrequencyStat> e : _frequencyStats.entrySet()) {
                FrequencyStat stat = e.getValue();
                if (stat == null) {
                    continue;
                }
                try {
                    stat.coalesceStats();
                } catch (Throwable t) {
                    noteCoalesceFailure(e.getKey(), null, t);
                }
            }
        }
        for (Map.Entry<String, RateStat> e : _rateStats.entrySet()) {
            RateStat stat = e.getValue();
            long before;
            try {
                before = stat.getCoalesceFailures();
                stat.coalesceStats();
            } catch (Throwable t) {
                noteCoalesceFailure(e.getKey(), null, t);
                continue;
            }
            // RateStat isolates its own rates, so failures inside a stat surface as a
            // count rather than an exception reaching here. Attribute them by name.
            long delta = stat.getCoalesceFailures() - before;
            for (long i = 0; i < delta; i++) {
                noteCoalesceFailure(e.getKey(), stat.getFirstCoalesceFailurePeriod(),
                                    stat.getFirstCoalesceFailureCause());
            }
        }
    }

    /**
     * Count an isolated coalesce failure and log it, naming the stat at fault.
     *
     * <p>ERROR rather than WARN because the failure is silent data loss, and
     * {@code logger.defaultLevel} is ERROR: a WARN would be suppressed in a default
     * configuration and the loss would go unnoticed. ERROR also reaches the
     * console's error log page, so the stat is identifiable without reading the log
     * file.
     *
     * @param statName name of the stat that failed
     * @param period the failing rate's period in ms, or null if not per-rate
     * @param cause what went wrong
     */
    private void noteCoalesceFailure(String statName, String period, Throwable cause) {
        long total = _coalesceFailures.incrementAndGet();
        if (total != 1 && total % COALESCE_FAILURE_LOG_INTERVAL != 0)
            return;
        StringBuilder sb = new StringBuilder(96);
        sb.append("Rate coalesce failed for '").append(statName).append('\'');
        if (period != null)
            sb.append(" period=").append(period).append("ms");
        sb.append("; this stat stops recording, all others continue. ")
          .append(total).append(" coalesce failures total");
        if (cause != null)
            sb.append(": ").append(cause);
        _log.error(sb.toString(), cause);
    }

    /**
     * Total coalesce failures isolated since startup.
     *
     * <p>Zero is the healthy state. Nonzero means at least one stat has silently
     * stopped recording; the log line names which one.
     *
     * @return the running failure count across all stats
     * @since 0.9.71+
     */
    public long getCoalesceFailures() {
        return _coalesceFailures.get();
    }

    /**
     * Misnamed, as it returns a FrequencyStat, not a Frequency.
     * @return the frequency
     */
    public FrequencyStat getFrequency(String name) {
        return _frequencyStats.get(name);
    }

    /**
     * Misnamed, as it returns a RateStat, not a Rate.
     * @return the rate
     */
    public RateStat getRate(String name) {
        return _rateStats.get(name);
    }

    /**
     * Names of all frequency statistics.
     *
     * @return a copy of the frequency stat names
     */
    public Set<String> getFrequencyNames() {
        return new HashSet<>(_frequencyStats.keySet());
    }

    /**
     * Names of all rate statistics.
     *
     * @return a copy of the rate stat names
     */
    public Set<String> getRateNames() {
        return new HashSet<>(_rateStats.keySet());
    }

    /** Whether the given stat is a monitored rate. */
    public boolean isRate(String statName) {
        return _rateStats.containsKey(statName);
    }

    /** Whether the given stat is a monitored frequency. */
    public boolean isFrequency(String statName) {
        return _frequencyStats.containsKey(statName);
    }

    /**
     * Group name (untranslated String) to a SortedSet of untranslated stat names.
     * Map is unsorted.
     * @return the stats by group
     */
    public Map<String, SortedSet<String>> getStatsByGroup() {
        Map<String, SortedSet<String>> groups = new HashMap<>(32);
        for (FrequencyStat stat : _frequencyStats.values()) {
            String gname = stat.getGroupName();
            SortedSet<String> names = groups.get(gname);
            if (names == null) {
                names = new TreeSet<>(Collator.getInstance());
                groups.put(gname, names);
            }
            names.add(stat.getName());
        }
        for (RateStat stat : _rateStats.values()) {
            String gname = stat.getGroupName();
            SortedSet<String> names = groups.get(gname);
            if (names == null) {
                names = new TreeSet<>(Collator.getInstance());
                groups.put(gname, names);
            }
            names.add(stat.getName());
        }
        return groups;
    }

    /**
     * Save memory by not creating stats unless they are required for router operation.
     * For backward compatibility of any external clients, always returns false if not in router context.
     *
     * @param statName ignored
     * @return true if the stat should be ignored.
     */
    public boolean ignoreStat(String statName) {
        return _context.isRouterContext() && !_context.getBooleanProperty(PROP_STAT_FULL);
    }

    /**
     * Serializes all Frequencies and Rates to the provided OutputStream
     *
     * @param out to write to
     * @param prefix to use when serializing
     * @throws IOException if something goes wrong
     * @since 0.9.23
     */
    public void store(OutputStream out, String prefix) throws IOException {
        for (FrequencyStat fs : _frequencyStats.values()) {
            fs.store(out, prefix);
        }
        for (RateStat rs : _rateStats.values()) {
            rs.store(out, prefix);
        }
    }
}
