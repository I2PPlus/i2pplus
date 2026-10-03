package net.i2p.stat;

import static java.util.Arrays.*;

import net.i2p.data.DataHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import java.nio.charset.StandardCharsets;
/** Coordinates a moving rate over various periods. */
public class RateStat {
    /** Unique name of the statistic. */
    private final String _statName;

    /** Grouping under which the stat is kept. */
    private final String _groupName;

    /** Describes the stat. */
    private final String _description;

    /** Actual rate objects for this statistic. */
    protected final Rate[] _rates;
    /**
     * How many per-rate {@link Rate#coalesce()} calls have thrown. A rate that
     * throws is skipped, never propagated: see {@link #coalesceStats()}.
     */
    private final AtomicLong _coalesceFailures = new AtomicLong();
    /**
     * Identity of the first rate that failed to coalesce, and its cause, so the
     * log names which period of which stat is broken instead of only counting.
     */
    private volatile String _firstFailurePeriod;
    private volatile Throwable _firstFailureCause;

    /**
     * Unique name of the statistic.
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds), must not be empty
     * @throws IllegalArgumentException if periods is empty
     */
    public RateStat(String name, String description, String group, long[] periods) {
        _statName = name;
        _description = description;
        _groupName = group;
        if (periods.length == 0) throw new IllegalArgumentException();

        long[] periodsCopy = new long[periods.length];
        System.arraycopy(periods, 0, periodsCopy, 0, periods.length);
        sort(periodsCopy);

        _rates = new Rate[periodsCopy.length];
        for (int i = 0; i < periodsCopy.length; i++) {
            Rate rate = new Rate(periodsCopy[i]);
            rate.setRateStat(this);
            _rates[i] = rate;
        }
    }

    /**
     * Constructor taking pre-built rates, for tests that need a rate which throws.
     *
     * <p>The public constructor cannot express this because it always builds working
     * rates, and a {@code Rate} cannot be made to fail without a fault injected into
     * it. The isolation in {@link #coalesceStats()} is only worth anything if it can
     * be shown to hold when a rate really does throw.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param rates the rates to coalesce, must not be empty
     * @throws IllegalArgumentException if rates is empty
     */
    RateStat(String name, String description, String group, Rate[] rates) {
        _statName = name;
        _description = description;
        _groupName = group;
        if (rates.length == 0) throw new IllegalArgumentException();
        _rates = rates;
        for (Rate r : _rates) r.setRateStat(this);
    }

/**
     * Route coalesced samples for every rate in this stat through {@code delivery}
     * instead of calling the summary listener inline.
     *
     * <p>Set by {@link StatManager} immediately after construction. A setter rather
     * than a constructor argument so the public constructor signature — and its
     * callers — are unaffected.
     *
     * @param delivery the shared delivery queue, or null for inline delivery
     * @since 0.9.71+
     */
    void setSampleDelivery(RateSampleDelivery delivery) {
        for (Rate r : _rates) r.setSampleDelivery(delivery);
    }

    /**
     * Sum of the per-rate coalesce skip counts; see {@link Rate#getCoalesceSkips()}.
     *
     * @return total coalesce skips across this stat's rates
     * @since 0.9.71+
     */
    long getCoalesceSkips() {
        long total = 0;
        for (Rate r : _rates) total += r.getCoalesceSkips();
        return total;
    }

    /**
     * Sum of the per-rate successful coalesce counts; see {@link Rate#getCoalesceCount()}.
     *
     * @return total coalesces across this stat's rates
     * @since 0.9.71+
     */
    public long getCoalesceCount() {
        long total = 0;
        for (Rate r : _rates) total += r.getCoalesceCount();
        return total;
    }

    /**
     * Sum of the per-rate backlog collapse counts; see
     * {@link Rate#getCoalesceBacklogCollapses()}.
     *
     * @return total backlog collapses across this stat's rates
     * @since 0.9.71+
     */
    long getCoalesceBacklogCollapses() {
        long total = 0;
        for (Rate r : _rates) total += r.getCoalesceBacklogCollapses();
        return total;
    }

