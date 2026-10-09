package net.i2p.router.web.helpers;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeMap;
import net.i2p.router.web.GraphGenerator;
import net.i2p.router.web.HelperBase;
import net.i2p.stat.FrequencyStat;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.Log;

/**
 * Helper for statistics configuration page rendering and form processing.
 * @since 0.9.33
 */
public class ConfigStatsHelper extends HelperBase {
    private Log _log;
    private final Set<String> _graphs;
    /** list of names of stats which are remaining, ordered by nested groups */
    private final List<String> _stats;
    private String _currentStatName;
    private String _currentGraphName;
    private String _currentStatDescription;
    private String _currentGroup;
    /** true if the current stat is the first in the group */
    private boolean _currentIsFirstInGroup;
    private boolean _currentIsGraphed;
    private boolean _currentCanBeGraphed;

    /**
     * Construct a new ConfigStatsHelper.
     */
    public ConfigStatsHelper() {
        _stats = new ArrayList<>();
        _graphs = new HashSet<>();
    }

    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId beginning few characters of the routerHash, or null to pick
     * the first one we come across.
     */
    @Override
    public void setContextId(String contextId) {
        super.setContextId(contextId);
        _log = _context.logManager().getLog(ConfigStatsHelper.class);

        Map<String, SortedSet<String>> unsorted = _context.statManager().getStatsByGroup();
        Map<String, Set<String>> groups = new TreeMap<>(new AlphaComparator());
        groups.putAll(unsorted);
        for (Set<String> stats : groups.values()) {
             _stats.addAll(stats);
        }

        // create a local copy of the config. Querying r.getSummaryListener()
        // lags behind, as GraphGenerator only runs once a minute.
        String specs = _context.getProperty("stat.summaries", GraphGenerator.DEFAULT_DATABASES);
        StringTokenizer tok = new StringTokenizer(specs, ",");
        while (tok.hasMoreTokens()) {
            _graphs.add(tok.nextToken().trim());
        }
    }

    /**
     * move the cursor to the next known stat, returning true if a valid
     * stat is available.
     *
     * @return true if a valid stat is available, otherwise false
     */
    public boolean hasMoreStats() {
        if (_stats.isEmpty())
            return false;
        _currentIsGraphed = false;
        _currentStatName = _stats.remove(0);
        RateStat rs = _context.statManager().getRate(_currentStatName);
        if (rs != null) {
            _currentStatDescription = rs.getDescription();
            if (_currentGroup == null)
                _currentIsFirstInGroup = true;
            else if (!rs.getGroupName().equals(_currentGroup))
                _currentIsFirstInGroup = true;
            else
                _currentIsFirstInGroup = false;
            _currentGroup = rs.getGroupName();
            long period = rs.getPeriods()[0]; // should be the minimum
            if (period <= 10*60*1000) {
                Rate r = rs.getRate(period);
                _currentCanBeGraphed = r != null;
                if (_currentCanBeGraphed) {
                    // see above
                    //_currentIsGraphed = r.getSummaryListener() != null;
                    _currentGraphName = _currentStatName + "." + period;
                    _currentIsGraphed = _graphs.contains(_currentGraphName);
                }
            } else {
                _currentCanBeGraphed = false;
            }
        } else {
            FrequencyStat fs = _context.statManager().getFrequency(_currentStatName);
            if (fs != null) {
                _currentStatDescription = fs.getDescription();
                if (_currentGroup == null)
                    _currentIsFirstInGroup = true;
                else if (!fs.getGroupName().equals(_currentGroup))
                    _currentIsFirstInGroup = true;
                else
                    _currentIsFirstInGroup = false;
                _currentGroup = fs.getGroupName();
                _currentCanBeGraphed = false;
            } else {
                if (_log.shouldError())
                    _log.error("Stat does not exist?! [" + _currentStatName + "]");
                return false;
            }
        }
        return true;
    }

