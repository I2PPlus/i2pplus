package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.Writer;
import java.security.GeneralSecurityException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Publishes some statistics about the router in the netDB.
 *
 */
public class StatisticsManager {
    private final Log _log;
    private final RouterContext _context;
    private final String _networkID;

    public final static String PROP_PUBLISH_RANKINGS = "router.publishPeerRankings";
    //private static final String PROP_CONTACT_NAME = "netdb.contact";
    /** enhance anonymity by only including build stats one out of this many times */
//    private static final int RANDOM_INCLUDE_STATS = 16;
    private static final int RANDOM_INCLUDE_STATS = 1024;
    //// remove after release ////
    private static final boolean SIMPLE_STATS = CoreVersion.PUBLISHED_VERSION.equals("0.9.58");

    private final DecimalFormat _fmt;
    private final DecimalFormat _pct;

    public StatisticsManager(RouterContext context) {
        _context = context;
        _fmt = SIMPLE_STATS ? new DecimalFormat("0.00") :
                              new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
        _pct = new DecimalFormat("#0.00%", new DecimalFormatSymbols(Locale.UK));
        _log = context.logManager().getLog(StatisticsManager.class);
        // null for some tests
        Router r = context.router();
        _networkID = r != null ? Integer.toString(r.getNetworkID()) : "2";
    }

    /**
     *  Retrieve a snapshot of the statistics that should be published.
     *
     *  This includes all standard options (as of 0.9.24, network ID and caps)
     */
    public Properties publishStatistics() {
        // if hash is null, will be caught in fkc.sign()
        return publishStatistics(_context.routerHash());
    }

    /**
     *  Retrieve a snapshot of the statistics that should be published.
     *
     *  This includes all standard options (as of 0.9.24, network ID and caps)
     *
     *  @param h current router hash, non-null
     *  @since 0.9.24
     */
    public Properties publishStatistics(Hash h) {
        Properties stats = new Properties();
        stats.setProperty("router.version", CoreVersion.PUBLISHED_VERSION);
        stats.setProperty(RouterInfo.PROP_NETWORK_ID, _networkID);
        String caps = _context.router().getCapabilities();
        boolean isFF = caps.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0;
        stats.setProperty(RouterInfo.PROP_CAPABILITIES, caps);

        if (_context.getBooleanPropertyDefaultTrue(PROP_PUBLISH_RANKINGS) &&
            _context.random().nextInt(RANDOM_INCLUDE_STATS) == 0 &&
            _context.router().getUptime() > 62*60*1000) {
            includeRate("tunnel.participatingTunnels", stats, new long[] { 60*60*1000 }, true);
            long rate = 60*60*1000;
            includeTunnelRates("Exploratory", stats, rate);
        }

        if (isFF) {
            int ri = _context.router().getUptime() > 30*60*1000 ?
                     _context.mainNetDb().getKnownRouters() :
                     3000 + _context.random().nextInt(1000);   // so it isn't obvious we restarted
            stats.setProperty("netdb.knownRouters", String.valueOf(ri));
            int ls = _context.router().getUptime() > 30*60*1000 ?
                     _context.mainNetDb().getKnownLeaseSets() :
                     30 + _context.random().nextInt(40);   // so it isn't obvious we restarted
            stats.setProperty("netdb.knownLeaseSets", String.valueOf(ls));
        }

        String family = _context.getProperty(FamilyKeyCrypto.PROP_FAMILY_NAME);
        if (family != null) {
            String sig = null;
            String key = null;
            RouterInfo oldRI = _context.router().getRouterInfo();
            if (oldRI != null) {
                // don't do it if family changed
                if (family.equals(oldRI.getOption(FamilyKeyCrypto.OPT_NAME))) {
                    // copy over the pubkey and signature
                    key = oldRI.getOption(FamilyKeyCrypto.OPT_KEY);
                    if (key != null) {
                        if (key.contains(";")) {
                            // we changed the separator from ';' to ':'
                            key = null;
                        } else {
                            sig = oldRI.getOption(FamilyKeyCrypto.OPT_SIG);
                            if (sig != null) {
                                stats.setProperty(FamilyKeyCrypto.OPT_NAME, family);
                                stats.setProperty(FamilyKeyCrypto.OPT_KEY, key);
                                stats.setProperty(FamilyKeyCrypto.OPT_SIG, sig);
                            }
                        }
                    }
                }
            }
            if (sig == null || key == null) {
                FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
                if (fkc != null) {
                    try {
                        stats.putAll(fkc.sign(family, h));
                    } catch (GeneralSecurityException gse) {
                        _log.error("Failed to sign Router family", gse);
                        stats.remove(FamilyKeyCrypto.OPT_KEY);
                        stats.remove(FamilyKeyCrypto.OPT_SIG);
                    }
                }
            }
        }

        return stats;
    }

