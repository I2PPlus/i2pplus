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
    private static final String PROP_CONTACT_NAME = "netdb.contact";
    /** enhance anonymity by only including build stats one out of this many times */
    private static final int RANDOM_INCLUDE_STATS = 1024;

    private final DecimalFormat _fmt;
    private final DecimalFormat _pct;

    public StatisticsManager(RouterContext context) {
        _context = context;
        _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
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
        stats.setProperty(RouterInfo.PROP_CAPABILITIES, _context.router().getCapabilities());

//        if (_context.getBooleanPropertyDefaultTrue(PROP_PUBLISH_RANKINGS) &&
//            _context.random().nextInt(RANDOM_INCLUDE_STATS) == 0) {
        int rnd = Math.max(_context.random().nextInt(60), _context.random().nextInt(30) + 30) *
                  Math.max(_context.random().nextInt(4), _context.random().nextInt(3) + 1) * 2 * 60;
        if (_context.getProperty(PROP_PUBLISH_RANKINGS) != null  && _context.getProperty(PROP_PUBLISH_RANKINGS) == "true" &&
            _context.random().nextInt(RANDOM_INCLUDE_STATS) == 0 && _context.router().getUptime() > Math.max(62*60*1000, rnd)) {
            // Disabled in 0.9
            //if (publishedUptime > 62*60*1000)
            //    includeAverageThroughput(stats);
            //includeRate("router.invalidMessageTime", stats, new long[] { 10*60*1000 });
            //includeRate("router.duplicateMessageId", stats, new long[] { 24*60*60*1000 });
            //includeRate("tunnel.duplicateIV", stats, new long[] { 24*60*60*1000 });
            //includeRate("tunnel.fragmentedDropped", stats, new long[] { 60*1000, 10*60*1000, 3*60*60*1000 });
            //includeRate("tunnel.fullFragments", stats, new long[] { 60*1000, 10*60*1000, 3*60*60*1000 });
            //includeRate("tunnel.smallFragments", stats, new long[] { 60*1000, 10*60*1000, 3*60*60*1000 });
            //includeRate("tunnel.testFailedTime", stats, new long[] { 10*60*1000 });

            //includeRate("tunnel.batchDelaySent", stats, new long[] { 60*1000, 10*60*1000, 60*60*1000 });
            //includeRate("tunnel.batchMultipleCount", stats, new long[] { 60*1000, 10*60*1000, 60*60*1000 });
            //includeRate("tunnel.corruptMessage", stats, new long[] { 60*1000, 60*60*1000l, 3*60*60*1000l });

            //includeRate("router.throttleTunnelProbTestSlow", stats, new long[] { 60*1000, 60*60*1000 });
            //includeRate("router.throttleTunnelProbTooFast", stats, new long[] { 60*1000, 60*60*1000 });
            //includeRate("router.throttleTunnelProcessingTime1m", stats, new long[] { 60*1000, 60*60*1000 });

            //includeRate("router.fastPeers", stats, new long[] { 60*1000, 60*60*1000 });

            //includeRate("udp.statusOK", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusDifferent", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusReject", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusUnknown", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusKnownCharlie", stats, new long[] { 1*60*1000, 10*60*1000 });
            //includeRate("udp.addressUpdated", stats, new long[] { 1*60*1000 });
            //includeRate("udp.addressTestInsteadOfUpdate", stats, new long[] { 1*60*1000 });

            //includeRate("clock.skew", stats, new long[] { 60*1000, 10*60*1000, 3*60*60*1000, 24*60*60*1000 });

            //includeRate("transport.sendProcessingTime", stats, new long[] { 60*1000, 60*60*1000 });
            //includeRate("jobQueue.jobRunSlow", stats, new long[] { 10*60*1000l, 60*60*1000l });
            //includeRate("crypto.elGamal.encrypt", stats, new long[] { 60*1000, 60*60*1000 });
            // total event count can be used to track uptime
            int partTunnels = _context.tunnelManager().getParticipatingCount();
            int spoofed = partTunnels;
            if (partTunnels > 4000) {
                if (partTunnels > 8000)
                    spoofed = (partTunnels / 3) - _context.random().nextInt(50);
                else if (partTunnels > 4000)
                    spoofed = (partTunnels / 2) + _context.random().nextInt(50);
                stats.setProperty("tunnels.participatingTunnels", String.valueOf(spoofed));
            } else {
                includeRate("tunnel.participatingTunnels", stats, new long[] { 60*60*1000 }, true);
            }
            //includeRate("tunnel.testSuccessTime", stats, new long[] { 10*60*1000l });
            //includeRate("client.sendAckTime", stats, new long[] { 60*1000, 60*60*1000 }, true);
            //includeRate("udp.sendConfirmTime", stats, new long[] { 10*60*1000 });
            //includeRate("udp.sendVolleyTime", stats, new long[] { 10*60*1000 });
            //includeRate("udp.ignoreRecentDuplicate", stats, new long[] { 60*1000 });
            //includeRate("udp.congestionOccurred", stats, new long[] { 10*60*1000 });
            //includeRate("stream.con.sendDuplicateSize", stats, new long[] { 60*1000, 60*60*1000 });
            //includeRate("stream.con.receiveDuplicateSize", stats, new long[] { 60*1000, 60*60*1000 });

            //stats.setProperty("stat__rateKey", "avg;maxAvg;pctLifetime;[sat;satLim;maxSat;maxSatLim;][num;lifetimeFreq;maxFreq]");

            //includeRate("tunnel.decryptRequestTime", stats, new long[] { 60*1000, 10*60*1000 });
            //includeRate("udp.packetDequeueTime", stats, new long[] { 60*1000 });
            //includeRate("udp.packetVerifyTime", stats, new long[] { 60*1000 });

            //includeRate("tunnel.buildRequestTime", stats, new long[] { 10*60*1000 });
            long rate = 60*60*1000;
            //includeTunnelRates("Client", stats, rate);
            includeTunnelRates("Exploratory", stats, rate);
            //includeRate("tunnel.rejectTimeout", stats, new long[] { 10*60*1000 });
            //includeRate("tunnel.rejectOverloaded", stats, new long[] { 10*60*1000 });
            //includeRate("tunnel.acceptLoad", stats, new long[] { 10*60*1000 });
        }

        // So that we will still get build requests - not required since 0.7.9 2010-01-12
        //stats.setProperty("stat_uptime", "90m");
        if (FloodfillNetworkDatabaseFacade.isFloodfill(_context.router().getRouterInfo())) {
            int ri = _context.router().getUptime() > 30*60*1000 ?
                     _context.netDb().getKnownRouters() :
                     3000 + _context.random().nextInt(1000);   // so it isn't obvious we restarted
            if (ri > 12000) {
                ri /= 3;
                ri -= _context.random().nextInt(50); // hide our real number of known peers to avoid broadcasting that we're running I2P+
            } else if (ri > 5000) {
                ri /= 2;
                ri += _context.random().nextInt(50); // hide our real number of known peers to avoid broadcasting that we're running I2P+
            }
            stats.setProperty("netdb.knownRouters", String.valueOf(ri));
            int ls = _context.router().getUptime() > 30*60*1000 ?
                     _context.netDb().getKnownLeaseSets() :
                     30 + _context.random().nextInt(40);   // so it isn't obvious we restarted
            stats.setProperty("netdb.knownLeaseSets", String.valueOf(ls));
        }

        String contact = _context.getProperty(PROP_CONTACT_NAME);
        if (contact != null)
            stats.setProperty("contact", contact);

        String family = _context.getProperty(FamilyKeyCrypto.PROP_FAMILY_NAME);
        if (family != null) {
            stats.setProperty(FamilyKeyCrypto.OPT_NAME, family);
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
                            stats.setProperty(FamilyKeyCrypto.OPT_KEY, key);
                            sig = oldRI.getOption(FamilyKeyCrypto.OPT_SIG);
                            if (sig != null)
                                stats.setProperty(FamilyKeyCrypto.OPT_SIG, sig);
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
                        _log.error("Failed to sign router family", gse);
                        stats.remove(FamilyKeyCrypto.OPT_KEY);
                        stats.remove(FamilyKeyCrypto.OPT_SIG);
                    }
                }
            }
        }

        return stats;
    }

