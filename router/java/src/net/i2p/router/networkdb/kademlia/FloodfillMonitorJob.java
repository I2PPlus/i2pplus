package net.i2p.router.networkdb.kademlia;

import java.util.List;

import net.i2p.crypto.SigType;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.util.EventLog;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Simple job to monitor the floodfill pool.
 * If we are class N or O, and meet some other criteria,
 * we will automatically become floodfill if there aren't enough.
 * But only change ff status every few hours to minimize ff churn.
 *
 */
class FloodfillMonitorJob extends JobImpl {
    private final Log _log;
    private final FloodfillNetworkDatabaseFacade _facade;
    private long _lastChanged;
    private boolean _deferredFlood;

//    private static final int REQUEUE_DELAY = 60*60*1000;
    private static final int REQUEUE_DELAY = 15*60*1000;
    private static final long MIN_UPTIME = 2*60*60*1000;
//    private static final long MIN_CHANGE_DELAY = 6*60*60*1000;
    private static final long MIN_CHANGE_DELAY = 3*60*60*1000;

//    private static final int MIN_FF = 5000;
    private static final int MIN_FF = 2000;
    private static final int MAX_FF = 999999;
    static final String PROP_FLOODFILL_PARTICIPANT = "router.floodfillParticipant";
    private Boolean autoff = true;

    public FloodfillMonitorJob(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        super(context);
        _facade = facade;
        _log = context.logManager().getLog(FloodfillMonitorJob.class);
    }

    public String getName() { return "Monitor Floodfill Pool"; }

    public synchronized void runJob() {
        if (!getContext().commSystem().isRunning()) {
            // Avoid deadlock in the transports through here via Router.rebuildRouterInfo() at startup
            if (_log.shouldWarn())
                _log.warn("Attempted to initialize Floodfill Monitor before comm system started, requeuing...");
            requeue(100);
            return;
        }
        boolean wasFF = _facade.floodfillEnabled();
        boolean ff = shouldBeFloodfill();
        if (wasFF)
            autoff = false;
        _facade.setFloodfillEnabledFromMonitor(ff);
        if (ff != wasFF) {
            if (ff) {
                if (!(getContext().getBooleanProperty(PROP_FLOODFILL_PARTICIPANT) &&
                      getContext().router().getUptime() < 3*60*1000)) {
                    getContext().router().eventLog().addEvent(EventLog.BECAME_FLOODFILL);
                }
            } else {
                getContext().router().eventLog().addEvent(EventLog.NOT_FLOODFILL);
            }
            getContext().router().rebuildRouterInfo(true);
            Job routerInfoFlood = new FloodfillRouterInfoFloodJob(getContext(), _facade);
            if (getContext().router().getUptime() < 5*60*1000) {
                if (!_deferredFlood) {
                    // Needed to prevent race if router.floodfillParticipant=true (not auto)
                    // Don't queue multiples
                    _deferredFlood = true;
                    routerInfoFlood.getTiming().setStartAfter(getContext().clock().now() + 5*60*1000);
                    getContext().jobQueue().addJob(routerInfoFlood);
                    if (_log.shouldDebug())
                        _log.logAlways(Log.DEBUG, "Deferred our FloodfillRouterInfoFloodJob run because of low uptime.");
                }
            } else {
                routerInfoFlood.runJob();
                if(_log.shouldDebug()) {
                    _log.logAlways(Log.DEBUG, "Running FloodfillRouterInfoFloodJob...");
                }
            }
        }
        if (autoff && _log.shouldInfo())
            _log.info("Should we be a Floodfill? " + ff);
        int delay = (REQUEUE_DELAY / 2) + getContext().random().nextInt(REQUEUE_DELAY);
        // there's a lot of eligible non-floodfills, keep them from all jumping in at once
        // TODO: somehow assess the size of the network to make this adaptive?

        if (!ff)
            delay *= 4; // this was 7, reduced for moar FFs --zab
        else
            delay *= 10; // slow down check if we're already a floodfill
        requeue(delay);
    }