    /**
     * Is the current stat the first in the group?
     *
     * @return true if the current stat is the first in the group
     */
    public boolean groupRequired() {
        if (_currentIsFirstInGroup) {
            _currentIsFirstInGroup = false;
            return true;
        } else {
            return false;
        }
    }
    /**
     * What group is the current stat in.
     *
     * @return the current group name
     */
    public String getCurrentGroupName() { return _currentGroup; }
    /**
     * Get the current stat name.
     *
     * @return the current stat name
     */
    public String getCurrentStatName() { return _currentStatName; }
    /**
     * Get the display label for the current stat.
     *
     * @return the label shown beside the checkbox
     * @since 0.9.71+
     */
    public String getCurrentStatLabel() { return statLabel(_currentStatName); }
    /**
     * The short name shown beside a stat's checkbox on the configstats page.
     *
     * <p>An ordinary stat name is a dotted subsystem path such as
     * {@code tunnel.buildTimeout}, and the subsystem is dropped because the section
     * header already states it. A pool's rates are named for the tunnel instead:
     * {@code [harry.i2p] InBps}. The first dot there sits inside the name, and cutting
     * at it turned four different services into four identical "i2p] InBps" labels.
     * A prefix carrying a bracket or a space is therefore a name rather than a
     * subsystem, and is shown whole.
     *
     * @param statName the stat name, or null
     * @return the display label, never null; null yields the empty string
     * @since 0.9.71+
     */
    static String statLabel(String statName) {
        if (statName == null) {
            return "";
        }
        int dot = statName.indexOf('.');
        if (dot > 0) {
            String prefix = statName.substring(0, dot);
            if (prefix.indexOf('[') >= 0 || prefix.indexOf(' ') >= 0) {
                return statName;
            }
            statName = statName.substring(dot + 1);
        }
        return abbreviate(statName);
    }

    /**
     * Shorten a stat name to what fits beside its checkbox.
     *
     * <p>The rules are ordered rather than alphabetical: {@code receive} rewrites to
     * {@code RX}, which the later {@code RXBps} rule expands again to {@code ReceiveBps},
     * so the chain may not be reordered. Every rule names a stat actually seen on the
     * page. The dotted rules only ever see a name whose subsystem prefix was dropped, a
     * pool name with a dot in it having been returned whole by {@link #statLabel}.
     *
     * @param label a stat name, with any subsystem prefix already removed
     * @return the abbreviated label, never null
     */
    private static String abbreviate(String label) {
        return label.replace("participating", "part").replace("Exploratory", "Expl")
                    .replace("Received", "RX")
                    .replace("con.", "").replace("garlic.decryptFail", "garlic.DecryptFail")
                    .replace(".data", ".Data").replace(".drop", ".Drop").replace(".delay", ".Delay")
                    .replace(".new", ".New").replace(".in", ".In").replace(".out", ".Out")
                    .replace("receive", "RX").replace("RXBps", "ReceiveBps")
                    .replace(".full", ".Full").replace(".size", ".Size").replace(".dups", ".Dups");
    }
    /**
     * Get the current graph name.
     *
     * @return the current graph name
     */
    public String getCurrentGraphName() { return _currentGraphName; }
    /**
     * Get the description of the current stat.
     *
     * @return the current stat description
     */
    public String getCurrentStatDescription() { return _currentStatDescription; }
    /**
     * Check whether the current stat is graphed.
     *
     * @return true if the current stat is graphed
     */
    public boolean getCurrentIsGraphed() { return _currentIsGraphed; }
    /**
     * Check whether the current stat can be graphed.
     *
     * @return true if the current stat can be graphed
     */
    public boolean getCurrentCanBeGraphed() { return _currentCanBeGraphed; }
    /**
     * Check whether the full stats are enabled.
     *
     * @return true if full stats are enabled
     */
    public boolean getIsFull() { return _context.getBooleanProperty(StatManager.PROP_STAT_FULL); }

    /**
     * Translated sort
     * Inner class, can't be Serializable
     * @since 0.9.4
     */
    private class AlphaComparator implements Comparator<String> {
        @Override
        public int compare(String lhs, String rhs) {
            // compare raw keys, not translated
            boolean lrouter = lhs.startsWith("Router");
            boolean rrouter = rhs.startsWith("Router");
            if (lrouter && !rrouter) {return -1;}
            if (rrouter && !lrouter) {return 1;}

            String lname = _t(lhs);
            String rname = _t(rhs);
            return Collator.getInstance().compare(lname, rname);
        }
    }
}