/*****
    private void includeRate(String rateName, Properties stats, long selectedPeriods[]) {
        includeRate(rateName, stats, selectedPeriods, false);
    }
*****/

    /**
     * @param fudgeQuantity the data being published in this stat is too sensitive to, uh
     *                      publish, so we'll kludge the quantity (allowing the fairly safe
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

    private String renderRate(Rate rate, boolean fudgeQuantity) {
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

    private String renderRate(Rate rate, double fudgeQuantity) {
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

    /* report the same data for tx and rx, for enhanced anonymity */
    private void includeAverageThroughput(Properties stats) {
        RateStat sendRate = _context.statManager().getRate("bw.sendRate");
        RateStat recvRate = _context.statManager().getRate("bw.recvRate");
        if (sendRate == null || recvRate == null)
            return;
        Rate s = sendRate.getRate(60*60*1000);
        Rate r = recvRate.getRate(60*60*1000);
        if (s == null || r == null)
            return;
        double speed = (s.getAverageValue() + r.getAverageValue()) / 2;
        double max = Math.max(s.getExtremeAverageValue(), r.getExtremeAverageValue());
        String str = num(speed) + ';' + num(max) + ";0;0;";
        stats.setProperty("stat_bandwidthSendBps.60m", str);
        stats.setProperty("stat_bandwidthReceiveBps.60m", str);
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