    /**
     * Retained coalesced samples for backfill, oldest first.
     *
     * <p>Read from the shortest-period rate ({@code _rates[0]}, periods being held
     * in ascending order) because that is the rate whose steps its RRD stores, and
     * therefore the one a listener writing history has to replay.
     *
     * @param max the most samples to return, clamped by the rate's ring
     * @return an immutable list of at most {@code max} samples, oldest first,
     *         never null
     * @since 0.9.71+
     */
    public List<Rate.CoalescedSample> getRecentSamples(int max) {
        return _rates[0].getRecentSamples(max);
    }

/**
 * Update all of the rates for the various periods with the given value.
 *
 * @param value the value
 * @param eventDuration the event duration
 */
public void addData(long value, long eventDuration) {
        for (Rate r : _rates) r.addData(value, eventDuration);
    }

    /**
     * Update all of the rates for the various periods with the given value.
     * Zero duration.
     *
 * @param value the value
 * @since 0.8.10
 */
public void addData(long value) {
        for (Rate r : _rates) r.addData(value);
    }

    /**
     * Coalesce every period's rate, isolating each one.
     *
     * <p>A rate that throws must not stop its siblings. {@link StatManager} walks
     * every stat in one pass on the coalesce timer, so an exception escaping here
     * would abandon every stat later in that pass: their rates would silently stop
     * coalescing, their listeners would stop receiving samples, and their graphs
     * would freeze with nothing logged anywhere - which is exactly the failure this
     * method exists to make impossible.
     *
     * <p>The first failure is remembered with the period that produced it, so
     * {@link StatManager} can name the offending stat rather than only counting
     * anonymous failures.
     */
    public void coalesceStats() {
        for (Rate r : _rates) {
            try {
                r.coalesce();
            } catch (Throwable t) {
                if (_coalesceFailures.incrementAndGet() == 1) {
                    _firstFailurePeriod = Long.toString(r.getPeriod());
                    _firstFailureCause = t;
                }
            }
        }
    }

    /**
     * Number of per-rate coalesce calls that have thrown since construction.
     *
     * @return the running failure count
     * @since 0.9.71+
     */
    long getCoalesceFailures() {
        return _coalesceFailures.get();
    }

    /**
     * The period of the first rate that failed to coalesce, or null if none has.
     *
     * @return the failing rate's period in ms, or null
     * @since 0.9.71+
     */
    String getFirstCoalesceFailurePeriod() {
        return _firstFailurePeriod;
    }

    /**
     * The cause of the first coalesce failure, for the log line.
     *
     * @return the first failure's throwable, or null
     * @since 0.9.71+
     */
    Throwable getFirstCoalesceFailureCause() {
        return _firstFailureCause;
    }

    /**
     * Unique name for this rate stat.
     * @return the unique name of this statistic
     */
    public String getName() {
        return _statName;
    }

    /**
     * Grouping name for this rate stat.
     * @return the grouping name under which this statistic is kept
     */
    public String getGroupName() {
        return _groupName;
    }

    /**
     * Description of what this rate stat measures.
     * @return a simple description of this statistic
     */
    public String getDescription() {
        return _description;
    }

    /**
     * Tracked periods for this rate.
     * @return the periods this rate is tracked over, in milliseconds
     */
    public long[] getPeriods() {
        long[] rv = new long[_rates.length];
        for (int i = 0; i < _rates.length; i++) rv[i] = _rates[i].getPeriod();
        return rv;
    }

    /**
     * Lifetime average from the shortest period.
     * @return the lifetime average value from the shortest period's rate
     */
    public double getLifetimeAverageValue() {
        return _rates[0].getLifetimeAverageValue();
    }

    /**
     * Lifetime event count from the shortest period.
     * @return the lifetime event count from the shortest period's rate
     */
    public long getLifetimeEventCount() {
        return _rates[0].getLifetimeEventCount();
    }

    /**
     * Returns rate with requested period if it exists,
     * otherwise null
     *
     * @param period ms
     * @return the Rate
     */
    public Rate getRate(long period) {
        for (Rate r : _rates) {
            if (r.getPeriod() == period) return r;
        }

        return null;
    }

