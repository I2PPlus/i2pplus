package net.i2p.stat;

import static java.util.Arrays.*;

import net.i2p.data.DataHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;

import java.nio.charset.StandardCharsets;
/** coordinate a moving rate over various periods */
public class RateStat {
    /** unique name of the statistic */
    private final String _statName;

    /** grouping under which the stat is kept */
    private final String _groupName;

    /** describe the stat */
    private final String _description;

    /** actual rate objects for this statistic */
    protected final Rate[] _rates;

    /**
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
     * update all of the rates for the various periods with the given value.
     */
    public void addData(long value, long eventDuration) {
        for (Rate r : _rates) r.addData(value, eventDuration);
    }

    /**
     * Update all of the rates for the various periods with the given value.
     * Zero duration.
     *
     * @since 0.8.10
     */
    public void addData(long value) {
        for (Rate r : _rates) r.addData(value);
    }

    /** coalesce all the stats */
    public void coalesceStats() {
        for (Rate r : _rates) r.coalesce();
    }

    /** Return the unique name of this statistic. @return the unique name of this statistic */
    public String getName() {
        return _statName;
    }

    /** Return the grouping name under which this statistic is kept. @return the grouping name under which this statistic is kept */
    public String getGroupName() {
        return _groupName;
    }

    /** Return a simple description of this statistic. @return a simple description of this statistic */
    public String getDescription() {
        return _description;
    }

    /** Return the periods this rate is tracked over, in milliseconds. @return the periods this rate is tracked over, in milliseconds */
    public long[] getPeriods() {
        long[] rv = new long[_rates.length];
        for (int i = 0; i < _rates.length; i++) rv[i] = _rates[i].getPeriod();
        return rv;
    }

    /** Return the lifetime average value from the shortest period's rate. @return the lifetime average value from the shortest period's rate */
    public double getLifetimeAverageValue() {
        return _rates[0].getLifetimeAverageValue();
    }

    /** Return the lifetime event count from the shortest period's rate. @return the lifetime event count from the shortest period's rate */
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

    boolean nameGroupDescEquals(RateStat rs) {
        return DataHelper.eq(getGroupName(), rs.getGroupName()) && DataHelper.eq(getDescription(), rs.getDescription()) && DataHelper.eq(getName(), rs.getName());
    }

    /**
     * Includes comment lines
     */
    public void store(OutputStream out, String prefix) throws IOException {
        store(out, prefix, true);
    }

    /**
     *  Stores the rate statistics to an output stream.
     *
     * @param addComments add comment lines to the output
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