    private boolean shouldBeFloodfill() {
        if (!SigType.ECDSA_SHA256_P256.isAvailable()) {
            boolean wasFF = _facade.floodfillEnabled();
            if (wasFF)
                autoff = false;
            if (autoff && _log.shouldInfo())
                _log.info("ECDSA SHA256 P256 unavailable on this router - not automatically enrolling as floodfill");
            return false;
        }

        // Hidden trumps netDb.floodfillParticipant=true
        if (getContext().router().isHidden()) {
            if (autoff && _log.shouldInfo())
                _log.info("Hidden mode enabled - not automatically enrolling as floodfill");
            return false;
        }

        String enabled = getContext().getProperty(PROP_FLOODFILL_PARTICIPANT, "auto");
        if ("true".equals(enabled))
            return true;
        if ("false".equals(enabled))
            return false;

        // auto from here down

        // Only if not shutting down...
        if (getContext().router().gracefulShutdownInProgress())
            return false;

        // ARM ElG decrypt is too slow
        if (SystemVersion.isSlow()) {
            if (autoff && _log.shouldInfo())
                _log.info("Slow processor (Arm/Android) detected on this host - not automatically enrolling as floodfill");
            return false;
        }

        if (getContext().getBooleanProperty(UDPTransport.PROP_LAPTOP_MODE)) {
            if (autoff && _log.shouldInfo())
                _log.info("Laptop mode is enabled on this router - not automatically enrolling as floodfill");
            return false;
        }
        // need IPv4 - The setting is the same for both SSU and NTCP, so just take the SSU one
        if (TransportUtil.getIPv6Config(getContext(), "SSU") == TransportUtil.IPv6Config.IPV6_ONLY) {
            if (autoff && _log.shouldInfo())
                _log.info("IPV4 is not active on this router - not automatically enrolling as floodfill");
            return false;
        }
        // need both transports
        Boolean ntcpEnabled = TransportManager.isNTCPEnabled(getContext());
        Boolean udpEnabled = getContext().getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP);
        if (!ntcpEnabled)
            return false;
        if (!udpEnabled)
            return false;
        if (!ntcpEnabled || !udpEnabled) {
            if (autoff && _log.shouldInfo())
                _log.info("Either NTCP or SSU transports are disabled - not automatically enrolling as floodfill");
        }

        if (getContext().commSystem().isInStrictCountry())
            return false;
        String country = getContext().commSystem().getOurCountry();
        // anonymous proxy, satellite provider (not in bad country list)
        if ("a1".equals(country) || "a2".equals(country)) {
            if (autoff && _log.shouldInfo())
                _log.info("Cannot determine location of router - not automatically enrolling as a floodfill");
            return false;
        }

        // Only if up a while...
        if (getContext().router().getUptime() < MIN_UPTIME) {
            if (autoff && _log.shouldInfo())
                _log.info("Not enough uptime (min 2h required) to automatically enroll as a floodfill");
            return false;
        }

        RouterInfo ri = getContext().router().getRouterInfo();
        if (ri == null) {
            if (autoff && _log.shouldInfo())
                _log.info("No routerinfo for this router found - not automatically enrolling as a floodfill");
            return false;
        }

        RouterIdentity ident = ri.getIdentity();
        if (ident.getSigningPublicKey().getType() == SigType.DSA_SHA1) {
            if (autoff && _log.shouldInfo())
                _log.info("Our router is using a DSA SHA1 signature - not automatically enrolling as a floodfill");
            return false;
        }

        char bw = ri.getBandwidthTier().charAt(0);
        // Only if class N, O, P, X
        if (bw != Router.CAPABILITY_BW128 && bw != Router.CAPABILITY_BW256 &&
            bw != Router.CAPABILITY_BW512 && bw != Router.CAPABILITY_BW_UNLIMITED) {
            if (autoff && _log.shouldInfo())
                _log.info("Not enough upstream bandwidth allocated to automatically enroll as a floodfill");
            return false;
        }

        // This list will not include ourselves...
        List<Hash> floodfillPeers = _facade.getFloodfillPeers();
        long now = getContext().clock().now();
        // We know none at all! Must be our turn...
        if (floodfillPeers == null || floodfillPeers.isEmpty()) {
            if (autoff && _log.shouldInfo())
                _log.info("No floodfills in our NetDb so we're enrolling as a floodfill");
            _lastChanged = now;
            return true;
        }

        // Only change status every so often
        boolean wasFF = _facade.floodfillEnabled();
        if (_lastChanged + MIN_CHANGE_DELAY > now)
            return wasFF;