    /**
     * Tests if a rate with the provided period exists within this RateStat.
     *
     * @param period ms
     * @return true if exists
     * @since 0.8.8
     */
    public boolean containsRate(long period) {
        return getRate(period) != null;
    }

    @Override
    public int hashCode() {
        return _statName.hashCode();
    }

    private static final String NL = System.getProperty("line.separator");
    private static final String HR = "#----------------------------------------------------------------------------------------";

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(4096);
        buf.append(getGroupName()).append('.').append(getName()).append(": ").append(getDescription()).append('\n');
        long[] periods = getPeriods();
        sort(periods);
        for (int i = 0; i < periods.length; i++) {
            buf.append('\t').append(periods[i]).append(':');
            Rate curRate = getRate(periods[i]);
            buf.append(curRate.toString());
            buf.append(NL);
        }
        return buf.toString();
    }

    /**
     * Object to compare.
     * @param obj the object to compare
     * @return true if equal by name, group, description, and rates
     */
    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof RateStat)) return false;
        if (obj == this) return true;
        RateStat rs = (RateStat) obj;
        if (nameGroupDescEquals(rs)) return deepEquals(this._rates, rs._rates);

        return false;
    }

    /**
     * Whether the other stat shares name, group, and description.
     * @param rs the other RateStat
     */
    boolean nameGroupDescEquals(RateStat rs) {
        return DataHelper.eq(getGroupName(), rs.getGroupName()) && DataHelper.eq(getDescription(), rs.getDescription()) && DataHelper.eq(getName(), rs.getName());
    }

    /**
     * Includes comment lines
     *
     * @param out the output stream
     * @param prefix the property prefix
     * @throws IOException if an I/O error occurs
     */
    public void store(OutputStream out, String prefix) throws IOException {
        store(out, prefix, true);
    }

    /**
     * Stores the rate statistics to an output stream.
     *
     * @param out the output stream
     * @param prefix the property prefix
     * @param addComments add comment lines to the output
     * @throws IOException if an I/O error occurs
     * @since 0.9.41
     */
    public void store(OutputStream out, String prefix, boolean addComments) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        if (addComments) {
            buf.append(NL);
            buf.append(HR).append(NL);
            buf.append("# ").append(_description).append(" [").append(_statName).append("]").append(NL);
            buf.append(HR).append(NL);
            out.write(buf.toString().getBytes(StandardCharsets.UTF_8));
            buf.setLength(0);
        }
        for (Rate r : _rates) {
            if (addComments) {
                buf.append(NL);
                buf.append("# Period: ").append(DataHelper.formatDuration(r.getPeriod())).append(" [").append(_statName).append("]").append(NL);
                buf.append(HR).append(NL).append(NL);
            }
            String curPrefix = prefix + "." + DataHelper.formatDuration(r.getPeriod());
            r.store(curPrefix, buf, addComments);
            out.write(buf.toString().getBytes(StandardCharsets.UTF_8));
            buf.setLength(0);
        }
    }

    /**
     * Load this rate stat from the properties, populating all of the rates contained
     * underneath it.  The comes from the given prefix (e.g. if we are given the prefix
     * "profile.dbIntroduction", a series of rates may be found underneath
     * "profile.dbIntroduction.60s", "profile.dbIntroduction.60m", and "profile.dbIntroduction.24h").
     * This RateStat must already be created, with the specified rate entries constructued - this
     * merely loads them with data.
     *
     * @param props the properties
     * @param prefix prefix to the property entries (should NOT end with a period)
     * @param treatAsCurrent if true, we'll treat the loaded data as if no time has
     *                       elapsed since it was written out, but if it is false, we'll
     *                       treat the data with as much freshness (or staleness) as appropriate.
     *
     * @throws IllegalArgumentException if the data was formatted incorrectly
     */
    public void load(Properties props, String prefix, boolean treatAsCurrent) throws IllegalArgumentException {
        for (Rate r : _rates) {
            long period = r.getPeriod();
            String curPrefix = prefix + "." + DataHelper.formatDuration(period);
            r.load(props, curPrefix, treatAsCurrent);
        }
    }
}