    /**
     * @param fudgeQuantity the data being published in this stat is too sensitive to, uh
     *                      publish, so we're kludge the quantity (allowing the fairly safe
     *                      publication of the average values
     */
    private void includeRate(String rateName, Properties stats, long selectedPeriods[],
                             boolean fudgeQuantity) {
        RateStat rate = _context.statManager().getRate(rateName);
        if (rate == null) return;
        long periods[] = rate.getPeriods();
        for (int i = 0; i < periods.length; i++) {
            if (periods[i] > _context.router().getUptime()) continue;
            if (selectedPeriods != null) {
                boolean found = false;
                for (int j = 0; j < selectedPeriods.length; j++) {
                    if (selectedPeriods[j] == periods[i]) {
                        found = true;
                        break;
                    }
                }
                if (!found) continue;
            }

            Rate curRate = rate.getRate(periods[i]);
            if (curRate == null) continue;
            if (curRate.getLifetimeEventCount() <= 0) continue;
            stats.setProperty("stat_" + rateName + '.' + getPeriod(curRate), renderRate(curRate, fudgeQuantity));
        }
    }

    /**
     *  Simple format, only what stats.i2p needs:
     *<pre>
     *  average
     *</pre>
     *
     *  Previous format:
     *<pre>
     *  avg;extreme avg;pct of lifetime avg;
     *  if lifetime total event time greater than zero:
     *       lastEventSaturation;lastSaturationLimit;extremeEventSaturation;extremeSaturationLimit;
     *  event count;
     *  if number of periods greater than zero:
     *       avg freq;exteremeEventCount;lifetimeEventCount;
     *</pre>
     */
    private String renderRate(Rate rate, boolean fudgeQuantity) {
        if (SIMPLE_STATS)
            return num(rate.getAverageValue());
        StringBuilder buf = new StringBuilder(128);
        buf.append(num(rate.getAverageValue())).append(';');
        buf.append(num(rate.getExtremeAverageValue())).append(';');
        buf.append(pct(rate.getPercentageOfLifetimeValue())).append(';');
        if (rate.getLifetimeTotalEventTime() > 0) {
            buf.append(pct(rate.getLastEventSaturation())).append(';');
            buf.append(num(rate.getLastSaturationLimit())).append(';');
            buf.append(pct(rate.getExtremeEventSaturation())).append(';');
            buf.append(num(rate.getExtremeSaturationLimit())).append(';');
        }
        long numPeriods = rate.getLifetimePeriods();
        if (fudgeQuantity) {
            buf.append("555;");
            if (numPeriods > 0) {
                buf.append("555;555;");
            }
        } else {
            buf.append(num(rate.getLastEventCount())).append(';');
            if (numPeriods > 0) {
                double avgFrequency = rate.getLifetimeEventCount() / (double)numPeriods;
                buf.append(num(avgFrequency)).append(';');
                buf.append(num(rate.getExtremeEventCount())).append(';');
                buf.append(num(rate.getLifetimeEventCount())).append(';');
            }
        }
        return buf.toString();
    }

    private static final String[] tunnelStats = { "Expire", "Reject", "Success" };

    /**
     *  Add tunnel build rates with some mods to hide absolute quantities
     *  In particular, report counts normalized to 100 (i.e. a percentage)
     */
    private void includeTunnelRates(String tunnelType, Properties stats, long selectedPeriod) {
        long totalEvents = 0;
        for (String tunnelStat : tunnelStats) {
            String rateName = "tunnel.build" + tunnelType + tunnelStat;
            RateStat stat = _context.statManager().getRate(rateName);
            if (stat == null) continue;
            Rate curRate = stat.getRate(selectedPeriod);
            if (curRate == null) continue;
            totalEvents += curRate.getLastEventCount();
        }
        if (totalEvents <= 0)
            return;
        for (String tunnelStat : tunnelStats) {
            String rateName = "tunnel.build" + tunnelType + tunnelStat;
            RateStat stat = _context.statManager().getRate(rateName);
            if (stat == null) continue;
            Rate curRate = stat.getRate(selectedPeriod);
            if (curRate == null) continue;
            double fudgeQuantity = 100.0d * curRate.getLastEventCount() / totalEvents;
            stats.setProperty("stat_" + rateName + '.' + getPeriod(curRate), renderRate(curRate, fudgeQuantity));
        }
    }

    /**
     *  Simple format, only what stats.i2p needs:
     *<pre>
     *  0;0;0;percent or event count
     *</pre>
     *
     *  Previous format: see above
     */
    private String renderRate(Rate rate, double fudgeQuantity) {
        if (SIMPLE_STATS)
            return "0;0;0;" + num(fudgeQuantity);
        StringBuilder buf = new StringBuilder(128);
        buf.append(num(rate.getAverageValue())).append(';');
        buf.append(num(rate.getExtremeAverageValue())).append(';');
        buf.append(pct(rate.getPercentageOfLifetimeValue())).append(';');
        if (rate.getLifetimeTotalEventTime() > 0) {
            // bah saturation
            buf.append("0;0;0;0;");
        }
        buf.append(num(fudgeQuantity)).append(';');
        return buf.toString();
    }

    private static String getPeriod(Rate rate) { return DataHelper.formatDuration(rate.getPeriod()); }

    private final String num(double num) {
        if (num < 0) num = 0;
        synchronized (_fmt) { return _fmt.format(num); }
    }

    private final String pct(double num) {
        if (num < 0) num = 0;
        synchronized (_pct) { return _pct.format(num); }
    }

    public void renderStatusHTML(Writer out) { }
}