        // This is similar to the qualification we do in FloodOnlySearchJob.runJob().
        // Count the "good" ff peers.
        //
        // Who's not good?
        // the unheard-from, unprofiled, failing, unreachable and banlisted ones.
        // We should hear from floodfills pretty frequently so set a 60m time limit.
        // If unprofiled we haven't talked to them in a long time.
        // We aren't contacting the peer directly, so banlist doesn't strictly matter,
        // but it's a bad sign, and we often banlist a peer before we fail it...
        //
        // Future: use Integration calculation
        //
        int ffcount = floodfillPeers.size();
        int failcount = 0;
        long before = now - 60*60*1000;
        for (Hash peer : floodfillPeers) {
            PeerProfile profile = getContext().profileOrganizer().getProfile(peer);
            if (profile == null || profile.getLastHeardFrom() < before ||
                getContext().banlist().isBanlisted(peer) ||
                getContext().commSystem().wasUnreachable(peer))
                failcount++;
        }

        if (wasFF)
            ffcount++;
        int good = ffcount - failcount;
        boolean happy = getContext().router().getRouterInfo().getCapabilities().indexOf('R') >= 0;
        // TODO - limit may still be too high
        // For reference, the avg lifetime job lag on my Pi is 6.
        // Should we consider avg. dropped ff jobs?
        RateStat lagStat = getContext().statManager().getRate("jobQueue.jobLag");
        if (lagStat != null) {
            Rate rate = lagStat.getRate(60*60*1000L);
            if (rate != null)
                happy = happy && rate.getAvgOrLifetimeAvg() < 25;
        }
        RateStat queueStat = getContext().statManager().getRate("router.tunnelBacklog");
        if (queueStat != null) {
            Rate rate = queueStat.getRate(60*60*1000L);
            if (rate != null)
                happy = happy && rate.getAvgOrLifetimeAvg() < 5;
        }
        // Only if we're pretty well integrated...
//        happy = happy && _facade.getKnownRouters() >= 400;
        happy = happy && _facade.getKnownRouters() >= 2000;
        happy = happy && getContext().commSystem().countActivePeers() >= 50;
//        happy = happy && getContext().tunnelManager().getParticipatingCount() >= 25;
        happy = happy && getContext().tunnelManager().getParticipatingCount() >= 250;
        happy = happy && Math.abs(getContext().clock().getOffset()) < 10*1000;
        // We need an address and no introducers
        if (happy) {
            RouterAddress ra = getContext().router().getRouterInfo().getTargetAddress("SSU");
            if (ra == null)
                happy = false;
            else {
                if (ra.getOption("itag0") != null)
                   happy = false;
            }
        }

        double elG = 0;
        RateStat stat = getContext().statManager().getRate("crypto.elGamal.decrypt");
        if (stat != null) {
            Rate rate = stat.getRate(60*60*1000L);
            if (rate != null) {
                elG = rate.getAvgOrLifetimeAvg();
                happy = happy && elG <= 40.0d;
            }
        }

        if (_log.shouldDebug()) {
            final RouterContext rc = getContext();
            final String log = String.format(
                    "Floodfill criteria breakdown: happy=%b, capabilities=%s, maxLag=%d, known=%d, " +
                    "active=%d, participating=%d, offset=%d, ssuAddr=%s ElG=%f",
                    happy,
                    rc.router().getRouterInfo().getCapabilities(),
                    rc.jobQueue().getMaxLag(),
                    _facade.getKnownRouters(),
                    rc.commSystem().countActivePeers(),
                    rc.tunnelManager().getParticipatingCount(),
                    Math.abs(rc.clock().getOffset()),
                    rc.router().getRouterInfo().getTargetAddress("SSU").toString(),
                    elG
                    );
            _log.debug(log);
        }


        // Too few, and we're reachable, let's volunteer
        if (good < MIN_FF && happy) {
            if (!wasFF) {
                _lastChanged = now;
                _log.logAlways(Log.INFO, "We only have " + good + " good floodfill peers and we want at least " + MIN_FF + ", enrolling as a floodfill...");
            }
            return true;
        }

        // Too many, or we aren't reachable, let's stop
        if (good > MAX_FF || (good > MIN_FF && !happy)) {
            if (wasFF && happy) {
                _lastChanged = now;
                _log.logAlways(Log.INFO, "We already have " + good + " good floodfill peers and we need only " + MIN_FF + " to " + MAX_FF +
                               ", disabling floodfill role...");
            } else if (wasFF && !happy) {
                _log.logAlways(Log.INFO, "We are no longer reachable, disabling floodfill role...");
            }
            return false;
        }

        if (_log.shouldInfo())
            _log.info("We have " + good + " good floodfill peers, not changing our floodfill status -> Enabled? " + wasFF + "; reachable? " + happy);
        return wasFF;
    }

}