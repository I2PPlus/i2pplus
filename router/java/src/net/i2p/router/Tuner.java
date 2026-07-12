package net.i2p.router;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.udp.PeerState;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.transport.udp.SimpleBandwidthEstimator;
import net.i2p.router.transport.udp.EstablishmentManager;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.router.tunnel.pool.BuildHandler;
import net.i2p.router.tunnel.pool.BuildExecutor;
import net.i2p.router.tunnel.pool.BuildRequestor;
import net.i2p.router.tunnel.pool.ParticipatingThrottler;
import net.i2p.router.tunnel.pool.RequestThrottler;
import net.i2p.router.tunnel.pool.TestJob;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.tunnel.pool.TunnelPoolManager;
import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.ntcp.NTCPConnection;
import net.i2p.router.transport.ntcp.Reader;
import net.i2p.router.transport.ntcp.Writer;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.util.SyntheticREDQueue;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.networkdb.kademlia.IterativeSearchJob;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.util.CoDelBlockingQueue;
import net.i2p.router.util.CoDelPriorityBlockingQueue;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * General-purpose adaptive tuner. Observes network and system stats,
 * adjusts tunable parameters to optimize router performance.
 *
 * <p>Runs every 30 seconds via {@link SimpleTimer2}. Each parameter
 * implements an AIMD-style feedback loop bounded within safe ranges:
 * <ol>
 *   <li>Read observed stat (60s rolling average)</li>
 *   <li>Compute target value from observed stat + cross-references</li>
 *   <li>Clamp target to [min, max]</li>
 *   <li>Apply health-based dampening when system is degraded</li>
 *   <li>Accelerate rollback when param diverges from default</li>
 *   <li>Call applyValue() to push the new value to the router</li>
 * </ol>
 *
 * <p>New parameters are added by extending {@link BaseParam} and
 * implementing:
 * <ul>
 *   <li>{@code applyValue(int)} — push value to router subsystem</li>
 *   <li>{@code getRuntimeValue()} — read current value from subsystem</li>
 *   <li>{@code getObservedStat(RouterContext)} — read primary signal</li>
 *   <li>{@code computeTarget(double)} — feedback logic with cross-refs</li>
 * </ul>
 *
 * <p>Params can be tuned live via the console UI or persisted to
 * {@code autotune.config} (separate from {@code router.config}).
 * Auto-revert to factory defaults triggers when system health drops
 * below 0.3.
 *
 * @since 0.9.70+
 */
public class Tuner extends SimpleTimer2.TimedEvent {

    private final RouterContext _context;
    private final Log _log;
    private final List<TunableParam> _params;
    private final AutotuneConfig _autotune;

    static final long STAT_PERIOD = 60 * 1000L;
    /** Max history samples for sparklines (30 samples @ 30s = 15min) */
    static final int MAX_HISTORY = 30;

    /** I2CP internal queue size — static so ClientManager can read it without circular dep */
    private static volatile int _internalQueueSize = SystemVersion.isSlow() ? 256 : 512;

    /** Max time (ms) a message may sit in the outbound dispatch queue before being dropped */
    private static volatile int _maxDispatchAgeMs = 3000;

    /** Priority for I/O handler threads — boosted under load, reduced when idle */
    private static volatile int _handlerThreadPriority = Thread.NORM_PRIORITY;

    /**
     * @return the target priority for I/O handler threads
     * @since 0.9.70+
     */
    public static int getHandlerThreadPriority() { return _handlerThreadPriority; }

    /**
     * Adjust the current thread's priority to the target handler priority.
     * Call this from handler thread main loops — lightweight volatile read.
     * Critical threads (watchdog, event pumper, job queue) should NOT call this.
     *
     * @since 0.9.70+
     */
    public static void adjustHandlerPriority() {
        int target = _handlerThreadPriority;
        Thread t = Thread.currentThread();
        if (t.getPriority() != target) {
            try { t.setPriority(target); }
            catch (SecurityException se) { /* ignore */ }
        }
    }

    /**
     * @return the max dispatch age in ms
     * @since 0.9.70+
     */
    public static int getMaxDispatchAgeMs() { return _maxDispatchAgeMs; }

    /**
     * @return the current I2CP internal queue size
     * @since 0.9.70+
     */
    public static int getInternalQueueSize() { return _internalQueueSize; }

    /**
     * Set the I2CP internal queue size (called by Tuner).
     * @since 0.9.70+
     */
    public static void setInternalQueueSize(int size) {
        int def = SystemVersion.isSlow() ? 256 : 512;
        _internalQueueSize = Math.max(def / 2, Math.min(def * 4, size));
    }

    /** Subsystem labels for grouping in the UI */
    public static final String SUB_TRANSPORT = "Transport";
    public static final String SUB_TUNNEL = "Tunnel";
    public static final String SUB_STREAMING = "Streaming";
    public static final String SUB_I2CP = "I2CP";
    public static final String SUB_CONGESTION = "Congestion";
    public static final String SUB_CRYPTO = "Crypto";
    public static final String SUB_ROUTER = "Router";
    public static final String SUB_NETDB = "NetDB";
    public static final String SUB_PEER = "Peers";

    // System capability factors for scaling defaults
    private static final long MAX_MEMORY = SystemVersion.getMaxMemory();
    private static final int CORES = SystemVersion.getCores();
    private static final boolean IS_SLOW = SystemVersion.isSlow();
    private static final int MEM_FACTOR = Math.max(1, (int)(MAX_MEMORY / (256L * 1024 * 1024)));
    private static final int CORE_FACTOR = Math.max(1, CORES);
    /** ML-KEM precalc min/max — each pair ~3.5KB, scale with cores and memory */
    private static final int MLKEM_FACTOR = Math.max(MEM_FACTOR, CORE_FACTOR);
    private static final int MLKEM_PRECALC_MIN = Math.max(512, 8 * MLKEM_FACTOR);
    private static final int MLKEM_PRECALC_MAX = Math.max(4096, 64 * MLKEM_FACTOR);
    /** X25519/EDH precalc — each pair ~64 bytes, scale with cores and memory */
    private static final int XDH_FACTOR = Math.max(MEM_FACTOR, CORE_FACTOR);
    private static final int XDH_PRECALC_MIN = Math.max(256, 128 * XDH_FACTOR);
    private static final int XDH_PRECALC_MAX = Math.max(32768, 2048 * XDH_FACTOR);
    private static final int EDH_PRECALC_MIN = Math.max(128, 64 * XDH_FACTOR);
    private static final int EDH_PRECALC_MAX = Math.max(16384, 1024 * XDH_FACTOR);

    /**
     * Compute a system-scaled value: base * factor, bounded by min and max.
     * Factor is max(memFactor, coreFactor), halved for slow systems.
     */
    static int scaleForSystem(int base, int hardMin, int hardMax) {
        int factor = Math.max(MEM_FACTOR, CORE_FACTOR);
        if (IS_SLOW) factor = Math.max(1, factor / 2);
        return Math.max(hardMin, Math.min(hardMax, base * factor));
    }

    /**
     * Returns current heap memory pressure as a ratio from 0.0 (no pressure)
     * to 1.0 (heap full). Used to scale cache sizes and buffer pools.
     *
     * @since 0.9.70+
     */
    static double getMemoryPressure() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        if (max <= 0) return 0.0;
        long used = rt.totalMemory() - rt.freeMemory();
        return Math.min(1.0, Math.max(0.0, (double) used / max));
    }

    /**
     * Manages the autotune.config file — separate from router.config.
     *
     * <p>Stores tuner metadata (value, default, min, max, step) for each
     * param. Actual config props that the router reads still go to
     * router.config via each param's {@code applyValue()}.
     *
     * <p>Writes are throttled: values are marked dirty and flushed to
     * disk at most once per 5 minutes ({@link #save()}). Use
     * {@link #forceSave()} for unconditional writes (e.g., shutdown).
     *
     * @since 0.9.70+
     */
    public static class AutotuneConfig {
        private static final String FILENAME = "autotune.config";
        private static final long SAVE_INTERVAL_MS = 5 * 60 * 1000L;
        private final File _file;
        private final Properties _props;
        private final Log _log;
        private volatile boolean _dirty;
        private volatile long _lastSaveMs;

        public AutotuneConfig(RouterContext ctx) {
            _file = new File(ctx.getRouterDir(), FILENAME);
            _props = new Properties();
            _log = ctx.logManager().getLog(Tuner.class);
            load();
        }

        private void load() {
            if (!_file.exists()) return;
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(_file);
                _props.load(fis);
            } catch (IOException ioe) {
                _log.warn("Error loading " + _file, ioe);
            } finally {
                if (fis != null) { try { fis.close(); } catch (IOException ig) {} }
            }
        }

        /** Write to disk only if dirty and ≥5min since last write. */
        public void save() {
            if (!_dirty) return;
            long now = System.currentTimeMillis();
            if (now - _lastSaveMs < SAVE_INTERVAL_MS) return;
            write();
        }

        /** Unconditional write — used on shutdown and form saves. */
        public void forceSave() {
            if (!_dirty) return;
            write();
        }

        private void write() {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(_file);
                _props.store(fos, "Auto-tuner persisted state — do not edit manually");
                _dirty = false;
                _lastSaveMs = System.currentTimeMillis();
            } catch (IOException ioe) {
                _log.warn("Error saving " + _file, ioe);
            } finally {
                if (fos != null) { try { fos.close(); } catch (IOException ig) {} }
            }
        }

        String getProperty(String key) {
            return _props.getProperty(key);
        }

        int getInt(String key, int defaultVal) {
            String val = _props.getProperty(key);
            if (val != null) {
                try { return Integer.parseInt(val); }
                catch (NumberFormatException nfe) {}
            }
            return defaultVal;
        }

        public void setProperty(String key, String value) {
            _props.setProperty(key, value);
            _dirty = true;
        }

        File getFile() { return _file; }
    }

    public Tuner(RouterContext ctx) {
        super(ctx.simpleTimer2());
        _context = ctx;
        _log = ctx.logManager().getLog(Tuner.class);
        _autotune = new AutotuneConfig(ctx);
        BaseParam._sharedAutotune = _autotune;
        _maxDispatchAgeMs = ctx.getProperty("i2p.router.maxDispatchAge", 3000);
        _handlerThreadPriority = ctx.getProperty("i2p.router.handlerThreadPriority", Thread.NORM_PRIORITY);
        _params = new ArrayList<TunableParam>(72);

        // Transport — NTCP/UDP/SSU
        _params.add(new AckFrequencyParam());
        _params.add(new DataMessageTimeoutParam());
        _params.add(new HandlerThreadPriorityParam());
        _params.add(new IbEstablishTimeParam());
        _params.add(new MaxConcurrentEstablishParam());
        _params.add(new MaxConcurrentMessagesParam());
        _params.add(new InitConcurrentMsgsParam());
        _params.add(new MinConcurrentMsgsParam());
        _params.add(new InitRTOParam());
        _params.add(new MinRTOParam());
        _params.add(new UdpMaxRtoParam());
        _params.add(new MaxSendWindowParam());
        _params.add(new MaxDispatchAgeParam());
        _params.add(new MaxQueuedOutboundParam());
        _params.add(new MaxWriteBufsParam());
        _params.add(new NtcpFailsafeFreqParam());
        _params.add(new NTCPQueueCapacityParam());
        _params.add(new NtcpReaderThreadsParam());
        _params.add(new NtcpSendFinisherThreadsParam());
        _params.add(new NtcpWriterThreadsParam());
        _params.add(new NtcpEstablishTimeParam());
        _params.add(new ObEstablishTimeParam());
        _params.add(new RdnsPoolSizeParam());
        _params.add(new SendPoolCapacityParam());
        _params.add(new UDPHandlerThreadsParam());
        _params.add(new UDPMessageReceiverThreadsParam());
        _params.add(new SentMessagesCleanTimeParam());
        _params.add(new OutboundMsgExpirationParam());

        // Tunnel
        _params.add(new BuildFirstHopTimeoutParam());
        _params.add(new BuildHandlerMaxQueueParam());
        _params.add(new BuildRequestTimeoutParam());
        _params.add(new IbMsgsPerPumpParam());
        _params.add(new MaxConcurrentBuildsParam());
        _params.add(new LookupLimitParam());
        _params.add(new MaxParticipatingTunnelsParam());
        _params.add(new ObMsgsPerPumpParam());
        _params.add(new PerTunnelBweDivisorParam());
        _params.add(new PumperQueueCapacityParam());
        _params.add(new PumperThreadsParam());
        _params.add(new ReplenishFrequencyParam());
        _params.add(new RequeueTimeParam());
        _params.add(new SelectorLoopDelayParam());
        _params.add(new TestJobMaxDelayParam());
        _params.add(new TestJobMaxQueuedParam());
        _params.add(new TestJobMinDelayParam());
        _params.add(new TunnelGrowthFactorParam());
        _params.add(new I2PTunnelServerHandlerThreadsParam());
        _params.add(new I2PTunnelClientRunnerMaxParam());
        _params.add(new BuildHandlerThreadsParam());

        // Throttling
        _params.add(new ParticipatingThrottleMinParam());
        _params.add(new ParticipatingThrottleMaxParam());
        _params.add(new ParticipatingThrottlePctParam());
        _params.add(new RequestThrottleMinParam());
        _params.add(new RequestThrottleMaxParam());
        _params.add(new RequestThrottlePctParam());
        _params.add(new RequestThrottleBurstParam());
        // Sustained load thresholds
        _params.add(new RequestHighLoadLagParam());
        _params.add(new RequestHighLoadCpuParam());
        _params.add(new RequestModerateLoadLagParam());
        _params.add(new RequestModerateLoadCpuParam());
        _params.add(new RequestSustainedHighLoadParam());
        _params.add(new RequestSustainedModerateLoadParam());
        // Pool backoff
        _params.add(new PoolFailureThresholdParam());
        _params.add(new PoolBackoffMsParam());
        _params.add(new TunnelTargetBufferParam());

        // Streaming
        _params.add(new CongestionAvoidanceGrowthParam());
        _params.add(new ImmediateAckDelayParam());
        _params.add(new InitialAckDelayParam());
        _params.add(new InitialResendDelayParam());
        _params.add(new InitialRTOParam());
        _params.add(new InitialWindowSizeParam());
        _params.add(new MaxRetransmissionsParam());
        _params.add(new MaxResendDelayParam());
        _params.add(new MaxRTOParam());
        _params.add(new MaxRttParam());
        _params.add(new MaxSlowStartWindowParam());
        _params.add(new MinResendDelayParam());
        _params.add(new PassiveFlushDelayParam());
        _params.add(new SlowStartGrowthParam());
        _params.add(new InactivityTimeoutParam());

        // I2CP
        _params.add(new InternalQueueSizeParam());
        _params.add(new WriterQueueSizeParam());

        // Congestion — CoDel + Westwood + RED
        _params.add(new CodelIntervalParam());
        _params.add(new CodelTargetParam());
        _params.add(new REDMaxDropProbParam());
        _params.add(new REDMaxThresholdParam());
        _params.add(new REDMinThresholdParam());
        _params.add(new WestwoodDecayFactorParam());

        // Crypto — precalc pools
        _params.add(new EDHPreCalcMinParam());
        _params.add(new MLKEMPreCalcMinParam());
        _params.add(new XDHPreCalcMinParam());

        // Router
        _params.add(new GoodDeficitThrottleParam());
        _params.add(new PeerOutboundQueueParam());
        _params.add(new ThrottleRejectExponentParam());
        _params.add(new TransitThrottleFactorParam());

        // NetDB
        _params.add(new NetDBMaxConcurrentParam());
        _params.add(new NetDBSearchLimitParam());
        _params.add(new NetDBSingleSearchTimeParam());

        // Peers
        _params.add(new MaxFastPeersParam());
        _params.add(new MaxHighCapPeersParam());
        _params.add(new MaxProfilesParam());
        _params.add(new MinFastPeersParam());
        _params.add(new MinHighCapPeersParam());

        // Purge stale tuner.* keys from router.config (one-time cleanup)
        purgeOldTunerProps(ctx);
    }

    /**
     * Remove old tuner.* properties from router.config that were written there
     * before autotune.config was introduced. Only purges keys we know about
     * (tuner.PARAM.value/default/min/max/step), leaves user-configured props alone.
     */
    private void purgeOldTunerProps(RouterContext ctx) {
        String markerKey = "tuner._migrated_to_autotune";
        if (ctx.getProperty(markerKey) != null)
            return;
        List<String> toRemove = new ArrayList<String>();
        for (String key : ctx.getPropertyNames()) {
            if (key.startsWith(PROP_PREFIX) && !key.equals(markerKey)) {
                toRemove.add(key);
            }
        }
        if (!toRemove.isEmpty()) {
            ctx.router().saveConfig(null, toRemove);
            if (_log.shouldInfo())
                _log.info("Purged " + toRemove.size() + " stale tuner.* keys from router.config");
        }
        ctx.router().saveConfig(markerKey, "true");
    }

    public void timeReached() {
        schedule(30*1000L);
        // Compute system health once per cycle, shared by all params
        SystemHealth health = new SystemHealth(_context);
        _lastHealthScore = health.getScore();
        _lastSubsystemScores = health.computeSubsystemScores();
        for (TunableParam param : _params) {
            try {
                if (param instanceof BaseParam) {
                    BaseParam bp = (BaseParam) param;
                    bp.refreshRanges(_context);
                    bp.refreshDefault(_context);
                    bp.setHealth(health);
                }
                param.update();
            } catch (Exception e) {
                if (_log.shouldWarn())
                    _log.warn("Tuner: error updating " + param.getName(), e);
            }
        }
        // Refresh XDH pool sizes based on current system conditions
        X25519KeyFactory xdh = X25519KeyFactory.getInstance();
        if (xdh != null) {
            try { xdh.refreshPoolSize(); }
            catch (Exception e) {
                if (_log.shouldWarn())
                    _log.warn("Tuner: error refreshing XDH pool", e);
            }
        }
        // Refresh EDH pool sizes based on current system conditions
        net.i2p.router.crypto.ratchet.Elg2KeyFactory edh =
            net.i2p.router.crypto.ratchet.Elg2KeyFactory.getInstance();
        if (edh != null) {
            try { edh.refreshPoolSize(); }
            catch (Exception e) {
                if (_log.shouldWarn())
                    _log.warn("Tuner: error refreshing EDH pool", e);
            }
        }
        // Refresh MLKEM pool sizes based on current system conditions
        net.i2p.router.crypto.pqc.MLKEMKeyFactory mlkem =
            net.i2p.router.crypto.pqc.MLKEMKeyFactory.getInstance();
        if (mlkem != null) {
            try { mlkem.refreshPoolSize(); }
            catch (Exception e) {
                if (_log.shouldWarn())
                    _log.warn("Tuner: error refreshing MLKEM pool", e);
            }
        }
        // Flush dirty state to disk (at most once per 5min, per AutotuneConfig throttle)
        _autotune.save();
    }

    /** Flush on shutdown — unconditional write if dirty */
    public void shutdown() {
        _autotune.forceSave();
    }

    public List<ParamSnapshot> getSnapshots() {
        List<ParamSnapshot> snaps = new ArrayList<ParamSnapshot>(_params.size());
        for (TunableParam p : _params) {
            snaps.add(p.snapshot());
        }
        return snaps;
    }

    /**
     * Get the latest system health score (0.0 = worst, 1.0 = perfect).
     * Returns NaN if health hasn't been computed yet.
     */
    public double getHealthScore() {
        return _lastHealthScore;
    }

    private volatile double _lastHealthScore = Double.NaN;
    private volatile Map<String, double[]> _lastSubsystemScores = Collections.emptyMap();

    /**
     * Per-subsystem health scores. Each entry: {score, ...detail values}.
     * Score is 0.0-1.0. Updated once per tuning cycle alongside the overall health score.
     *
     * @since 0.9.70+
     */
    public static class SubsystemScore {
        public final String name;
        public final String label;
        public double score;
        public final String[] details;

        SubsystemScore(String name, String label, double score, String[] details) {
            this.name = name;
            this.label = label;
            this.score = Math.max(0.0, Math.min(1.0, score));
            this.details = details != null ? details : new String[0];
        }
    }

    /**
     * Get per-subsystem health scores computed during the last tuning cycle.
     * Returns a list of SubsystemScore sorted by the SUBSYSTEM_ORDER used on the tuning page.
     *
     * @since 0.9.70+
     */
    public List<SubsystemScore> getSubsystemScores() {
        Map<String, double[]> scores = _lastSubsystemScores;
        List<SubsystemScore> result = new ArrayList<SubsystemScore>();
        String[] order = {
            SUB_ROUTER, SUB_TUNNEL, SUB_TRANSPORT, SUB_NETDB,
            SUB_STREAMING, SUB_I2CP, SUB_PEER, SUB_CONGESTION, SUB_CRYPTO
        };
        String[] labels = {
            "Router", "Tunnel", "Transport", "NetDB",
            "Streaming", "I2CP", "Peers", "Congestion", "Crypto"
        };
        String[][] metrics = {
            { "jobQueue.jobLag" },
            { "tunnel.buildSuccessRate + pool alive/deficit" },
            { "transport.sendProcessingTime", "transport.sendMessageFailureLifetime / sendMessageSize" },
            { "client.leaseSetFoundRemoteTime / netDb.successTime" },
            { "stream.resetReceived / stream.connectionReceived" },
            { "i2cp.internalQueueSize" },
            { "peer.activeProfileCount" },
            { "udp.congestionOccurred / udp.allowConcurrentActive" },
            { "crypto.EDHEmpty/Used, XDHEmpty/Used, MLKEMEmpty/Used" }
        };
        for (int i = 0; i < order.length; i++) {
            double[] vals = scores.get(order[i]);
            double score = (vals != null && vals.length > 0) ? vals[0] : Double.NaN;
            result.add(new SubsystemScore(order[i], labels[i], score, metrics[i]));
        }
        return result;
    }

    /**
     * Get the shared AutotuneConfig instance.
     * Form handlers should use this (not new AutotuneConfig()) so writes
     * go to the same in-memory instance the Tuner reads from.
     *
     * @since 0.9.70+
     */
    public AutotuneConfig getAutotune() {
        return _autotune;
    }

    public TunableParam getParam(String name) {
        for (TunableParam p : _params) {
            if (p.getName().equals(name))
                return p;
        }
        return null;
    }

    public void setOverride(String name, int value) {
        TunableParam p = getParam(name);
        if (p != null)
            p.setOverride(value);
    }

    /** Restore all params to their factory defaults and clear persisted values */
    public void restoreDefaults() {
        for (TunableParam param : _params) {
            if (param instanceof BaseParam) {
                BaseParam bp = (BaseParam) param;
                bp._autotune.setProperty(bp._name + ".value", String.valueOf(bp._defaultValue));
                bp.applyValue(bp._defaultValue);
                bp._autoTuning = true;
                bp._override = -1;
            }
        }
        _autotune.forceSave();
    }

    public interface TunableParam {
        String getName();
        String getDescription();
        String getSubsystem();
        int getCurrentValue();
        int getMin();
        int getMax();
        int getStep();
        boolean isAutoTuning();
        void update();
        void setOverride(int value);
        ParamSnapshot snapshot();
        /** @return recent value history for sparkline (newest last) */
        int[] getValueHistory();
        /** @return recent observed stat history for sparkline (newest last) */
        double[] getStatHistory();
    }

    public static class ParamSnapshot {
        public final String name;
        public final String description;
        public final String subsystem;
        public final int currentValue;
        public final int defaultValue;
        public final int min;
        public final int max;
        public final int step;
        public final boolean autoTuning;
        public final String observedStat;
        public final double observedStatValue;
        public final int[] valueHistory;
        public final double[] statHistory;

        public ParamSnapshot(String name, String description, String subsystem, int currentValue,
                             int defaultValue, int min, int max, int step, boolean autoTuning,
                             String observedStat, double observedStatValue,
                             int[] valueHistory, double[] statHistory) {
            this.name = name;
            this.description = description;
            this.subsystem = subsystem;
            this.currentValue = currentValue;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.step = step;
            this.autoTuning = autoTuning;
            this.observedStat = observedStat;
            this.observedStatValue = observedStatValue;
            this.valueHistory = valueHistory;
            this.statHistory = statHistory;
        }
    }

    /**
     * Property prefix for tuner ranges.
     * Format: tuner.{paramName}.min, tuner.{paramName}.max, tuner.{paramName}.step
     */
    static final String PROP_PREFIX = "tuner.";

    /**
     * Base class for tunable params with history tracking and auto-revert.
     *
     * <p>Each param wraps a single router configuration value. The tuning
     * loop reads an observed stat, computes a target, and applies it:
     * <pre>
     *   observed = getObservedStat()
     *   target   = computeTarget(observed)
     *   target   = clamp(target, min, max)
     *   target   = dampenByHealth(target, current)
     *   target   = accelerateRollback(target, current, defaultValue)
     *   applyValue(target)
     * </pre>
     *
     * <p>Min/max/step ranges are stored in {@code autotune.config} and
     * can be changed at runtime via the console UI (no restart needed).
     * Factory defaults are captured on first run and persisted for
     * auto-revert when system health is degraded.
     *
     * <p>Thread safety: all fields are accessed only from the Tuner
     * timer thread, except {@code _override} and {@code _autoTuning}
     * which are set from the console form handler.
     *
     * @since 0.9.70+
     */
    abstract static class BaseParam implements TunableParam {
        /** Shared instance — set once in Tuner constructor, used by all BaseParams */
        static volatile AutotuneConfig _sharedAutotune;
        protected final String _name;
        protected final String _description;
        protected final String _subsystem;
        protected final String _propPrefix;
        protected final int _defaultMin;
        protected final int _defaultMax;
        protected final int _defaultStep;
        protected int _min;
        protected int _max;
        protected int _step;
        protected final String _statName;
        /** Factory default: value before any tuning. Persisted on first run for auto-revert. */
        protected int _defaultValue;
        /** Last known value (from persistence or runtime default). Used as tuning baseline. */
        protected final int _initialValue;
        /** True until first update() call — applies persisted value from autotune.config. */
        protected boolean _firstTick = true;
        protected volatile int _override;
        protected volatile boolean _autoTuning;
        protected SystemHealth _health;
        protected final int[] _valueHistory;
        protected final double[] _statHistory;
        protected int _historyCount;
        protected final Log _log;
        protected final RouterContext _ctx;
        protected final AutotuneConfig _autotune;

        protected BaseParam(String name, String description, String subsystem,
                            int defaultMin, int defaultMax,
                            int defaultStep, String statName, RouterContext ctx) {
            this(name, description, subsystem, defaultMin, defaultMax, defaultStep, statName, ctx, null);
        }

        protected BaseParam(String name, String description, String subsystem,
                            int defaultMin, int defaultMax,
                            int defaultStep, String statName, RouterContext ctx,
                            AutotuneConfig autotune) {
            _name = name;
            _description = description;
            _subsystem = subsystem;
            _propPrefix = PROP_PREFIX + name + ".";
            _statName = statName;
            _defaultMin = defaultMin;
            _defaultMax = defaultMax;
            _defaultStep = defaultStep;
            _min = defaultMin;
            _max = defaultMax;
            _step = defaultStep;
            _log = ctx.logManager().getLog(Tuner.class);
            _ctx = ctx;
            _autotune = (autotune != null) ? autotune : _sharedAutotune;
            // Capture factory default on first run, persist to autotune.config
            int runtimeDefault = getRuntimeValue();
            String defaultKey = name + ".default";
            String valueKey = name + ".value";
            String existingDefault = _autotune.getProperty(defaultKey);
            boolean changed = false;
            if (existingDefault == null) {
                _defaultValue = runtimeDefault;
                _autotune.setProperty(defaultKey, String.valueOf(runtimeDefault));
                _autotune.setProperty(valueKey, String.valueOf(runtimeDefault));
                changed = true;
            } else {
                _defaultValue = Integer.parseInt(existingDefault);
                // 1. Clamp to [min, max] — catches out-of-range (e.g. -1)
                if (_defaultValue < _min || _defaultValue > _max) {
                    int prev = _defaultValue;
                    _defaultValue = Math.max(_min, Math.min(_max, _defaultValue));
                    if (_log.shouldWarn())
                        _log.warn(_name + " default clamped: " + prev + " -> " + _defaultValue +
                                  " (range " + _min + "-" + _max + ")");
                }
                // 2. Heal when persisted value matches factory default but default doesn't
                //    Catches in-range corruptions (e.g. 2600 for MinResendDelay).
                if (runtimeDefault > 0 && _defaultValue != runtimeDefault) {
                    int persistedValue = _autotune.getInt(valueKey, -1);
                    if (persistedValue == runtimeDefault) {
                        int prev = _defaultValue;
                        _defaultValue = runtimeDefault;
                        if (_log.shouldWarn())
                            _log.warn(_name + " default healed: " + prev + " -> " + _defaultValue +
                                      " (factory default: " + runtimeDefault +
                                      ", persisted value: " + persistedValue + ")");
                    }
                }
                if (_defaultValue != Integer.parseInt(existingDefault)) {
                    _autotune.setProperty(defaultKey, String.valueOf(_defaultValue));
                    changed = true;
                }
            }
            if (changed)
                _autotune.forceSave();
            // Read persisted tuned value (clamped to current range) — catches stale
            // autotune.config values from before code changes (e.g., max lowered 512→20)
            int raw = _autotune.getInt(valueKey, _defaultValue);
            _initialValue = Math.max(_min, Math.min(_max, raw));
            _override = -1;
            _autoTuning = true;
            _valueHistory = new int[MAX_HISTORY];
            _statHistory = new double[MAX_HISTORY];
            _historyCount = 0;
        }

        public String getName() { return _name; }
        public String getDescription() { return _description; }
        public String getSubsystem() { return _subsystem; }
        public int getMin() { return _min; }
        public int getMax() { return _max; }
        public int getStep() { return _step; }
        public int getDefaultValue() { return _defaultValue; }
        public boolean isAutoTuning() { return _autoTuning; }

        /**
         * Get the current share bandwidth in bytes per second.
         * Useful for params whose ranges should scale with bandwidth.
         *
         * @since 0.9.70+
         */
        protected static int getShareBps(RouterContext ctx) {
            return 1000 * TunnelDispatcher.getShareBandwidth(ctx);
        }

        /**
         * Effective default min — override for bandwidth-scaled params.
         * Called by refreshRanges() instead of _defaultMin.
         *
         * @since 0.9.70+
         */
        protected int getDefaultMin(RouterContext ctx) { return _defaultMin; }

        /**
         * Effective default max — override for bandwidth-scaled params.
         * Called by refreshRanges() instead of _defaultMax.
         *
         * @since 0.9.70+
         */
        protected int getDefaultMax(RouterContext ctx) { return _defaultMax; }

        /**
         * Transit bandwidth threshold for "heavy" — 80% of configured share bandwidth.
         * Replaces the old hardcoded 50 KB/s which was far too low for any real router.
         * @since 0.9.70+
         */
        protected static int getHeavyTransitThreshold(RouterContext ctx) {
            return getShareBps(ctx) * 4 / 5;
        }

        /**
         * Transit bandwidth threshold for "sustained heavy" — 50% of configured share.
         * @since 0.9.70+
         */
        protected static int getSustainedHeavyTransitThreshold(RouterContext ctx) {
            return getShareBps(ctx) / 2;
        }

        /**
         * Effective default step — override for bandwidth-scaled params.
         * Called by refreshRanges() instead of _defaultStep.
         *
         * @since 0.9.70+
         */
        protected int getDefaultStep(RouterContext ctx) { return _defaultStep; }

        /**
         * Re-read min/max/step from autotune.config — live update, no restart.
         *
         * <p>Called once per tuning cycle before computeTarget(). Clamps
         * loaded ranges to constructor limits to prevent stale config
         * values from exceeding actual setter limits. Also clamps the
         * persisted value to the new range if it was set before a code
         * change lowered the max.
         *
         * @since 0.9.70+
         */
        public void refreshRanges(RouterContext ctx) {
            String name = _name;
            int floor = getDefaultMin(ctx);
            int ceil = getDefaultMax(ctx);
            int step = getDefaultStep(ctx);
            _min = _autotune.getInt(name + ".min", floor);
            _max = _autotune.getInt(name + ".max", ceil);
            _step = _autotune.getInt(name + ".step", step);
            // Clamp loaded ranges to effective defaults (enforce setter constraints)
            // Prevents stale autotune.config values from exceeding actual setter limits
            _min = Math.max(floor, _min);
            _max = Math.min(ceil, _max);
            if (_min > _max) {
                _min = floor;
                _max = ceil;
            }
            // Clamp persisted value to new range — prevents stale autotune.config
            // values from exceeding caps after code changes (e.g., max lowered)
            int val = _autotune.getInt(_name + ".value", _defaultValue);
            if (val < _min || val > _max) {
                int clamped = Math.max(_min, Math.min(_max, val));
                if (_log.shouldInfo())
                    _log.info(_name + " persisted value " + val + " clamped to " + clamped +
                              " (range " + _min + "-" + _max + ")");
                _autotune.setProperty(_name + ".value", String.valueOf(clamped));
            }
        }

        /**
         * Re-read the default value from autotune.config — live update after form save.
         *
         * <p>Called once per tuning cycle. When a user saves a new default
         * via the console form, this method picks it up and uses it as the
         * auto-revert target.
         *
         * @since 0.9.70+
         */
        public void refreshDefault(RouterContext ctx) {
            int newDefault = _autotune.getInt(_name + ".default", _defaultValue);
            if (newDefault != _defaultValue) {
                if (_log.shouldInfo())
                    _log.info(_name + " default changed from " + _defaultValue + " to " + newDefault);
                _defaultValue = newDefault;
            }
        }

        /**
         * Persist a tuned value to autotune.config.
         *
         * <p>Writes are throttled: the value is marked dirty and flushed
         * to disk at most once per 5 minutes by {@link AutotuneConfig#save()}.
         * This avoids excessive I/O from params that change every cycle.
         *
         * @param ctx   the router context
         * @param value the new value to persist
         * @since 0.9.70+
         */
        protected void persistValue(RouterContext ctx, int value) {
            _autotune.setProperty(_name + ".value", String.valueOf(value));
        }

        /**
         * Set a manual override value. Disables auto-tuning until cleared.
         *
         * <p>Called from the console form handler when a user manually
         * sets a param value. Setting value &lt; 0 re-enables auto-tuning.
         * The override is applied immediately via {@link #applyValue} and
         * persisted to autotune.config.
         *
         * @param value the override value, or &lt; 0 to re-enable auto-tuning
         * @since 0.9.70+
         */
        public void setOverride(int value) {
            _override = value;
            _autoTuning = (value < 0);
            if (value >= 0) {
                int prev = getRuntimeValue();
                if (_log.shouldInfo())
                    _log.info(_name + " override set from " + prev + " to " + value + " (default: " + _defaultValue + ")");
                applyValue(value);
                persistValue(_ctx, value);
            }
        }

        /** Set the current system health score for this update cycle. */
        void setHealth(SystemHealth health) { _health = health; }

        public int[] getValueHistory() { return _valueHistory; }
        public double[] getStatHistory() { return _statHistory; }

        public ParamSnapshot snapshot() {
            int[] vh = new int[_historyCount];
            double[] sh = new double[_historyCount];
            System.arraycopy(_valueHistory, 0, vh, 0, _historyCount);
            System.arraycopy(_statHistory, 0, sh, 0, _historyCount);
            double obsVal = Double.NaN;
            try {
                obsVal = getObservedStat(null);
            } catch (Exception e) {
                if (_log.shouldDebug()) _log.debug(_name + " snapshot stat unavailable", e);
            }
            return new ParamSnapshot(_name, _description, _subsystem, getCurrentValue(),
                                     _defaultValue, _min, _max, _step, _autoTuning,
                                     _statName, obsVal, vh, sh);
        }

        protected abstract void applyValue(int value);
        protected abstract int getRuntimeValue();
        protected abstract double getObservedStat(RouterContext ctx);
        protected abstract int computeTarget(double observed);

        /**
         * Fetch an additional stat value for cross-reference decisions.
         *
         * <p>Cross-reference stats are used alongside the primary stat to
         * make more informed tuning decisions. For example, a streaming
         * param might use {@code transport.sendProcessingTime} as its
         * primary signal but also check {@code jobQueue.jobLag} to avoid
         * increasing load when the CPU is already saturated.
         *
         * @param ctx      the router context
         * @param statName the stat to query (e.g., "jobQueue.jobLag")
         * @return the 60s rolling average, or NaN if not available
         * @since 0.9.70+
         */
        protected double getAdditionalStat(RouterContext ctx, String statName) {
            RateStat rs = ctx.statManager().getRate(statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        /**
         * Fetch an additional stat value using the 1-hour average.
         *
         * <p>Useful for less time-sensitive decisions where we want to
         * confirm a trend rather than react to a short-term spike.
         * For example, build success rate should be checked over an
         * hour to avoid over-reacting to a brief network glitch.
         *
         * @param ctx      the router context
         * @param statName the stat to query
         * @return the 1-hour rolling average, or NaN if not available
         * @since 0.9.70+
         */
        protected double getAdditionalStatHourly(RouterContext ctx, String statName) {
            RateStat rs = ctx.statManager().getRate(statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(RateConstants.ONE_HOUR);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        /**
         * Fetch the event count for an additional stat using the 60s period.
         *
         * <p>For event-count stats where {@code addRateData(name, 1)} is called,
         * {@link #getAdditionalStat} returns {@code getAverageValue()} which is
         * always 1.0 when events exist. This method returns the raw event count
         * instead, which is the correct metric for "how many events occurred".
         *
         * @param ctx      the router context
         * @param statName the stat to query
         * @return the event count in the last 60s, or NaN if not available
         * @since 0.9.70+
         */
        protected double getAdditionalEventCount(RouterContext ctx, String statName) {
            RateStat rs = ctx.statManager().getRate(statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getLastEventCount();
        }

        /**
         * Fetch the event count for an additional stat using the 1-hour period.
         *
         * @param ctx      the router context
         * @param statName the stat to query
         * @return the event count in the last hour, or NaN if not available
         * @since 0.9.70+
         */
        protected double getAdditionalEventCountHourly(RouterContext ctx, String statName) {
            RateStat rs = ctx.statManager().getRate(statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(RateConstants.ONE_HOUR);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getLastEventCount();
        }

        /**
         * Compute actual tunnel build success rate from event counts.
         *
         * <p>The build success/failure/timeout stats are event-count markers
         * ({@code addRateData(name, 100, 0)}), so {@link #getAdditionalStat}
         * <p>BuildExecutor emits these stats as 0-100 integer percentages with
         * {@code addRateData(name, rate, 0)}. The {@code eventDuration=0} parameter is
         * <em>not</em> an event count delta — event count always increments by 1 per call.
         * {@link #getAdditionalStat} returns {@code getAverageValue()} = average of emitted
         * rates, which is the correct success percentage. Do NOT use
         * {@link #getAdditionalEventCount} here — it returns the call count (always 1.0
         * when all three stats are emitted together), not the rate value.
         *
         * @param ctx the router context
         * @return success rate as 0.0–1.0, or NaN if no build events
         * @since 0.9.70+
         */
        protected double getBuildSuccessRate(RouterContext ctx) {
            double success = getAdditionalStat(ctx, "tunnel.buildSuccessRate");
            double failure = getAdditionalStat(ctx, "tunnel.buildFailureRate");
            double timeout = getAdditionalStat(ctx, "tunnel.buildTimeoutRate");
            double total = success + failure + timeout;
            if (Double.isNaN(total) || total <= 0) return Double.NaN;
            return success / 100.0;
        }

        /**
         * Fetch the total CoDel drop event count across all priority levels.
         *
         * <p>CoDelPriorityBlockingQueue emits per-priority stats with names like
         * {@code codel.UDP-Sender.drop.0}, {@code codel.UDP-Sender.drop.100}, etc.
         * This method sums the event counts across all priorities.
         *
         * @param ctx      the router context
         * @param prefix   the stat prefix (e.g., "codel.UDP-Sender.drop")
         * @return the total drop event count, or NaN if no priorities have events
         * @since 0.9.70+
         */
        protected double getCoDelDropEventCount(RouterContext ctx, String prefix) {
            // CoDelPriorityBlockingQueue priorities: {0, 100, 200, 300, 400, 500}
            final int[] priorities = {0, 100, 200, 300, 400, 500};
            double total = 0;
            boolean found = false;
            for (int p : priorities) {
                RateStat rs = ctx.statManager().getRate(prefix + p);
                if (rs == null) continue;
                Rate rate = rs.getRate(STAT_PERIOD);
                if (rate == null || rate.getLastEventCount() == 0) continue;
                total += rate.getLastEventCount();
                found = true;
            }
            return found ? total : Double.NaN;
        }

        /**
         * Fetch observed RTT, falling back to {@code udp.sendConfirmTime}
         * when the primary stat has no events (lightly loaded router).
         *
         * <p>The primary stat is typically a streaming-specific metric
         * (e.g., {@code stream.lifetimeRTT}) which only fires when active
         * streaming connections exist. On a lightly loaded router, this
         * may be NaN. The fallback {@code udp.sendConfirmTime} is always
         * available when any UDP traffic is present.
         *
         * @param ctx         the router context
         * @param primaryStat the streaming-specific RTT stat name
         * @return the observed RTT in ms, or NaN if neither stat has events
         * @since 0.9.70+
         */
        protected double getObservedRTT(RouterContext ctx, String primaryStat) {
            double val = getAdditionalStat(ctx, primaryStat);
            if (!Double.isNaN(val)) return val;
            return getAdditionalStat(ctx, "udp.sendConfirmTime");
        }

        /**
         * Get NTCP reader pool utilization (0.0-1.0).
         * @since 0.9.70+
         */
        protected double getReaderUtilization(RouterContext ctx) {
            Transport t = ctx.commSystem().getTransports().get(NTCPTransport.STYLE);
            return (t instanceof NTCPTransport) ? ((NTCPTransport) t).getReaderUtilization() : Double.NaN;
        }

        /**
         * Get NTCP writer pool utilization (0.0-1.0).
         * @since 0.9.70+
         */
        protected double getWriterUtilization(RouterContext ctx) {
            Transport t = ctx.commSystem().getTransports().get(NTCPTransport.STYLE);
            return (t instanceof NTCPTransport) ? ((NTCPTransport) t).getWriterUtilization() : Double.NaN;
        }

        /**
         * Get NTCP send finisher pool utilization (0.0-1.0).
         * @since 0.9.70+
         */
        protected double getSendFinisherUtilization(RouterContext ctx) {
            Transport t = ctx.commSystem().getTransports().get(NTCPTransport.STYLE);
            return (t instanceof NTCPTransport) ? ((NTCPTransport) t).getSendFinisherUtilization() : Double.NaN;
        }

        /**
         * Get UDP packet handler pool utilization (0.0-1.0).
         * @since 0.9.70+
         */
        protected double getPacketHandlerUtilization(RouterContext ctx) {
            Transport t = ctx.commSystem().getTransports().get(UDPTransport.STYLE);
            return (t instanceof UDPTransport) ? ((UDPTransport) t).getPacketHandlerUtilization() : Double.NaN;
        }

        /**
         * Get UDP message receiver pool utilization (0.0-1.0).
         * @since 0.9.70+
         */
        protected double getMessageReceiverUtilization(RouterContext ctx) {
            Transport t = ctx.commSystem().getTransports().get(UDPTransport.STYLE);
            return (t instanceof UDPTransport) ? ((UDPTransport) t).getMessageReceiverUtilization() : Double.NaN;
        }

        /**
         * Get tunnel pumper pool utilization (0.0-1.0).
         * @since 0.9.70+
         */
        protected double getPumperUtilization(RouterContext ctx) {
            return TunnelDispatcher.getPumperUtilization();
        }

        public int getCurrentValue() { return getRuntimeValue(); }

        protected void recordHistory(double observed) {
            if (_historyCount < MAX_HISTORY) {
                _valueHistory[_historyCount] = getRuntimeValue();
                _statHistory[_historyCount] = observed;
                _historyCount++;
            } else {
                System.arraycopy(_valueHistory, 1, _valueHistory, 0, MAX_HISTORY - 1);
                System.arraycopy(_statHistory, 1, _statHistory, 0, MAX_HISTORY - 1);
                _valueHistory[MAX_HISTORY - 1] = getRuntimeValue();
                _statHistory[MAX_HISTORY - 1] = observed;
            }
        }

        private static final double DEGRADED_THRESHOLD = 0.3;
        private boolean _reverted;

        /**
         * Main tuning loop for this param. Called once per cycle (60s).
         *
         * <p>Sequence:
         * <ol>
         *   <li>Read observed stat via {@link #getObservedStat}</li>
         *   <li>Record value/stat history for sparklines</li>
         *   <li>Skip if auto-tuning disabled or stat unavailable</li>
         *   <li>Auto-revert to default if system health &lt; 0.3</li>
         *   <li>Compute target via {@link #computeTarget}</li>
         *   <li>Apply health-based dampening (reduces step when degraded)</li>
         *   <li>Clamp to [min, max]</li>
         *   <li>Accelerate rollback when param diverges from default</li>
         *   <li>Call {@link #applyValue} and {@link #persistValue}</li>
         * </ol>
         *
         * @since 0.9.70+
         */
        public void update() {
            double observed = getObservedStat(null);
            recordHistory(observed);
            // First tick: restore persisted value from autotune.config
            if (_firstTick) {
                _firstTick = false;
                int current = getRuntimeValue();
                if (_initialValue != current) {
                    if (_log.shouldInfo())
                        _log.info(_name + " restoring persisted value " + _initialValue + " (current: " + current + ")");
                    applyValue(_initialValue);
                    persistValue(_ctx, _initialValue);
                }
            }
            if (!_autoTuning)
                return;
            if (Double.isNaN(observed))
                return;
            // Auto-revert to factory default when system is severely degraded
            if (_health != null && _health.getScore() < DEGRADED_THRESHOLD) {
                int current = getRuntimeValue();
                if (current != _defaultValue && !_reverted) {
                    if (_log.shouldInfo())
                        _log.info(_name + " auto-reverting from " + current + " to default " + _defaultValue + " (health: " + _health.getScore() + ")");
                    applyValue(_defaultValue);
                    _reverted = true;
                }
                return;
            }
            _reverted = false;
            int target = computeTarget(observed);
            // Health-based dampening: reduce step size when degraded
            if (_health != null && _health.getScore() < 0.6) {
                int current = getRuntimeValue();
                int rawDelta = target - current;
                // scale delta by health: at health=0.3 → 0% of delta, at health=0.6 → 100%
                int dampened = current + (int)(rawDelta * ((_health.getScore() - DEGRADED_THRESHOLD) / 0.3));
                // clamp to at least 1 step toward target
                if (dampened == current && target != current)
                    dampened = current + (rawDelta > 0 ? Math.min(_step, rawDelta) : Math.max(-_step, rawDelta));
                target = dampened;
            }
            // Hard clamp to [min, max] BEFORE comparison — prevents stale persisted values
            // from exceeding caps after code changes, even when computeTarget() returns
            // unclamped current or dampening pushes target outside range
            target = Math.max(_min, Math.min(_max, target));
            // Fast rollback: when current has diverged from _defaultValue and the stat
            // has moved against the divergence, snap back faster (2x step)
            int current = getRuntimeValue();
            if (target != current) {
                boolean divergedUp = current > _defaultValue;
                boolean divergedDown = current < _defaultValue;
                boolean statMovedAgainst = (divergedUp && target < current) ||
                                           (divergedDown && target > current);
                if (statMovedAgainst && Math.abs(target - current) > _step) {
                    // accelerate rollback: 2x step in the revert direction
                    int fastStep = _step * 2;
                    if (target < current)
                        target = Math.max(target, current - fastStep);
                    else
                        target = Math.min(target, current + fastStep);
                }
                if (_log.shouldInfo())
                    _log.info(_name + " changing from " + current + " to " + target + " (default: " + _defaultValue + ")");
                applyValue(target);
                persistValue(_ctx, target);
            }
        }

        /**
         * Move from current toward target, but at most one step per cycle.
         * Used by computeTarget() implementations to prevent large jumps.
         *
         * @param current the current runtime value
         * @param target  the desired target value
         * @param step    the maximum change per cycle
         * @return current ± step, clamped to target
         * @since 0.9.70+
         */
        protected static int clamp(int current, int target, int step) {
            if (target > current)
                return Math.min(target, current + step);
            else if (target < current)
                return Math.max(target, current - step);
            return current;
        }
    }

    /**
     * Reflection helper for calling streaming class methods across module boundaries.
     *
     * <p>The streaming module depends on router, not vice versa, so we use
     * reflection to call static getters/setters on I2PSocketManagerFull.
     * Class lookup is cached after first successful resolution.
     *
     * @since 0.9.70+
     */
    private static class StreamingReflector {
        private static final String FULL_CLASS = "net.i2p.client.streaming.impl.I2PSocketManagerFull";
        private static volatile Class<?> _cls;
        private static volatile boolean _resolved;

        private static Class<?> getCLS() {
            if (!_resolved) {
                try {
                    _cls = Class.forName(FULL_CLASS);
                } catch (ClassNotFoundException e) {
                    // streaming not loaded
                }
                _resolved = true;
            }
            return _cls;
        }

        static int invokeGetInt(String methodName) {
            Class<?> c = getCLS();
            if (c == null) return -1;
            try {
                return (Integer) c.getMethod(methodName).invoke(null);
            } catch (Exception e) {
                return -1;
            }
        }

        static void invokeSetInt(String methodName, int value) {
            Class<?> c = getCLS();
            if (c == null) return;
            try {
                c.getMethod(methodName, int.class).invoke(null, value);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Reflection helper for ConnectionOptions and Connection static methods.
     *
     * <p>Unlike {@link StreamingReflector}, this reflector caches failed
     * lookups and retries on each cycle. This handles the case where
     * streaming isn't loaded yet at tuner startup — the reflector will
     * retry until the classes are available.
     *
     * @since 0.9.70+
     */
    private static class StreamingConnectionReflector {
        private static volatile Class<?> _connectionOptionsCls;
        private static volatile Class<?> _connectionCls;
        private static volatile boolean _resolved;

        private static void resolve() {
            if (!_resolved) {
                try {
                    _connectionOptionsCls = Class.forName("net.i2p.client.streaming.impl.ConnectionOptions");
                    _connectionCls = Class.forName("net.i2p.client.streaming.impl.Connection");
                } catch (ClassNotFoundException e) {
                    // streaming not loaded yet — don't cache, retry next cycle
                    return;
                }
                _resolved = true;
            }
        }

        static int invokeConnectionOptionsInt(String methodName) {
            resolve();
            if (_connectionOptionsCls == null) return -1;
            try {
                return (Integer) _connectionOptionsCls.getMethod(methodName).invoke(null);
            } catch (Exception e) {
                return -1;
            }
        }

        static void invokeConnectionOptionsSet(String methodName, int value) {
            resolve();
            if (_connectionOptionsCls == null) return;
            try {
                _connectionOptionsCls.getMethod(methodName, int.class).invoke(null, value);
            } catch (Exception e) {
                // ignore
            }
        }

        static int invokeConnectionInt(String methodName) {
            resolve();
            if (_connectionCls == null) return -1;
            try {
                return (Integer) _connectionCls.getMethod(methodName).invoke(null);
            } catch (Exception e) {
                return -1;
            }
        }

        static void invokeConnectionSet(String methodName, int value) {
            resolve();
            if (_connectionCls == null) return;
            try {
                _connectionCls.getMethod(methodName, int.class).invoke(null, value);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Reflection helper for accessing TunnelControllerGroup across module boundaries.
     * i2ptunnel is not compiled into the router module, so we reflect.
     *
     * @since 0.9.70+
     */
    private static class I2PTunnelReflector {
        private static final String TCG = "net.i2p.i2ptunnel.TunnelControllerGroup";
        private static volatile Class<?> _cls;
        private static volatile boolean _resolved;

        private static Class<?> getCLS() {
            if (!_resolved) {
                try {
                    _cls = Class.forName(TCG);
                } catch (ClassNotFoundException e) {
                    // i2ptunnel not loaded
                }
                _resolved = true;
            }
            return _cls;
        }

        static int invokeGetInt(String methodName) {
            Class<?> c = getCLS();
            if (c == null) return -1;
            try {
                return (Integer) c.getMethod(methodName).invoke(null);
            } catch (Exception e) {
                return -1;
            }
        }

        static void invokeSetInt(String methodName, int value) {
            Class<?> c = getCLS();
            if (c == null) return;
            try {
                c.getMethod(methodName, int.class).invoke(null, value);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    // ==================== Transport Params ====================

    /**
     * Tunes ACK_FREQUENCY based on network round-trip time.
     * Lower frequency = more ACKs, less loss. Higher = fewer ACKs, less overhead.
     * Target: decrease frequency (more ACKs) when RTT is high,
     * increase (fewer ACKs) when RTT is low and no loss.
     */
    private class AckFrequencyParam extends BaseParam {
        private static final double LOW_THRESHOLD = 200;
        private static final double HIGH_THRESHOLD = 500;

        AckFrequencyParam() {
            super("ACK_FREQUENCY", "ACK interval (packets)",
                  SUB_TRANSPORT, 50, 300, 10, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            PeerState.setAckFrequency(value);
        }

        protected int getRuntimeValue() {
            return PeerState.getAckFrequency();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (actual network RTT, ms)
            // Cross-refs: sendMessageFailureLifetime (congestion), jobLag (CPU),
            //             udp.sendExpired (message timeouts)
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double sendExpired = getAdditionalStat(_context, "udp.sendExpired");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasLoss = !Double.isNaN(sendExpired) && sendExpired > 0;

            // Dead zone: RTT is in healthy range
            if (observed >= LOW_THRESHOLD && observed <= HIGH_THRESHOLD && !hasLoss)
                return current;

            // High RTT or loss: decrease frequency (more ACKs to detect loss faster)
            if (observed > HIGH_THRESHOLD || hasLoss) {
                if (systemBusy) return current;
                return Math.max(_min, current - _step);
            }

            // Low RTT + no loss + no congestion: increase frequency (fewer ACKs, less overhead)
            if (observed < LOW_THRESHOLD && !congested && !systemBusy && !hasLoss)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Tunes DATA_MESSAGE_TIMEOUT based on observed send confirm time
     * and LeaseSet lookup performance. Data messages must survive long
     * enough for the router to find the destination's LeaseSet before
     * the transport even begins sending; LeaseSet lookups can take
     * 15-20s when the network is congested.
     */
    private class DataMessageTimeoutParam extends BaseParam {

        DataMessageTimeoutParam() {
            super("DATA_MESSAGE_TIMEOUT", "Streaming data timeout (ms)",
                  SUB_TRANSPORT, 15000, 120000, 2000, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            UDPTransport.setDataMessageTimeout(value);
        }

        protected int getRuntimeValue() {
            return (int) UDPTransport.getDataMessageTimeout();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (ms, actual SSU send+confirm RTT)
            // Cross-refs: udp.sendExpired (SSU message timeouts), sendMessageFailureLifetime (congestion),
            //             jobLag (CPU), participating InBps (transit load),
            //             initialRTT (streaming connection establishment latency),
            //             leaseSetFailed/Found (NetDB lookup time)
            double sendExpired = getAdditionalStat(_context, "udp.sendExpired");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double initialRTT = getAdditionalStat(_context, "stream.con.initialRTT.out");
            double leaseFailedTime = getAdditionalStat(_context, "client.leaseSetFailedRemoteTime");
            double leaseFoundTime = getAdditionalStat(_context, "client.leaseSetFoundRemoteTime");
            double leaseFailedTimeHourly = getAdditionalStatHourly(_context, "client.leaseSetFailedRemoteTime");

            boolean hasTimeouts = !Double.isNaN(sendExpired) && sendExpired > 0;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 5000;
            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > getHeavyTransitThreshold(_context);
            boolean slowStreaming = !Double.isNaN(initialRTT) && initialRTT > 10000;
            boolean leaseFailed = (!Double.isNaN(leaseFailedTime) && leaseFailedTime > 0) ||
                                  (!Double.isNaN(leaseFailedTimeHourly) && leaseFailedTimeHourly > 5000);
            boolean lookupsSlow = !Double.isNaN(leaseFoundTime) && leaseFoundTime > 5000;

            // Base target: 3x observed send confirm time, with higher floor
            int target = Math.max(15000, (int) (observed * 3));

            // LeaseSet lookups are failing — ensure timeout covers lookup + delivery
            if (leaseFailed) {
                double lookupCost = !Double.isNaN(leaseFailedTime) ? leaseFailedTime : leaseFailedTimeHourly;
                target = Math.max(target, (int) (lookupCost + observed));
            }

            // LeaseSet lookups are succeeding but slow — add headroom
            if (lookupsSlow) {
                target = Math.max(target, (int) (leaseFoundTime * 3));
            }

            // Dead zone: if current is already within 30% of target, hold steady
            if (current >= target * 0.7 && current <= target * 1.3 && !hasTimeouts && !congested && !leaseFailed)
                return current;

            // Never decrease when there are timeouts, congestion, heavy transit,
            // slow streaming, or failing LeaseSet lookups
            if (target < current && (hasTimeouts || congested || heavyTransit || slowStreaming || leaseFailed))
                return current;

            // Force increase when send confirm time is very high (>15s)
            if (!Double.isNaN(observed) && observed > 15000 && current < _max) {
                target = Math.max(target, (int) Math.min(_max, observed * 4));
            }

            target = Math.max(15000, target);
            return clamp(current, target, _step);
        }
    }

    /**
     * Tunes MAX_OB_ESTABLISH_TIME based on outbound establish time stat.
     * Target: ~4x observed average outbound establish time with floor.
     */
    private class ObEstablishTimeParam extends BaseParam {

        ObEstablishTimeParam() {
            super("MAX_OB_ESTABLISH_TIME", "Outbound establish timeout (ms)",
                  SUB_TRANSPORT,

                  1500, 5000, 250, "udp.outboundEstablishTime", _context);
        }

        protected void applyValue(int value) {
            UDPTransport.setMaxObEstablishTime(value);
        }

        protected int getRuntimeValue() {
            return (int) UDPTransport.getMaxObEstablishTime();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double sendExpired = getAdditionalStat(_context, "udp.sendExpired");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean hasEstablishFailures = !Double.isNaN(sendExpired) && sendExpired > 0;
            boolean highRTT = !Double.isNaN(confirmTime) && confirmTime > 5000;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 5000;

            int target = Math.max(1500, (int) (observed * 4));

            if (current >= target * 0.5 && current <= target * 1.5 && !hasEstablishFailures)
                return current;

            if (target < current && (hasEstablishFailures || highRTT || congested))
                return current;

            return clamp(current, target, _step);
        }
    }

    /**
     * Tunes MAX_IB_ESTABLISH_TIME based on inbound establish time stat.
     * Target: ~4x observed average inbound establish time with floor.
     */
    private class IbEstablishTimeParam extends BaseParam {

        IbEstablishTimeParam() {
            super("MAX_IB_ESTABLISH_TIME", "Inbound establish timeout (ms)",
                  SUB_TRANSPORT,

                  1500, 5000, 250, "udp.inboundEstablishTime", _context);
        }

        protected void applyValue(int value) {
            UDPTransport.setMaxIbEstablishTime(value);
        }

        protected int getRuntimeValue() {
            return (int) UDPTransport.getMaxIbEstablishTime();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double sendExpired = getAdditionalStat(_context, "udp.sendExpired");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean hasEstablishFailures = !Double.isNaN(sendExpired) && sendExpired > 0;
            boolean highRTT = !Double.isNaN(confirmTime) && confirmTime > 5000;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 5000;

            int target = Math.max(1500, (int) (observed * 4));

            if (current >= target * 0.5 && current <= target * 1.5 && !hasEstablishFailures)
                return current;

            if (target < current && (hasEstablishFailures || highRTT || congested))
                return current;

            return clamp(current, target, _step);
        }
    }

    /**
     * Tunes NTCP ESTABLISH_TIMEOUT based on outbound establish time stat.
     * Single shared timeout for both inbound and outbound NTCP2 handshakes.
     * Primary signal: {@code ntcp.outboundEstablishTime}.
     *
     * @since 0.9.70+
     */
    private class NtcpEstablishTimeParam extends BaseParam {

        NtcpEstablishTimeParam() {
            super("NTCP_ESTABLISH_TIMEOUT", "NTCP establish timeout (ms)",
                  SUB_TRANSPORT,
                  1500, 5000, 250, "ntcp.outboundEstablishTime", _context);
        }

        protected void applyValue(int value) {
            NTCPTransport.setEstablishTimeout(value);
        }

        protected int getRuntimeValue() {
            return NTCPTransport.getEstablishTimeout();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double ibTime = getAdditionalStat(_context, "ntcp.inboundEstablishTime");
            double sendFailed = getAdditionalStat(_context, "ntcp.outboundEstablishFailed");

            boolean hasFailures = !Double.isNaN(sendFailed) && sendFailed > 0;
            double worstObserved = observed;
            if (!Double.isNaN(ibTime) && ibTime > worstObserved)
                worstObserved = ibTime;

            int target = Math.max(1500, (int) (worstObserved * 4));

            if (current >= target * 0.5 && current <= target * 1.5 && !hasFailures)
                return current;

            if (target < current && hasFailures)
                return current;

            return clamp(current, target, _step);
        }
    }

    /**
     * Tunes rDNS executor core pool size based on queue depth.
     * Only active when {@code routerconsole.enableReverseLookups} is true.
     * Primary signal: {@code rdns.executor.queueSize}.
     *
     * @since 0.9.70+
     */
    private class RdnsPoolSizeParam extends BaseParam {

        RdnsPoolSizeParam() {
            super("rdns.corePoolSize", "rDNS executor threads",
                  SUB_TRANSPORT,
                  2, 8, 1, "rdns.executor.queueSize", _context);
        }

        protected void applyValue(int value) {
            CommSystemFacadeImpl.setRdnsCorePoolSize(value);
        }

        protected int getRuntimeValue() {
            return CommSystemFacadeImpl.getRdnsCorePoolSize();
        }

        protected double getObservedStat(RouterContext ctx) {
            if (!_context.getBooleanProperty("routerconsole.enableReverseLookups"))
                return Double.NaN;
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // Skip tuning when rDNS is disabled
            if (!_context.getBooleanProperty("routerconsole.enableReverseLookups"))
                return current;
            double queueSize = observed;
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 50;

            // Queue building up — scale up
            if (!Double.isNaN(queueSize) && queueSize > 5 && !cpuPressure)
                return Math.min(_max, current + 1);

            // Queue empty and headroom — scale down
            if ((Double.isNaN(queueSize) || queueSize < 1) && !cpuPressure)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * Tunes max message dispatch age — messages older than this are dropped
     * instead of cycling through bid/send/fail/requeue until expiry.
     * <p>Primary signal: {@code transport.expiredOnQueueLifetime}.
     * Cross-refs: {@code transport.bidFailAllTransports}.
     *
     * @since 0.9.70+
     */
    private class MaxDispatchAgeParam extends BaseParam {

        MaxDispatchAgeParam() {
            super("i2p.router.maxDispatchAge", "Max message queue age (ms)",
                  SUB_TRANSPORT,

                  2000, 30000, 1000, "transport.expiredOnQueueLifetime", _context);
        }

        protected void applyValue(int value) {
            _maxDispatchAgeMs = value;
            _context.router().saveConfig("i2p.router.maxDispatchAge", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.router.maxDispatchAge", 3000);
        }

        protected void setRuntimeValue(int value) {
            _maxDispatchAgeMs = value;
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _ctx.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = transport.expiredOnQueueLifetime (ms, avg lifetime of expired messages)
            // Cross-refs: transport.bidFailAllTransports (messages with no transport),
            //             udp.outboundQueueDepth (total outbound pressure),
            //             transport.expiredOnQueueCount (expired per min),
            //             transport.messagesDelivered (delivered per min)
            double bidFails = getAdditionalEventCount(_context, "transport.bidFailAllTransports");
            double queueDepth = getAdditionalStat(_context, "udp.outboundQueueDepth");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double peerCount = getAdditionalStat(_context, "udp.peerCount");
            double expiredCount = getAdditionalStat(_context, "udp.peerExpired");
            double delivered = getAdditionalEventCount(_context, "transport.messagesDelivered");
            double expiredQ = getAdditionalEventCount(_context, "transport.expiredOnQueueCount");

            boolean lotsOfExpirations = observed > 5000;
            boolean lotsOfBidFails = !Double.isNaN(bidFails) && bidFails > 10;
            boolean deepQueue = !Double.isNaN(queueDepth) && queueDepth > 2000;
            boolean cpuFree = Double.isNaN(jobLag) || jobLag < 20;
            boolean peersFalling = !Double.isNaN(peerCount) && peerCount < 1000;
            boolean drainActive = !Double.isNaN(expiredCount) && expiredCount > 50;
            boolean atFloor = current <= _min;
            double totalCompleted = !Double.isNaN(delivered) && !Double.isNaN(expiredQ) ? delivered + expiredQ : Double.NaN;
            double deliveryRatio = !Double.isNaN(totalCompleted) && totalCompleted > 0 ? delivered / totalCompleted : Double.NaN;
            boolean deathSpiral = !Double.isNaN(deliveryRatio) && deliveryRatio < 0.3 && atFloor && deepQueue;
            boolean healthyDelivery = !Double.isNaN(deliveryRatio) && deliveryRatio > 0.8;
            boolean lowThroughput = !Double.isNaN(delivered) && delivered < 10 && !Double.isNaN(expiredQ) && expiredQ > 50;

            // Death spiral: at floor + deep queue + <30% delivery ratio.
            // Messages expire before they can be dispatched. Raise age so they
            // survive long enough for a transport slot to open up.
            if (deathSpiral)
                return Math.min(3000, current + _step * 2);

            // Near death spiral: low throughput even without deep queue
            if (lowThroughput && atFloor && !healthyDelivery)
                return Math.min(3000, current + _step);

            // Deep queue + lots of expirations with healthy delivery = queue is filling
            // faster than it drains. Drop stale messages faster to free slots.
            if (deepQueue && lotsOfExpirations && !atFloor)
                return Math.max(_min, current - _step * 2);

            // Many expirations = lower age limit (stop hoarding dead messages)
            if (lotsOfExpirations && !atFloor)
                return Math.max(_min, current - _step);

            // Bid fails + expirations + no delivery = can't route, stop trying
            if (lotsOfBidFails && lotsOfExpirations && !atFloor && !healthyDelivery)
                return Math.max(_min, current - _step * 2);

            // Recovery: healthy delivery + queue clearing + CPU free = raise toward default
            if (healthyDelivery && !deepQueue && cpuFree && current < 3000)
                return Math.min(3000, current + _step * 2);

            // Clear: few expirations + few bid fails + no delivery pressure = raise
            if (observed < 1000 && !lotsOfBidFails && !deepQueue && healthyDelivery)
                return Math.min(3000, current + _step);

            return current;
        }
    }

    /**
     * Tunes I/O handler thread priority based on dispatch latency and queue backlogs.
     * Higher priority under load helps handler threads抢占 CPU from idle work.
     * Lower priority when idle lets other work run.
     * <p>Primary signal: {@code transport.sendProcessingTime}.
     * Cross-refs: {@code ntcp.readQueueSize}, {@code ntcp.sendFinisher.queueSize},
     *             {@code jobQueue.jobLag}.
     *
     * @since 0.9.70+
     */
    private class HandlerThreadPriorityParam extends BaseParam {

        HandlerThreadPriorityParam() {
            super("i2p.router.handlerThreadPriority", "I/O thread priority",
                  SUB_TRANSPORT,

                  Thread.MIN_PRIORITY + 2, Thread.MAX_PRIORITY - 1, 1,
                  "transport.sendProcessingTime", _context);
        }

        protected void applyValue(int value) {
            _handlerThreadPriority = value;
            _context.router().saveConfig("i2p.router.handlerThreadPriority", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.router.handlerThreadPriority", Thread.NORM_PRIORITY);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _ctx.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = transport.sendProcessingTime (ms, dispatch latency)
            // Cross-refs: ntcp.readQueueSize, ntcp.sendFinisher.queueSize, jobQueue.jobLag,
            //             udp.outboundQueueDepth (outbound pressure)
            double readQueue = getAdditionalStat(_context, "ntcp.readQueueSize");
            double finisherQueue = getAdditionalStat(_context, "ntcp.sendFinisher.queueSize");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double queueDepth = getAdditionalStat(_context, "udp.outboundQueueDepth");

            boolean highLatency = observed > 200;
            boolean moderateLatency = observed > 100;
            boolean queuesBackedUp = (!Double.isNaN(readQueue) && readQueue > 5) ||
                                     (!Double.isNaN(finisherQueue) && finisherQueue > 5);
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 50;
            boolean deepQueue = !Double.isNaN(queueDepth) && queueDepth > 2000;

            // Deep outbound queue + moderate latency = boost priority to drain faster
            if (deepQueue && moderateLatency)
                return Math.min(_max, current + 2);

            // High latency + backed up queues = boost priority hard
            if (highLatency && queuesBackedUp)
                return Math.min(_max, current + 2);

            // Moderate latency or queues building = boost slightly
            if (moderateLatency || queuesBackedUp)
                return Math.min(_max, current + _step);

            // Low latency + idle queues + no CPU pressure = reduce priority
            if (observed < 50 && !queuesBackedUp && !cpuPressure)
                return Math.max(_min, current - _step);

            // CPU pressure = boost (don't let handlers starve)
            if (cpuPressure && moderateLatency)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    // ==================== Tunnel Params ====================

    /**
     * Tunes TunnelGatewayPumper REQUEUE_TIME based on pumper queue depth.
     * Lower requeue time = faster retry = lower latency under load.
     * Target: increase requeue time when queue depth is high (reduce CPU thrash),
     * decrease when queue depth is low (reduce latency).
     */
    private class RequeueTimeParam extends BaseParam {

        RequeueTimeParam() {
            super("REQUEUE_TIME", "Tunnel pumper requeue delay (ms)",
                  SUB_TUNNEL,

                  10, 200, 5, "tunnel.participating InBps", _context);
        }

        protected void applyValue(int value) {
            TunnelDispatcher.setRequeueTime(value);
        }

        protected int getRuntimeValue() {
            return (int) TunnelDispatcher.getRequeueTime();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = participating InBps (transit inbound bandwidth)
            // Cross-refs: gateway overflow drops, queue sizes, NTCP pumper loop rate
            double obDrops = getAdditionalEventCount(_context, "tunnel.dropGatewayOverflowOB");
            double ibDrops = getAdditionalEventCount(_context, "tunnel.dropGatewayOverflowIB");
            double obQueue = getAdditionalStat(_context, "tunnel.obgw.queueSize");
            double ibQueue = getAdditionalStat(_context, "tunnel.ibgw.queueSize");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double pumperLoops = getAdditionalStat(_context, "ntcp.pumperLoopsPerSecond");

            boolean hasDrops = (!Double.isNaN(obDrops) && obDrops > 0) ||
                               (!Double.isNaN(ibDrops) && ibDrops > 0);
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean queuesBackedUp = (!Double.isNaN(obQueue) && obQueue > 200) ||
                                     (!Double.isNaN(ibQueue) && ibQueue > 200);
            boolean pumperSpinning = !Double.isNaN(pumperLoops) && pumperLoops > 10000;

            // NTCP pumper spinning at extreme rate — increase requeue to reduce total loop pressure
            if (pumperSpinning && !queuesBackedUp)
                return Math.min(_max, current + _step);

            // Low transit + spare capacity + queues empty = decrease requeue (faster retry, lower latency)
            if (observed < 10000 && !hasDrops && !systemBusy && !queuesBackedUp)
                return Math.max(_min, current - _step);

            // Heavy transit + drops OR queues backed up = increase requeue (back off, clear backlog)
            // But if CPU is overloaded, hold steady
            if ((observed > 50000 && hasDrops) || queuesBackedUp) {
                if (systemBusy) return current;
                return Math.min(_max, current + _step);
            }

            return current;
        }
    }

    /**
     * Tunes TunnelGatewayPumper queue capacity based on gateway queue depth.
     * Larger queue = more buffering before drops under burst load.
     * Primary signal: tunnel.pumperQueueDepth (pumper queue usage).
     * Cross-refs: tunnel.pumperQueueFull (drop events), tunnel.ibgw/obgw.queueSize.
     *
     * @since 0.9.70+
     */
    private class PumperQueueCapacityParam extends BaseParam {

        PumperQueueCapacityParam() {
            super("tunnel.pumper.queueCapacity", "Tunnel pumper queue capacity",
                  SUB_TUNNEL,

                  256, 4096, 128, "tunnel.pumperQueueFull", _context);
        }

        protected void applyValue(int value) {
            TunnelDispatcher.setPumperQueueCapacity(value);
            TunnelDispatcher.resizePumperQueue(value);
        }

        protected int getRuntimeValue() {
            return TunnelDispatcher.getPumperQueueCapacity();
        }

        protected double getObservedStat(RouterContext ctx) {
            return getAdditionalEventCount(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.pumperQueueFull event count (drop events per period)
            // Cross-refs: tunnel.ibgw/obgw.queueSize, tunnel.dispatchParticipant,
            //             jobQueue.jobLag (CPU), memory pressure
            double ibgwQueue = getAdditionalStat(_context, "tunnel.ibgw.queueSize");
            double obgwQueue = getAdditionalStat(_context, "tunnel.obgw.queueSize");
            double transitLoad = getAdditionalEventCount(_context, "tunnel.dispatchParticipant");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double memPressure = getMemoryPressure();
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean memOk = memPressure < 0.75;
            boolean memCritical = memPressure > 0.85;
            boolean hasDrops = !Double.isNaN(observed) && observed > 0;
            boolean queuesBackedUp = (!Double.isNaN(ibgwQueue) && ibgwQueue > 50) ||
                                     (!Double.isNaN(obgwQueue) && obgwQueue > 50);
            boolean heavyTransit = !Double.isNaN(transitLoad) && transitLoad > 2000;

            // Memory critical: shrink immediately
            if (memCritical && current > _min)
                return Math.max(_min, current - _step * 2);

            // Drops or backed-up queues + no CPU + memory OK = grow queue
            if ((hasDrops || queuesBackedUp) && !cpuPressure && memOk)
                return Math.min(_max, current + _step);

            // Heavy transit + no pressure + memory OK = grow proactively
            if (heavyTransit && !cpuPressure && memOk && current < _max / 2)
                return Math.min(_max, current + _step);

            // No drops + low queues + light load = shrink to save memory
            if (!hasDrops && !queuesBackedUp && !heavyTransit && !cpuPressure && current > _min)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Tunes TunnelGatewayPumper thread count based on pumper backlog.
     * More threads = more parallel gateway pumping = lower latency.
     * Primary signal: tunnel.pumperQueueDepth (pumper queue usage).
     * Cross-refs: tunnel.ibgw/obgw.queueSize, jobQueue.jobLag (CPU).
     *
     * @since 0.9.70+
     */
    private class PumperThreadsParam extends BaseParam {

        PumperThreadsParam() {
            super("tunnel.pumper.threads", "Tunnel pumper threads",
                  SUB_TUNNEL,

                  2, 16, 1, "tunnel.pumperQueueDepth", _context);
        }

        protected void applyValue(int value) {
            TunnelDispatcher.setPumperMaxThreads(value);
            TunnelDispatcher.adjustPumperThreads(value);
        }

        protected int getRuntimeValue() {
            return TunnelDispatcher.getPumperMaxThreads();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.pumperQueueDepth (avg items in pumper queue)
            // Cross-refs: tunnel.ibgw/obgw.queueSize, tunnel.pumperQueueFull,
            //             jobQueue.jobLag (CPU)
            double ibgwQueue = getAdditionalStat(_context, "tunnel.ibgw.queueSize");
            double obgwQueue = getAdditionalStat(_context, "tunnel.obgw.queueSize");
            double drops = getAdditionalStat(_context, "tunnel.pumperQueueFull");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean queueHigh = !Double.isNaN(observed) && observed > 20;
            boolean hasDrops = !Double.isNaN(drops) && drops > 0;
            boolean queuesBackedUp = (!Double.isNaN(ibgwQueue) && ibgwQueue > 50) ||
                                     (!Double.isNaN(obgwQueue) && obgwQueue > 50);

            // Utilization: ratio of threads actively pumping vs total pool size
            double utilization = getPumperUtilization(_context);
            boolean highUtilization = !Double.isNaN(utilization) && utilization > 0.8;
            boolean lowUtilization = !Double.isNaN(utilization) && utilization < 0.2;

            // Queue depth high, drops, or backed up + no CPU = add threads
            if ((queueHigh || hasDrops || queuesBackedUp) && !cpuPressure)
                return Math.min(_max, current + 1);

            // High utilization + queue pressure = add threads
            if (highUtilization && (queueHigh || queuesBackedUp) && !cpuPressure)
                return Math.min(_max, current + 1);

            // Idle + no pressure = remove threads
            if (!queueHigh && !hasDrops && !queuesBackedUp && !cpuPressure && lowUtilization && current > _min)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * Tunes FIFOBandwidthRefiller REPLENISH_FREQUENCY based on bandwidth utilization.
     * More frequent refill = smoother bandwidth = lower latency.
     * Target: decrease frequency (faster refill) when bandwidth is high,
     * increase (save CPU) when bandwidth is low.
     */
    private class ReplenishFrequencyParam extends BaseParam {

        ReplenishFrequencyParam() {
            super("REPLENISH_FREQUENCY", "Bandwidth token refill interval (ms)",
                  SUB_TUNNEL,

                  5, 200, 5, "tunnel.participating OutBps", _context);
        }

        protected void applyValue(int value) {
            FIFOBandwidthRefiller.setReplenishFrequency(value);
        }

        protected int getRuntimeValue() {
            return (int) FIFOBandwidthRefiller.getReplenishFrequency();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = participating OutBps (transit outbound bandwidth)
            // Cross-refs: sendMessageFailureLifetime (starvation), jobLag (CPU)
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean starving = !Double.isNaN(failLifetime) && failLifetime > 10000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            // Low bandwidth + spare capacity = decrease interval (faster refill, smoother latency)
            if (observed < 10000 && !starving && !systemBusy)
                return Math.max(_min, current - _step);

            // High bandwidth + starvation = increase interval (batch refills, reduce CPU)
            if (observed > 50000 && starving) {
                if (systemBusy) return current;
                return Math.min(_max, current + _step);
            }

            return current;
        }
    }

    /**
     * Tunes EventPumper SELECTOR_LOOP_DELAY based on NTCP loop rate.
     * Lower delay = more responsive I/O = lower latency.
     * Target: decrease delay (more responsive) when loop rate is moderate,
     * increase (save CPU) when loop rate is very high.
     */
    private class SelectorLoopDelayParam extends BaseParam {

        SelectorLoopDelayParam() {
            super("SELECTOR_LOOP_DELAY", "NTCP selector sleep (ms)",
                  SUB_TRANSPORT,
                  1, 100, 10, "ntcp.pumperLoopsPerSecond", _context);
        }

        protected void applyValue(int value) {
            NTCPTransport.setSelectorLoopDelay(value);
        }

        protected int getRuntimeValue() {
            return (int) NTCPTransport.getSelectorLoopDelay();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = ntcp.pumperLoopsPerSecond (NTCP event loop rate)
            // Cross-refs: jobLag (CPU), ntcp.writeQueueFull (NTCP pressure)
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double writeQueueFull = getAdditionalStat(_context, "ntcp.writeQueueFull");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 50;
            boolean ntcpPressure = !Double.isNaN(writeQueueFull) && writeQueueFull > 0;

            // Extremely high loop rate (>50K/s) = aggressive delay increase
            // prevents busy-spinning even when CPU load appears moderate
            if (observed > 50000)
                return Math.min(_max, current + _step * 5);

            // Very high loop rate (>10K/s) = moderate delay increase
            if (observed > 10000)
                return Math.min(_max, current + _step * 2);

            // High loop rate (>2000/s) = gradual delay increase
            if (observed > 2000 && current < _max)
                return Math.min(_max, current + _step);

            // Low loop rate + spare capacity = decrease delay (faster pumper, burst handling)
            if (observed < 200 && !systemBusy && !ntcpPressure && !highLoad)
                return Math.max(_min, current - _step);

            // NTCP pressure = decrease delay immediately
            if (ntcpPressure && !systemBusy && current > _min)
                return Math.max(_min, current - _step);

            // CPU loaded = increase delay (reduce CPU pressure)
            if (systemBusy && !highLoad && current < _max)
                return Math.min(_max, current + _step);

            // High system load = increase delay (reduce CPU pressure)
            if (highLoad && current < _max)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Tunes PumpedTunnelGateway MAX_OB_MSGS_PER_PUMP based on gateway queue depth.
     * Higher batch = more throughput per pump, but higher latency per message.
     * Target: decrease batch when queue is filling (reduce latency),
     * increase when queue is empty and no pressure (maximize throughput).
     */
    private class ObMsgsPerPumpParam extends BaseParam {

        ObMsgsPerPumpParam() {
            super("MAX_OB_MSGS_PER_PUMP", "Outbound gateway batch size",
                  SUB_TUNNEL,

                  8, 1024, 16, "tunnel.obgw.queueSize", _context);
        }

        protected void applyValue(int value) {
            TunnelDispatcher.setMaxObMsgsPerPump(value);
        }

        protected int getRuntimeValue() {
            return TunnelDispatcher.getMaxObMsgsPerPump();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.obgw.queueSize (queue depth, messages waiting)
            // Cross-refs: tunnel.dropGatewayOverflowOB (drops), jobLag (CPU), memory
            // Note: dropGatewayOverflowOB is an event-count stat (addRateData(1));
            //       getAdditionalStat() returns 1.0 always when events exist,
            //       so we use getAdditionalEventCount() for the raw count.
            double drops = getAdditionalEventCount(_context, "tunnel.dropGatewayOverflowOB");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasDrops = !Double.isNaN(drops) && drops > 5;
            boolean queueFilling = observed > 200;

            // Queue filling fast + drops: decrease (reduce batch to lower latency)
            if (queueFilling && hasDrops)
                return Math.max(_min, current - _step);

            // Drops under load: decrease to reduce latency
            if (hasDrops && (cpuPressure || highLoad))
                return Math.max(_min, current - _step);

            // Queue deep + no headroom: decrease
            if (queueFilling && (cpuPressure || highLoad))
                return Math.max(_min, current - _step);

            // No drops + queue empty + headroom: increase batch for throughput
            if (!hasDrops && observed < 50 && !cpuPressure && !highLoad && memPressure < 0.6)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Tunes PumpedTunnelGateway MAX_IB_MSGS_PER_PUMP based on gateway queue depth.
     * Higher batch = more throughput per pump, but higher latency per message.
     * Target: decrease batch when queue is filling (reduce latency),
     * increase when queue is empty and no pressure (maximize throughput).
     */
    private class IbMsgsPerPumpParam extends BaseParam {

        IbMsgsPerPumpParam() {
            super("MAX_IB_MSGS_PER_PUMP", "Inbound gateway batch size",
                  SUB_TUNNEL,

                  8, 512, 8, "tunnel.ibgw.queueSize", _context);
        }

        protected void applyValue(int value) {
            TunnelDispatcher.setMaxIbMsgsPerPump(value);
        }

        protected int getRuntimeValue() {
            return TunnelDispatcher.getMaxIbMsgsPerPump();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.ibgw.queueSize (queue depth, messages waiting)
            // Cross-refs: tunnel.dropGatewayOverflowIB (drops), transit InBps, jobLag, memory
            // Note: dropGatewayOverflowIB is an event-count stat (addRateData(1));
            //       use getAdditionalEventCount() for the raw count.
            double drops = getAdditionalEventCount(_context, "tunnel.dropGatewayOverflowIB");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();

            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > getHeavyTransitThreshold(_context);
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasDrops = !Double.isNaN(drops) && drops > 5;
            boolean queueFilling = observed > 200;

            // Drops under pressure: decrease
            if (hasDrops) {
                if (heavyTransit && systemBusy) return current;
                return Math.max(_min, current - _step);
            }

            // Queue filling fast: decrease (reduce batch to lower latency)
            if (queueFilling && (systemBusy || highLoad))
                return Math.max(_min, current - _step);

            // No drops + queue empty + headroom + heavy transit: increase for throughput
            if (observed < 50 && heavyTransit && !systemBusy && !highLoad && memPressure < 0.6)
                return Math.min(_max, current + _step);

            // No drops + queue empty + headroom + no transit: grow cautiously
            if (observed < 50 && !heavyTransit && !systemBusy && !highLoad && memPressure < 0.5)
                return Math.min(_max, current + Math.max(1, _step / 2));

            return current;
        }
    }

    // ==================== Streaming Params ====================

    /**
     * Tunes streaming INITIAL_WINDOW_SIZE based on observed RTT.
     * Higher initial window = faster ramp-up for new connections.
     * Target: increase when RTT is low (fast pipe), decrease when dropping (congestion).
     */
    private class InitialWindowSizeParam extends BaseParam {

        InitialWindowSizeParam() {
            super("INITIAL_WINDOW_SIZE", "Initial congestion window",
                  SUB_STREAMING,

                  1, 256, 4, "stream.con.initialRTT.in", _context);
        }

        protected void applyValue(int value) {
            StreamingReflector.invokeSetInt("setInitialWindowSize", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingReflector.invokeGetInt("getInitialWindowSize");
            return v >= 0 ? v : 16;
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = stream.con.initialRTT.in (inbound RTT, ms)
            // Cross-refs: sendMessageFailureLifetime (congestion), buildSuccessRate (network health),
            //             sendDuplicateSize (drops!), lifetimeRTT (completed stream RTT),
            //             lifetimeSendWindowSize (final window size at stream close),
            //             chokeSizeBegin (choke pressure)
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double buildSuccess = getBuildSuccessRate(_context);
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");
            double lifetimeRTT = getAdditionalStat(_context, "stream.con.lifetimeRTT");
            double lifetimeWindowSize = getAdditionalStat(_context, "stream.con.lifetimeSendWindowSize");
            double chokeSize = getAdditionalStat(_context, "stream.chokeSizeBegin");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean dropping = !Double.isNaN(dupSize) && dupSize > 500;
            boolean streamsSlow = !Double.isNaN(lifetimeRTT) && lifetimeRTT > 5000;
            boolean windowsSmall = !Double.isNaN(lifetimeWindowSize) && lifetimeWindowSize < 4;
            boolean choking = !Double.isNaN(chokeSize) && chokeSize > 5;

            // FAST PATH: latency + drops = congestion-driven shrink
            // High latency alone is not a reason to shrink streaming windows —
            // it's often transit load or warm-up. Only shrink when drops confirm
            // the window is too aggressive for the path.
            if (!Double.isNaN(dupSize) && dupSize > 500) {
                int aggression = dupSize > 1000 ? _step * 2 : _step;
                return Math.max(_min, current - aggression);
            }

            // Drops or congestion = shrink (loss minimization)
            if (dropping || congested)
                return Math.max(_min, current - _step);

            // Network unhealthy = shrink toward default
            if (!networkHealthy) {
                if (current > _defaultValue)
                    return Math.max(_defaultValue, current - _step);
                return current;
            }

            // Completed streams slow + windows small = increase (pipe can handle larger windows)
            if (streamsSlow && windowsSmall && !dropping && !congested)
                return Math.min(_max, current + _step);

            // Choking = decrease (window too aggressive for path)
            if (choking)
                return Math.max(_min, current - _step);

            // Low RTT (fast pipe) + no drops + no congestion = increase window
            if (observed < 10000 && !dropping && !congested)
                return Math.min(_max, current + _step);

            // High RTT (slow pipe) + no drops + healthy network = cautiously increase
            if (observed > 20000 && !dropping && !congested && networkHealthy)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Tunes streaming INITIAL_RTO based on observed RTT and congestion signals.
     * Higher RTO = more tolerant of latency spikes, lower = faster loss detection.
     * Target: 2x RTT as baseline, adjust based on congestion and network health.
     */
    private class InitialRTOParam extends BaseParam {

        InitialRTOParam() {
            super("INITIAL_RTO", "First retransmit timeout (ms)",
                  SUB_STREAMING,

                  1000, 30000, 1000, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            StreamingReflector.invokeSetInt("setInitialRTO", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingReflector.invokeGetInt("getInitialRTO");
            return v >= 0 ? v : 6000;
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = stream.con.initialRTT.out (outbound RTT in ms)
            // Cross-refs: sendMessageFailureLifetime (congestion), buildSuccessRate (network health),
            //             udp.sendConfirmTime (actual RTT), sendDuplicateSize (drops!)
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double buildSuccess = getBuildSuccessRate(_context);
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean spuriousRetransmits = !Double.isNaN(dupSize) && dupSize > 1000;
            boolean highRTT = !Double.isNaN(confirmTime) && confirmTime > 20000;

            // Target: 2x RTT as baseline (standard TCP-like behavior)
            int target = Math.max(2000, Math.min(_max, (int) (observed * 2)));

            // FAST PATH: latency target violated — raise RTO (faster loss detection helps latency)
            if (highRTT && target < current)
                return Math.min(_max, current + _step);

            // Spurious retransmits = raise RTO (stop wasting bandwidth)
            if (spuriousRetransmits && target < current)
                return Math.min(_max, current + _step);

            // Congested or network unhealthy = don't lower RTO
            if ((congested || !networkHealthy) && target < current)
                return current;

            // Dead zone: if current is within 50% of target and no drops, hold
            if (current >= target * 0.5 && current <= target * 1.5 && !spuriousRetransmits)
                return current;

            return clamp(current, target, _step);
        }
    }

    /**
     * Tunes streaming INITIAL_ACK_DELAY based on sends-before-ACK stat.
     * Lower delay = more responsive ACKs, higher = more piggybacking.
     * Target: decrease when sends are backing up, increase when stable.
     */
    private class InitialAckDelayParam extends BaseParam {

        InitialAckDelayParam() {
            super("INITIAL_ACK_DELAY", "Piggyback ACK wait (ms)",
                  SUB_STREAMING,

                  10, 100, 5, "stream.sendsBeforeAck", _context);
        }

        protected void applyValue(int value) {
            StreamingReflector.invokeSetInt("setDefaultInitialAckDelay", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingReflector.invokeGetInt("getDefaultInitialAckDelay");
            return v >= 0 ? v : 20;
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = stream.sendsBeforeAck (avg sends before standalone ACK)
            // Cross-refs: sendMessageSize (message size), udp.sendConfirmTime (actual RTT),
            //             sendDuplicateSize (drops!)
            double msgSize = getAdditionalStat(_context, "stream.con.sendMessageSize");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");

            boolean largeMessages = !Double.isNaN(msgSize) && msgSize > 1000;
            boolean dropping = !Double.isNaN(dupSize) && dupSize > 500;
            boolean highRTT = !Double.isNaN(confirmTime) && confirmTime > 20000;

            // FAST PATH: latency + drops = sender needs fast feedback
            if (highRTT && dropping) {
                int aggression = confirmTime > 25000 ? _step * 2 : _step;
                return Math.max(_min, current - aggression);
            }

            // Drops + many sends = decrease delay (sender needs faster feedback)
            if (dropping && observed > 3)
                return Math.max(_min, current - _step);

            // High sends + small messages = increase delay (more piggybacking)
            if (observed > 5 && !largeMessages)
                return Math.min(_max, current + _step);

            // Low sends OR large messages = decrease delay (more responsive)
            if (observed < 2 || largeMessages)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Tunes streaming PASSIVE_FLUSH_DELAY based on message send size stat.
     * Lower delay = lower latency, higher = better batching.
     * Target: decrease under load (low latency), increase when idle (batch efficiency).
     */
    private class PassiveFlushDelayParam extends BaseParam {

        PassiveFlushDelayParam() {
            super("PASSIVE_FLUSH_DELAY", "Nagle flush delay (ms)",
                  SUB_STREAMING,

                  10, 200, 10, "stream.con.sendMessageSize", _context);
        }

        protected void applyValue(int value) {
            StreamingReflector.invokeSetInt("setDefaultPassiveFlushDelay", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingReflector.invokeGetInt("getDefaultPassiveFlushDelay");
            return v >= 0 ? v : 100;
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = stream.con.sendMessageSize (avg outgoing message size)
            // Cross-refs: sendsBeforeAck, udp.sendConfirmTime (actual RTT),
            //             sendDuplicateSize (drops!)
            double sendsBeforeAck = getAdditionalStat(_context, "stream.sendsBeforeAck");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");

            boolean manySends = !Double.isNaN(sendsBeforeAck) && sendsBeforeAck > 3;
            boolean dropping = !Double.isNaN(dupSize) && dupSize > 500;
            boolean highRTT = !Double.isNaN(confirmTime) && confirmTime > 20000;

            // FAST PATH: latency + drops = flush immediately (clear HOL blocking)
            if (highRTT && dropping) {
                int aggression = confirmTime > 25000 ? _step * 2 : _step;
                return Math.max(_min, current - aggression);
            }

            // Drops + many sends = decrease delay (reduce HOL blocking)
            if (dropping && manySends)
                return Math.max(_min, current - _step);

            // Large messages + many sends = decrease delay (optimize latency)
            if (observed > 1000 && manySends)
                return Math.max(_min, current - _step);

            // Small messages + few sends = increase delay (batch small writes)
            if (observed < 200 && !manySends)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Tunes streaming max slow start window.
     * Controls how large a stream's congestion window can grow during slow start.
     * Higher = more aggressive ramp-up, lower = gentler.
     */
    private class MaxSlowStartWindowParam extends BaseParam {

        MaxSlowStartWindowParam() {
            super("i2p.streaming.maxSlowStartWindow", "Streaming slow start cap",
                  SUB_STREAMING,

                  8, 256, 4, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.maxSlowStartWindow", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.maxSlowStartWindow", 32);
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = stream.con.initialRTT.out (outbound RTT, ms)
            // Cross-refs: sendMessageFailureLifetime (congestion),
            //             sendDuplicateSize (drops!)
            // NOTE: sendProcessingTime is NOT used — it's a transport metric, not streaming.
            // Using it creates a feedback loop: high delay → slower streaming → more queuing → higher delay.
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean dropping = !Double.isNaN(dupSize) && dupSize > 500;

            // Recovery floor: if below 50% of default, always increase back toward default
            // unless severe drops or congestion. Prevents params from getting stuck at minimums.
            int recoveryFloor = Math.max(_min, _defaultValue / 2);
            if (current < recoveryFloor && !congested)
                return Math.min(_defaultValue, current + _step);

            // Severe drops or congestion = shrink window cap (loss minimization)
            if (dropping || congested)
                return Math.max(recoveryFloor, current - _step);

            // Dead zone: hold within 50% of default unless signal is strong
            if (current >= recoveryFloor && current <= _defaultValue * 2 && !dropping && !congested)
                return current;

            // Below default: increase if no drops/congestion
            if (current < _defaultValue && !dropping && !congested)
                return Math.min(_max, current + _step);

            // Above default: decrease if RTT is high
            if (current > _defaultValue && observed > 20000)
                return Math.max(recoveryFloor, current - _step);

            return current;
        }
    }

    // ==================== I2CP Params ====================

    /**
     * Tunes I2CP CLIENT_WRITER_QUEUE_SIZE based on network load.
     * Higher queue = more buffer for slow clients, lower = less memory per client.
     * Target: increase when network is slow (clients back up),
     * decrease when fast and no overflow pressure.
     */
    private class WriterQueueSizeParam extends BaseParam {

        WriterQueueSizeParam() {
            super("CLIENT_WRITER_QUEUE_SIZE", "I2CP write queue (messages)",
                  SUB_I2CP,

                  32, 2048, 32, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            ClientManagerFacadeImpl.setWriterQueueSize(value);
        }

        protected int getRuntimeValue() {
            return ClientManagerFacadeImpl.getWriterQueueSize();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (ms, actual network RTT)
            // Cross-refs: client.writerQueueFull (overflow events), client.dispatchSendTime, jobLag (CPU), sendProcessingTime
            // Note: writerQueueFull is an event-count stat (addRateData(1));
            //       use getAdditionalEventCount/Hourly() for raw counts.
            double overflows = getAdditionalEventCount(_context, "client.writerQueueFull");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double sendTime = getAdditionalStat(_context, "transport.sendProcessingTime");
            double dispatchSend = getAdditionalStat(_context, "client.dispatchSendTime");
            double hourlyOverflows = getAdditionalEventCountHourly(_context, "client.writerQueueFull");
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasOverflows = !Double.isNaN(overflows) && overflows > 0;
            boolean sustainedOverflows = !Double.isNaN(hourlyOverflows) && hourlyOverflows > 2;
            boolean highRTT = !Double.isNaN(observed) && observed > 20000;
            boolean moderateRTT = !Double.isNaN(observed) && observed > 10000;
            boolean lowLatency = !Double.isNaN(sendTime) && sendTime < 50;
            boolean dispatchSlow = !Double.isNaN(dispatchSend) && dispatchSend > 200;

            // Overflow or sustained overflows + headroom = increase queue
            if ((hasOverflows || sustainedOverflows) && !systemBusy)
                return Math.min(_max, current + _step);

            // High RTT + no CPU pressure = increase queue (clients back up on slow links)
            if (highRTT && !systemBusy)
                return Math.min(_max, current + _step);

            // Moderate RTT + internal processing slow = increase queue
            if (moderateRTT && !Double.isNaN(sendTime) && sendTime > 100 && !systemBusy)
                return Math.min(_max, current + Math.max(1, _step / 2));

            // Dispatch send time high = queue contention, increase capacity
            if (dispatchSlow && !systemBusy && !hasOverflows)
                return Math.min(_max, current + _step);

            // Low latency + no overflows + fast dispatch = decrease queue (save memory)
            if (lowLatency && !hasOverflows && !sustainedOverflows && !systemBusy && !dispatchSlow && current > _min)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    // ==================== CoDel Params ====================

    /**
     * Tunes CoDel target delay based on observed queue latency.
     * Lower target = more aggressive dropping, less bufferbloat.
     * Higher target = less dropping, more latency.
     * Uses codel.*.delay as the observed stat.
     */
    private class CodelTargetParam extends BaseParam {

        CodelTargetParam() {
            super("CODEL_TARGET",
                  "CoDel drop target",
                   SUB_CONGESTION, 1, 10, 1, "codel.UDP-Sender.delay", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig(
                Collections.singletonMap("router.codelTarget", String.valueOf(value)), null);
            CoDelBlockingQueue.updateAllTargets(value);
            CoDelPriorityBlockingQueue.updateAllTargets(value);
        }

        protected int getRuntimeValue() {
            return _context.getProperty("router.codelTarget", 5);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = codel.UDP-Sender.delay (avg queue sojourn time, ms)
            // Cross-refs: codel.UDP-Sender.drop.<priority> (drop events), jobLag (CPU)
            // Note: CoDel drop stats are per-priority; sum all priorities.
            double drops = getCoDelDropEventCount(_context, "codel.UDP-Sender.drop.");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean hasDrops = !Double.isNaN(drops) && drops > 5;

            // Delay approaching current target = tighten target for more aggressive dropping
            if (observed > current * 0.6 && hasDrops)
                return Math.max(_min, current - _step);

            // Active drops without high delay = early congestion signal, tighten
            if (hasDrops && observed > 2)
                return Math.max(_min, current - _step);

            // High system load = lower target to keep queues short under pressure
            if (highLoad && observed > 1)
                return Math.max(_min, current - _step);

            // Queue delay well under target (less than half) + no drops = healthy,
            // drift back toward default gradually (not toward max)
            int defaultVal = 5;
            if (observed < current * 0.3 && !hasDrops && !highLoad) {
                if (current > defaultVal)
                    return Math.max(defaultVal, current - _step);
                if (current < defaultVal)
                    return Math.min(defaultVal, current + _step);
            }

            return current;
        }
    }

    /**
     * Tunes CoDel interval based on observed queue sojourn time.
     * Shorter interval = faster reaction to congestion.
     * Longer interval = smoother behavior when delay is low.
     */
    private class CodelIntervalParam extends BaseParam {

        CodelIntervalParam() {
            super("CODEL_INTERVAL",
                  "CoDel measurement window",
                   SUB_CONGESTION, 20, 200, 10, "codel.UDP-Sender.delay", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig(
                Collections.singletonMap("router.codelInterval", String.valueOf(value)), null);
            CoDelBlockingQueue.updateAllIntervals(value);
            CoDelPriorityBlockingQueue.updateAllIntervals(value);
        }

        protected int getRuntimeValue() {
            return _context.getProperty("router.codelInterval", 50);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = codel.UDP-Sender.delay (avg queue sojourn time, ms)
            // Cross-refs: codel.UDP-Sender.drop.<priority> (drop events), jobLag (CPU)
            // Note: CoDel drop stats are per-priority; sum all priorities.
            double drops = getCoDelDropEventCount(_context, "codel.UDP-Sender.drop.");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean hasDrops = !Double.isNaN(drops) && drops > 5;

            // Active congestion with drops = shorten interval for faster reaction
            if (hasDrops && observed > 5)
                return Math.max(_min, current - _step);

            // High system load + any delay = shorten interval
            if (highLoad && observed > 2)
                return Math.max(_min, current - _step);

            // Well under default + no drops = drift back toward default (not toward max)
            int defaultVal = 50;
            if (observed < 2 && !hasDrops && !highLoad) {
                if (current > defaultVal)
                    return Math.max(defaultVal, current - _step);
                if (current < defaultVal)
                    return Math.min(defaultVal, current + _step);
            }

            return current;
        }
    }

    // ==================== Westwood Params ====================

    /**
     * Tunes Westwood+ EWMA decay factor based on bandwidth stability.
     * Lower factor = faster adaptation to bandwidth changes.
     * Higher factor = smoother estimates, less jitter.
     * Uses transport.sendProcessingTime as the observed stat (proxy for RTT stability).
     */
    private class WestwoodDecayFactorParam extends BaseParam {

        WestwoodDecayFactorParam() {
            super("WESTWOOD_DECAY_FACTOR",
                  "Westwood EWMA smoothing",
                   SUB_CONGESTION, 2, 16, 1, "transport.sendProcessingTime", _context);
        }

        protected void applyValue(int value) {
            SimpleBandwidthEstimator.setDecayFactor(value);
            // Also set on streaming estimator via reflection
            StreamingReflector.invokeSetInt("setDecayFactor", value);
        }

        protected int getRuntimeValue() {
            return SimpleBandwidthEstimator.getDecayFactor();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null)
                return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0)
                return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = transport.sendProcessingTime (ms, proxy for RTT variability)
            // Cross-refs: sendMessageFailureLifetime (congestion), jobLag (CPU)
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            // High send time variability + no congestion = lower factor (faster adaptation)
            if (observed > 300 && !congested && !systemBusy)
                return Math.max(_min, current - _step);

            // Low variability = raise factor (smoother EWMA estimates)
            if (observed < 200 && !congested)
                return Math.min(_max, current + _step);

            // During congestion, hold steady (don't change EWMA while network is unstable)
            return current;
        }
    }

    /**
     * SyntheticREDQueue minimum queue threshold (bytes).
     * Below this queue size, no packets are dropped.
     * Increase when healthy to reduce unnecessary drops; decrease under heavy congestion.
     */
    private class REDMinThresholdParam extends BaseParam {

        REDMinThresholdParam() {
            super("RED_MIN_THRESHOLD",
                  "RED min queue threshold",
                   SUB_CONGESTION, 1024, 65536, 512,
                   "bwLimiter.participatingBandwidthQueue", _context);
        }

        /**
         * Scale min/max/step with share bandwidth.
         * Default = bwBps / 4; range = [bwBps/16 .. bwBps].
         */
        @Override
        protected int getDefaultMin(RouterContext ctx) {
            return Math.max(1024, getShareBps(ctx) / 16);
        }

        @Override
        protected int getDefaultMax(RouterContext ctx) {
            return getShareBps(ctx);
        }

        @Override
        protected int getDefaultStep(RouterContext ctx) {
            return Math.max(256, getShareBps(ctx) / 256);
        }

        protected void applyValue(int value) {
            SyntheticREDQueue.updateAllMinThresholds(value);
        }

        protected int getRuntimeValue() {
            return SyntheticREDQueue.getCurrentMinThreshold();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = participating bandwidth queue (bytes) — growing = more transit load
            double drops = getAdditionalEventCount(_context, "tunnel.participatingMessageDropped");
            double overflow = getAdditionalEventCount(_context, "tunnel.dropGatewayOverflow");
            double codelDelay = getAdditionalStat(_context, "codel.OBGW.delay");

            boolean congested = (!Double.isNaN(drops) && drops > 10) ||
                                (!Double.isNaN(overflow) && overflow > 10);
            boolean hasCapacity = Double.isNaN(drops) || drops < 1;
            boolean lowDelay = !Double.isNaN(codelDelay) && codelDelay < 1;

            // Drops present + queue building: lower threshold (drop earlier to protect downstream)
            if (congested && observed > 1000)
                return Math.max(_min, current - _step);

            // No drops, low CoDel delay, queue building slowly: raise threshold (allow bursts)
            if (hasCapacity && lowDelay && observed < 500)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * SyntheticREDQueue maximum queue threshold (bytes).
     * Above this, ALL packets are dropped (100% drop probability).
     * Should scale with bandwidth and available memory.
     */
    private class REDMaxThresholdParam extends BaseParam {

        REDMaxThresholdParam() {
            super("RED_MAX_THRESHOLD",
                  "RED max queue threshold",
                   SUB_CONGESTION, 2048, 131072, 1024,
                   "bwLimiter.participatingBandwidthQueue", _context);
        }

        /**
         * Scale min/max/step with share bandwidth.
         * Default = bwBps / 2; range = [bwBps/8 .. bwBps*2].
         */
        @Override
        protected int getDefaultMin(RouterContext ctx) {
            return Math.max(2048, getShareBps(ctx) / 8);
        }

        @Override
        protected int getDefaultMax(RouterContext ctx) {
            return getShareBps(ctx) * 2;
        }

        @Override
        protected int getDefaultStep(RouterContext ctx) {
            return Math.max(512, getShareBps(ctx) / 128);
        }

        protected void applyValue(int value) {
            SyntheticREDQueue.updateAllMaxThresholds(value);
        }

        protected int getRuntimeValue() {
            return SyntheticREDQueue.getCurrentMaxThreshold();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            int minThreshold = getRuntimeValue(); // fallback
            try {
                // Read the current min threshold from the instance
                List<SyntheticREDQueue> instances = SyntheticREDQueue.getInstances();
                if (!instances.isEmpty()) {
                    minThreshold = instances.get(0).getMinThreshold();
                }
            } catch (Exception e) { /* use fallback */ }

            // Note: participatingMessageDropped is an event-count stat (addRateData(1));
            //       use getAdditionalEventCount() for the raw count.
            double drops = getAdditionalEventCount(_context, "tunnel.participatingMessageDropped");
            boolean hardDrops = !Double.isNaN(drops) && drops > 50;

            // Hard drops occurring: raise max threshold (allow deeper queue before 100% drop)
            if (hardDrops)
                return Math.min(_max, current + _step);

            // Queue healthy, no hard drops: lower max toward 2x min (tighter bound)
            if (Double.isNaN(drops) || drops == 0) {
                int target = Math.max(minThreshold * 2, _min);
                if (current > target)
                    return Math.max(target, current - _step);
            }

            return current;
        }
    }

    /**
     * SyntheticREDQueue maximum drop probability.
     * Controls how aggressively RED drops when queue is between min and max thresholds.
     * Higher = more aggressive drops under congestion; lower = gentler, buffer-heavy.
     */
    private class REDMaxDropProbParam extends BaseParam {

        REDMaxDropProbParam() {
            // Convert float probability to integer micro-units for BaseParam (long integer type)
            // 0.00005f = 50 micro-units, min 10 (0.0001%), max 1000 (0.1%), step 10
            super("RED_MAX_DROP_PROB",
                  "RED max drop probability",
                   SUB_CONGESTION, 10, 1000, 10,
                   "tunnel.participatingMessageDropped", _context);
        }

        protected void applyValue(int value) {
            float prob = value / 1_000_000.0f;
            SyntheticREDQueue.updateAllMaxDropProbability(prob);
        }

        protected int getRuntimeValue() {
            float prob = SyntheticREDQueue.getCurrentMaxDropProbability();
            return Math.round(prob * 1_000_000);
        }

        protected double getObservedStat(RouterContext ctx) {
            return getAdditionalEventCount(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.participatingMessageDropped event count
            double overflow = getAdditionalEventCount(_context, "tunnel.dropGatewayOverflow");
            double codelDelay = getAdditionalStat(_context, "codel.OBGW.delay");
            boolean systemBusy = !Double.isNaN(overflow) && overflow > 10;

            // Heavy drops + CoDel delay rising: increase drop probability (drop harder)
            if (!Double.isNaN(observed) && observed > 20 && !Double.isNaN(codelDelay) && codelDelay > 3)
                return Math.min(_max, current + _step);

            // No drops, queue healthy: decrease probability (buffer more gently)
            if ((Double.isNaN(observed) || observed == 0) && !systemBusy)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    // =====================================================================
    // Buffer & Thread params
    // =====================================================================

    /**
     * X25519 key precalculation minimum pool size.
     * Scales up when usage is high, scales down when idle.
     */
    private class XDHPreCalcMinParam extends BaseParam {

        XDHPreCalcMinParam() {
            super("crypto.x25519.precalcMin", "Min precomputed X25519 key pairs",
                  SUB_CRYPTO,

                  XDH_PRECALC_MIN, XDH_PRECALC_MAX, 8, "crypto.XDHUsed", _context);
        }

        protected void applyValue(int value) {
            X25519KeyFactory f = X25519KeyFactory.getInstance();
            if (f != null) f.setMinSize(value);
        }

        protected int getRuntimeValue() {
            X25519KeyFactory f = X25519KeyFactory.getInstance();
            return f != null ? f.getMinSize() : 0;
        }

        protected double getObservedStat(RouterContext ctx) {
            return getAdditionalEventCount(_context, _statName);
        }

        protected int computeTarget(double observed) {
            // Clamp to bounds first — config may have stale value beyond current range
            int current = Math.min(Math.max(getRuntimeValue(), _min), _max);
            // observed = crypto.XDHUsed event count (key consumption per period)
            // Cross-refs: crypto.XDHEmpty (empty pool events — the only trigger to grow),
            //             jobLag (CPU pressure), memory pressure, system load
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double empties = getAdditionalStat(_context, "crypto.XDHEmpty");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean poolEmptying = !Double.isNaN(empties) && empties > 0;

            // Severe memory pressure: shrink fast
            if (memPressure > 0.85)
                return Math.max(_min, current - _step * 2);

            // Pool is emptying under load = grow to absorb demand
            if (poolEmptying && !highLoad && memPressure < 0.7) {
                int step = (memPressure < 0.5 && sysLoad < 40) ? _step * 2 : _step;
                return Math.min(_max, current + step);
            }

            // Pool emptying under memory pressure = hold
            if (poolEmptying)
                return current;

            // Not emptying + idle or loaded = shrink toward factory default
            if (!poolEmptying && (highLoad || memPressure > 0.6 || systemBusy))
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * EDH (Elligator2) key precalculation minimum pool size.
     * Scales up only when pool is emptying, scales down when idle.
     */
    private class EDHPreCalcMinParam extends BaseParam {

        EDHPreCalcMinParam() {
            super("crypto.edh.precalcMin", "Min precomputed EDH key pairs",
                  SUB_CRYPTO,

                  EDH_PRECALC_MIN, EDH_PRECALC_MAX, 8, "crypto.EDHUsed", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("crypto.edh.precalc.min", Integer.toString(value));
            net.i2p.router.crypto.ratchet.Elg2KeyFactory f = net.i2p.router.crypto.ratchet.Elg2KeyFactory.getInstance();
            if (f != null) f.setMinSize(value);
        }

        protected int getRuntimeValue() {
            net.i2p.router.crypto.ratchet.Elg2KeyFactory f = net.i2p.router.crypto.ratchet.Elg2KeyFactory.getInstance();
            return f != null ? f.getMinSize() : 0;
        }

        protected double getObservedStat(RouterContext ctx) {
            return getAdditionalEventCount(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = Math.min(Math.max(getRuntimeValue(), _min), _max);
            // observed = crypto.EDHUsed event count (key consumption per period)
            // Cross-refs: crypto.EDHEmpty (empty pool events — the only trigger to grow),
            //             jobLag, memory pressure, system load
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double empties = getAdditionalStat(_context, "crypto.EDHEmpty");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean poolEmptying = !Double.isNaN(empties) && empties > 0;

            if (memPressure > 0.85)
                return Math.max(_min, current - _step * 2);

            if (poolEmptying && !highLoad && memPressure < 0.7) {
                int step = (memPressure < 0.5 && sysLoad < 40) ? _step * 2 : _step;
                return Math.min(_max, current + step);
            }

            if (poolEmptying)
                return current;

            if (!poolEmptying && (highLoad || memPressure > 0.6 || systemBusy))
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * ML-KEM key precalculation minimum pool size.
     */
    private class MLKEMPreCalcMinParam extends BaseParam {

        MLKEMPreCalcMinParam() {
            super("crypto.mlkem.precalcMin", "Min precomputed ML-KEM key pairs",
                  SUB_CRYPTO,

                  MLKEM_PRECALC_MIN, MLKEM_PRECALC_MAX, 64, "crypto.MLKEMEmpty", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("crypto.mlkem.precalc.min", Integer.toString(value));
            net.i2p.router.crypto.pqc.MLKEMKeyFactory f = net.i2p.router.crypto.pqc.MLKEMKeyFactory.getInstance();
            if (f != null) f.setMinSize(value);
        }

        protected int getRuntimeValue() {
            net.i2p.router.crypto.pqc.MLKEMKeyFactory f = net.i2p.router.crypto.pqc.MLKEMKeyFactory.getInstance();
            return f != null ? f.getMinSize() : 1;
        }

        protected double getObservedStat(RouterContext ctx) {
            return getAdditionalEventCount(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = Math.min(Math.max(getRuntimeValue(), _min), _max);
            // observed = crypto.MLKEMEmpty event count (queue empty events per period)
            // Cross-refs: crypto.MLKEMUsed (key consumption rate), jobLag (CPU), memory pressure, system load
            // Note: MLKEMUsed is an event-count stat (addRateData(1));
            //       use getAdditionalEventCount() for the raw count.
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double used = getAdditionalEventCount(_context, "crypto.MLKEMUsed");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            double memPressure = getMemoryPressure();
            boolean highUsage = !Double.isNaN(used) && used > 10;

            // Severe memory pressure: shrink fast
            if (memPressure > 0.85)
                return Math.max(_min, current - _step * 2);

            // Pool emptying under high usage = grow aggressively to absorb demand
            if (observed > 0 && highUsage && !highLoad && memPressure < 0.7)
                return Math.min(_max, current + _step * 2);

            // Pool emptying under load = grow to absorb demand
            if (observed > 0 && !highLoad && memPressure < 0.7) {
                int step = (memPressure < 0.5 && sysLoad < 40) ? _step * 2 : _step;
                return Math.min(_max, current + step);
            }

            // Pool emptying under memory pressure = hold
            if (observed > 0)
                return current;

            // Not emptying + idle or loaded = shrink toward factory default
            if (observed == 0 && (highLoad || memPressure > 0.6 || systemBusy))
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * NTCP send finisher queue capacity. Scales with send path load.
     *
     * <p>Primary signal: {@code ntcp.sendTime} (ms). Cross-refs:
     * {@code ntcp.writeQueueFull}, {@code ntcp.sendQueueSize},
     * {@code jobQueue.jobLag}.
     *
     * <p>Increases when send path is congested (high send time + queue
     * buildup). Decreases when send path is fast and queue is empty.
     *
     * @since 0.9.70+
     */
    private class NTCPQueueCapacityParam extends BaseParam {

        NTCPQueueCapacityParam() {
            super("ntcp.sendFinisher.queueCapacity", "NTCP send queue capacity",
                  SUB_TRANSPORT,

                  256, 16384, 256, "ntcp.sendTime", _context);
        }

        protected void applyValue(int value) {
            NTCPTransport.setSendFinisherQueueCapacity(value);
        }

        protected int getRuntimeValue() {
            return NTCPTransport.getSendFinisherQueueCapacity();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = ntcp.sendTime (ms, message lifetime in finisher pipeline)
            // Cross-refs: jobLag (CPU), transit OutBps (bandwidth load),
            //             ntcp.sendFinisher.queueSize, ntcp.writeQueueFull
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double transitBps = getAdditionalStat(_context, "tunnel.participating OutBps");
            double hourlyBps = getAdditionalStatHourly(_context, "tunnel.participating OutBps");
            double writeQueueFull = getAdditionalStat(_context, "ntcp.writeQueueFull");
            double finisherQ = getAdditionalStat(_context, "ntcp.sendFinisher.queueSize");
            double memPressure = getMemoryPressure();

            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > getHeavyTransitThreshold(_context);
            boolean sustainedHeavyTransit = !Double.isNaN(hourlyBps) && hourlyBps > getSustainedHeavyTransitThreshold(_context);
            boolean queueOverflow = !Double.isNaN(writeQueueFull) && writeQueueFull > 0;
            boolean sendSlow = observed > 100;
            boolean finisherBacklog = !Double.isNaN(finisherQ) && finisherQ > 200;

            // Send slow or queue full or finisher backlog + CPU headroom = increase
            if ((sendSlow || queueOverflow || finisherBacklog) && !systemBusy)
                return Math.min(_max, current + _step);

            // Proactive growth: heavy transit + headroom
            if (!systemBusy && memPressure < 0.7 && (heavyTransit || sustainedHeavyTransit))
                return Math.min(_max, current + _step);

            // Idle: send fast + no backlog + no transit = shrink
            if (observed < 30 && !queueOverflow && !finisherBacklog
                && !heavyTransit && !sustainedHeavyTransit && current > _min)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * UDP packet handler thread count. Dynamically adjusts based on
     * packet processing latency and transit load.
     *
     * <p>Primary signal: {@code udp.pushTime} (ms, time to push packet
     * to handler). Cross-refs: {@code tunnel.participating InBps},
     * {@code jobQueue.jobLag}, system load, memory pressure.
     *
     * <p>Increases when push time is high and CPU/system have headroom.
     * Decreases when push time is low and no heavy transit. Supports
     * dynamic thread addition/removal via {@link PacketHandler#adjustThreads()}.
     *
     * @since 0.9.70+
     */
    private class UDPHandlerThreadsParam extends BaseParam {

        UDPHandlerThreadsParam() {
            super("udp.packetHandler.maxThreads", "Max UDP packet handler threads",
                  SUB_TRANSPORT,

                  2, 16, 1, "udp.pushTime", _context);
        }

        protected void applyValue(int value) {
            UDPTransport.setPacketHandlerMaxThreads(value);
            Transport udp = _context.commSystem().getTransports().get(UDPTransport.STYLE);
            if (udp instanceof UDPTransport) ((UDPTransport) udp).adjustPacketHandlerThreads();
        }

        protected int getRuntimeValue() {
            return UDPTransport.getPacketHandlerMaxThreads();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.pushTime (avg time to push packet to handler, ms)
            // Cross-refs: transit InBps, jobLag (CPU), memory, msgRx.queueSize (downstream),
            //             udp.outboundQueueDepth (outbound pressure)
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyBps = getAdditionalStatHourly(_context, "tunnel.participating InBps");
            double msgRxQueue = getAdditionalStat(_context, "udp.msgRx.queueSize");
            double queueDepth = getAdditionalStat(_context, "udp.outboundQueueDepth");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();

            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > getHeavyTransitThreshold(_context);
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean sustainedHeavyTransit = !Double.isNaN(hourlyBps) && hourlyBps > getSustainedHeavyTransitThreshold(_context);
            boolean downstreamBackedUp = !Double.isNaN(msgRxQueue) && msgRxQueue > 50;
            boolean deepQueue = !Double.isNaN(queueDepth) && queueDepth > 2000;

            // Utilization: ratio of handlers actively processing vs max
            double utilization = getPacketHandlerUtilization(_context);
            boolean highUtilization = !Double.isNaN(utilization) && utilization > 0.8;
            boolean lowUtilization = !Double.isNaN(utilization) && utilization < 0.2;

            // Idle: shrink first — handlers keeping up fine means threads are wasted.
            // Transit volume is irrelevant for shrink; only handler load matters.
            if (observed < 1 && !downstreamBackedUp
                && lowUtilization && current > _min)
                return Math.max(_min, current - 1);

            // Deep outbound queue = add threads (process acks faster to drain)
            if (deepQueue && !systemBusy && !highLoad && memPressure < 0.7)
                return Math.min(_max, current + 1);

            // Any push time pressure + no CPU = add threads (max throughput)
            if (observed > 10 && !systemBusy && !highLoad)
                return Math.min(_max, current + 1);

            // Heavy transit + headroom = add threads proactively
            if (heavyTransit && !systemBusy && !highLoad && memPressure < 0.7)
                return Math.min(_max, current + 1);

            // High utilization + downstream pressure = add threads
            if (highUtilization && downstreamBackedUp && !systemBusy)
                return Math.min(_max, current + 1);

            // Downstream backed up = add threads to drain faster
            if (downstreamBackedUp && !systemBusy && !highLoad)
                return Math.min(_max, current + 1);

            return current;
        }
    }

    /**
     * Tunes UDP MessageReceiver thread count based on upstream pressure.
     * Higher thread count = more parallel message assembly = fewer expired messages.
     * Primary signal: codel.UDP-Receiver.delay (upstream queue sojourn time).
     * Cross-refs: udp.inboundExpired (messages dropped), codel.UDP-MessageReceiver.delay (sojourn).
     *
     * @since 0.9.70+
     */
    private class UDPMessageReceiverThreadsParam extends BaseParam {

        UDPMessageReceiverThreadsParam() {
            super("udp.messageReceiver.threads", "UDP message receiver threads",
                  SUB_TRANSPORT,

                  2, 16, 1, "codel.UDP-Receiver.delay", _context);
        }

        protected void applyValue(int value) {
            UDPTransport.setMessageReceiverThreads(value);
            Transport udp = _context.commSystem().getTransports().get(UDPTransport.STYLE);
            if (udp instanceof UDPTransport) ((UDPTransport) udp).adjustMessageReceiverThreads();
        }

        protected int getRuntimeValue() {
            return UDPTransport.getMessageReceiverThreads();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = codel.UDP-Receiver.delay (upstream queue sojourn time, ms)
            // Cross-refs: udp.inboundExpired, udp.msgRx.queueSize, codel.UDP-MessageReceiver.delay,
            //             jobLag (CPU), udp.pushTime (downstream handler pressure),
            //             udp.outboundQueueDepth (outbound pressure — process acks faster)
            double expired = getAdditionalStat(_context, "udp.inboundExpired");
            double queueSize = getAdditionalStat(_context, "udp.msgRx.queueSize");
            double sojournTime = getAdditionalStat(_context, "codel.UDP-MessageReceiver.delay");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double pushTime = getAdditionalStat(_context, "udp.pushTime");
            double queueDepth = getAdditionalStat(_context, "udp.outboundQueueDepth");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();

            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean upstreamBackpressure = observed > 5;
            boolean queueBackedUp = !Double.isNaN(queueSize) && queueSize > 50;
            boolean messagesExpiring = !Double.isNaN(expired) && expired > 0;
            boolean longSojourn = !Double.isNaN(sojournTime) && sojournTime > 2;
            boolean downstreamSlow = !Double.isNaN(pushTime) && pushTime > 10;
            boolean deepQueue = !Double.isNaN(queueDepth) && queueDepth > 2000;

            // Utilization: ratio of runners actively processing vs total
            double utilization = getMessageReceiverUtilization(_context);
            boolean highUtilization = !Double.isNaN(utilization) && utilization > 0.8;
            boolean lowUtilization = !Double.isNaN(utilization) && utilization < 0.2;

            // Deep outbound queue = process acks faster to free outbound messages
            if (deepQueue && !cpuPressure && !highLoad && memPressure < 0.7)
                return Math.min(_max, current + 1);

            // Any upstream pressure + no CPU = add threads (max throughput)
            if (upstreamBackpressure && !cpuPressure && !highLoad)
                return Math.min(_max, current + 1);

            // High utilization + queue backed up = add threads
            if (highUtilization && queueBackedUp && !cpuPressure)
                return Math.min(_max, current + 1);

            // Queue backed up + no CPU = add threads
            if (queueBackedUp && !cpuPressure && !highLoad)
                return Math.min(_max, current + 1);

            // Messages expiring + no CPU = add threads (loss = urgency)
            if (messagesExpiring && !cpuPressure && !highLoad)
                return Math.min(_max, current + 1);

            // Long sojourn + headroom = add threads proactively
            if (longSojourn && !cpuPressure && !highLoad && memPressure < 0.7)
                return Math.min(_max, current + 1);

            // Idle + no work = shrink (threads doing nothing useful)
            if (!upstreamBackpressure && !queueBackedUp && !messagesExpiring
                && !longSojourn && !downstreamSlow && observed < 1 && lowUtilization && current > _min)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * SSU2 sent-messages cleanup interval. Lower = more aggressive cleanup
     * of unacked packet tracking, preventing _sentMessages backlog.
     */
    private class SentMessagesCleanTimeParam extends BaseParam {

        SentMessagesCleanTimeParam() {
            super("udp.peer.sentMessagesCleanTime", "SSU2 sent-messages ACK sweep interval",
                  SUB_TRANSPORT,

                  2000, 300000, 5000, "udp.sentMessagesDepth", _context);
        }

        protected void applyValue(int value) {
            PeerState.setSentMessagesCleanTime(value);
        }

        protected int getRuntimeValue() {
            return (int) PeerState.getSentMessagesCleanTime();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sentMessagesDepth (avg pending-ACK entries across peers)
            // Cross-refs: udp.peerCount (total connections), udp.peerExpired (expiration rate),
            //             jobLag (CPU), tunnel.buildSuccessRate (network health)
            double peerCount = getAdditionalStat(_context, "udp.peerCount");
            double expired = getAdditionalStat(_context, "udp.peerExpired");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double memPressure = getMemoryPressure();
            boolean manyPeers = !Double.isNaN(peerCount) && peerCount > 500;
            boolean deepBacklog = !Double.isNaN(observed) && observed > 50;
            boolean lowExpiry = !Double.isNaN(expired) && expired < 10;
            boolean cpuFree = Double.isNaN(jobLag) || jobLag < 20;
            boolean memOk = memPressure < 0.75;
            boolean memCritical = memPressure > 0.85;

            // Memory critical: aggressive cleanup regardless
            if (memCritical)
                return Math.max(_min, current - _step * 2);

            // Deep backlog + low expiry = cleanup isn't keeping up, accelerate
            if (deepBacklog && lowExpiry && cpuFree && memOk)
                return Math.max(_min, current - _step);

            // Many peers + backlog = accelerate cleanup
            if (manyPeers && deepBacklog && cpuFree)
                return Math.max(_min, current - _step);

            // Clean backlog resolved, ease up to save CPU
            if (!deepBacklog && !manyPeers && current < _defaultValue)
                return Math.min(_defaultValue, current + _step);

            // Deep backlog but CPU pressure — can't clean faster without harming sends
            if (deepBacklog && !cpuFree)
                return current;

            return current;
        }
    }

    /**
     * Outbound message expiration timeout. Shorter = messages fail faster,
     * preventing OutboundMessageState + OutNetMessage pileup behind stuck peers.
     */
    private class OutboundMsgExpirationParam extends BaseParam {

        OutboundMsgExpirationParam() {
            super("udp.peer.outboundMsgExpiration", "Outbound message expiration timeout",
                  SUB_TRANSPORT,

                  5000, 300000, 5000, "udp.peerCount", _context);
        }

        protected void applyValue(int value) {
            PeerState.setOutboundMsgExpiration(value);
        }

        protected int getRuntimeValue() {
            return (int) PeerState.getOutboundMsgExpiration();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.peerCount (total SSU connections)
            // Cross-refs: udp.sentMessagesDepth, udp.peerExpired, memPressure, jobLag
            double sentDepth = getAdditionalStat(_context, "udp.sentMessagesDepth");
            double expired = getAdditionalStat(_context, "udp.peerExpired");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double memPressure = getMemoryPressure();
            boolean manyPeers = !Double.isNaN(observed) && observed > 500;
            boolean deepSent = !Double.isNaN(sentDepth) && sentDepth > 50;
            boolean highExpiry = !Double.isNaN(expired) && expired > 50;
            boolean cpuFree = Double.isNaN(jobLag) || jobLag < 20;
            boolean memOk = memPressure < 0.75;
            boolean memCritical = memPressure > 0.85;

            // Memory critical: shortest expiration to drain queues fast
            if (memCritical)
                return Math.max(_min, current - _step * 2);

            // Many peers + deep sent-messages = expire messages faster to shed load
            if (manyPeers && deepSent && cpuFree && memOk)
                return Math.max(_min, current - _step);

            // High expiry rate means messages are timing out anyway — accelerate
            if (highExpiry && cpuFree)
                return Math.max(_min, current - _step);

            // Back to normal: restore default
            if (!manyPeers && !deepSent && current < _defaultValue)
                return Math.min(_defaultValue, current + _step);

            return current;
        }
    }

    /**
     * Per-peer outbound message queue size.
     * Scales with peer count and network load.
     */
    private class PeerOutboundQueueParam extends BaseParam {

        PeerOutboundQueueParam() {
            super("router.peerOutboundQueueSize", "Max router outbound messages (per peer)",
                  SUB_ROUTER,

                  50, 1000, 50, "peer.activeProfileCount", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("router.peerOutboundQueueSize", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            int v = _context.getProperty("router.peerOutboundQueueSize", 0);
            return v > 0 ? v : _min;
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = peer.activeProfileCount (active profiles in RAM)
            // Cross-refs: udp.rejectConcurrentActive (rejections), jobLag, memory,
            //             udp.outboundQueueDepth (outbound pressure)
            double rejections = getAdditionalStat(_context, "udp.rejectConcurrentActive");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyRejections = getAdditionalStatHourly(_context, "udp.rejectConcurrentActive");
            double queueDepth = getAdditionalStat(_context, "udp.outboundQueueDepth");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();

            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasRejections = !Double.isNaN(rejections) && rejections > 0;
            boolean sustainedRejections = !Double.isNaN(hourlyRejections) && hourlyRejections > 5;
            boolean manyPeers = observed > 500;
            boolean deepQueue = !Double.isNaN(queueDepth) && queueDepth > 2000;

            // Deep queue + headroom = increase cap to avoid throttling
            if (deepQueue && !systemBusy && !highLoad && memPressure < 0.5)
                return Math.min(_max, current + _step);

            // Rejections + headroom = increase queue
            if ((hasRejections || sustainedRejections) && !systemBusy && !highLoad)
                return Math.min(_max, current + _step);

            // Many peers + no rejections + headroom: grow proactively
            if (manyPeers && !hasRejections && !systemBusy && !highLoad && memPressure < 0.5)
                return Math.min(_max, current + Math.max(1, _step / 2));

            // Few peers + no rejections + headroom = shrink (save memory)
            if (!manyPeers && !hasRejections && !systemBusy && !highLoad)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    // =====================================================================
    // Router Core params
    // =====================================================================

    /**
     * Transit message throttle factor.
     * Controls how aggressively transit messages are dropped under bandwidth pressure.
     */
    private class TransitThrottleFactorParam extends BaseParam {

        TransitThrottleFactorParam() {
            super("router.transitThrottleFactor", "Transit throttle aggressiveness",
                  SUB_ROUTER,

                  50, 100, 5, "tunnel.participating InBps", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("router.transitThrottleFactor", String.valueOf(value / 100.0f));
        }

        protected int getRuntimeValue() {
            float f = _context.getProperty("router.transitThrottleFactor", 0.95f);
            return (int)(f * 100);
        }

        protected double getObservedStat(RouterContext ctx) {
            // Primary: tunnel.participating InBps (transit bandwidth)
            // Fallback: transport.sendProcessingTime (latency proxy)
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs != null) {
                Rate rate = rs.getRate(STAT_PERIOD);
                if (rate != null && rate.getLastEventCount() > 0) return rate.getAverageValue();
            }
            RateStat fb = _context.statManager().getRate("transport.sendProcessingTime");
            if (fb != null) {
                Rate rate = fb.getRate(STAT_PERIOD);
                if (rate != null && rate.getLastEventCount() > 0) return rate.getAverageValue();
            }
            return Double.NaN;
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            int maxBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1024;
            if (maxBps <= 0) return current;

            // Check if primary stat (participating InBps) has events
            RateStat primaryRs = _context.statManager().getRate(_statName);
            Rate primaryRate = primaryRs != null ? primaryRs.getRate(STAT_PERIOD) : null;
            boolean hasTransitTraffic = primaryRate != null && primaryRate.getLastEventCount() > 0;

            double usagePct;
            if (hasTransitTraffic) {
                // observed = participating InBps; factor = fraction of transit KEPT
                usagePct = observed / maxBps;
            } else {
                // No transit traffic — observed is sendProcessingTime (latency)
                // Map latency to usage: <50ms = low usage, >200ms = high usage
                usagePct = observed < 50 ? 0.1 : observed < 200 ? 0.5 : 0.8;
            }

            // Cross-refs: buildSuccessRate (network health), sendMessageFailureLifetime (congestion),
            //             participatingMessageCountAvgPerTunnel (per-tunnel load)
            // Hourly trend: confirm bandwidth usage trend isn't just a short-term spike
            double buildSuccess = getBuildSuccessRate(_context);
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double hourlyBps = getAdditionalStatHourly(_context, _statName);
            double msgsPerTunnel = getAdditionalStat(_context, "tunnel.participatingMessageCountAvgPerTunnel");

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            // Confirm high usage is a sustained trend, not just a spike
            boolean sustainedHighUsage = !Double.isNaN(hourlyBps) && hourlyBps / maxBps > 0.6;
            boolean perTunnelHigh = !Double.isNaN(msgsPerTunnel) && msgsPerTunnel > 200;

            // High bandwidth usage + network healthy = increase throttle (manage load proactively)
            // Require sustained trend to avoid over-reacting to brief spikes
            if (usagePct > 0.7 && networkHealthy && sustainedHighUsage)
                return Math.min(_max, current + _step);

            // Per-tunnel load high + congestion = increase throttle (each tunnel is overloaded)
            if (perTunnelHigh && congested)
                return Math.min(_max, current + _step);

            // Low bandwidth usage = decrease throttle (we have room, accept more transit)
            if (usagePct < 0.3 && !congested && !perTunnelHigh)
                return Math.max(_min, current - _step);

            // Congested but low usage = don't change (something else is wrong)
            return current;
        }
    }

    /**
     * Tunnel rejection curve steepness. Higher exponent = sharper transition
     * from accepting to rejecting.
     *
     * <p>Primary signal: {@code tunnel.buildSuccessRate}. Cross-refs:
     * {@code tunnel.concurrentBuilds}, {@code transport.sendMessageFailureLifetime}.
     *
     * <p>Increases when build success drops (reject tunnels more aggressively).
     * Decreases when builds succeed consistently (accept more tunnels).
     *
     * @since 0.9.70+
     */
    private class ThrottleRejectExponentParam extends BaseParam {

        ThrottleRejectExponentParam() {
            super("router.throttleRejectExponent", "Rejection curve steepness",
                  SUB_ROUTER,

                  1, 50, 1, "tunnel.buildSuccessRate", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("router.throttleRejectExponent", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("router.throttleRejectExponent", 10);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            // Stat emitted as 0-100; normalize to 0-1 for threshold comparisons
            return rate.getAverageValue() / 100.0;
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = build success ratio (0.0-1.0)
            // Cross-refs: concurrentBuilds (storm), participating InBps (load)
            // Hourly trend: confirm build success trend isn't just a short-term dip
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double hourlySuccess = getAdditionalStatHourly(_context, _statName) / 100.0;

            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;
            boolean heavyLoad = !Double.isNaN(transitBps) && transitBps > 80000;
            // Confirm sustained low success rate
            boolean sustainedLowSuccess = !Double.isNaN(hourlySuccess) && hourlySuccess < 0.6;

            // Build storm = hold steady (don't change rejection curve mid-storm)
            if (buildStorm) return current;

            // High success + low load = increase exponent (sharper cutoff, can be aggressive)
            if (observed > 0.9 && !heavyLoad)
                return Math.min(_max, current + _step);

            // Low success = decrease exponent (gentler rejection curve, less abrupt)
            // Require sustained trend to avoid over-reacting to brief dips
            if (observed < 0.5 && sustainedLowSuccess)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Per-tunnel BWE divisor.
     * Controls how much bandwidth each transit tunnel gets.
     * Lower divisor = more bandwidth per tunnel = more aggressive transit.
     * Higher divisor = less bandwidth per tunnel = conservative transit.
     * Auto-scales with capacity: lower when spare, higher when near limits.
     */
    private class PerTunnelBweDivisorParam extends BaseParam {

        PerTunnelBweDivisorParam() {
            super("router.tunnel.perTunnelBweDivisor", "Per-tunnel bandwidth divisor",
                  SUB_TUNNEL,

                  2, 1000, 10, "tunnel.participating InBps", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("router.tunnel.perTunnelBweDivisor", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            int configured = _context.getProperty("router.tunnel.perTunnelBweDivisor", 0);
            if (configured > 0) return configured;
            // Default: min(maxTunnels, 100) — the old hardcoded value
            int maxTunnels = _context.getProperty(RouterThrottleImpl.PROP_MAX_TUNNELS,
                                                  RouterThrottleImpl._defaultMaxTunnels);
            return Math.min(maxTunnels, 100);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            int numTunnels = _context.tunnelManager().getParticipatingCount();
            int maxTunnels = _context.getProperty(RouterThrottleImpl.PROP_MAX_TUNNELS,
                                                  RouterThrottleImpl._defaultMaxTunnels);
            int maxBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1024;
            if (maxBps <= 0) return current;
            double usagePct = observed / maxBps;

            double buildSuccess = getBuildSuccessRate(_context);
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;

            // Scale divisor with actual tunnel count: share / numTunnels
            // When spare capacity: lower divisor = each tunnel gets more bandwidth
            if (usagePct < 0.1 && networkHealthy && !congested && !systemBusy && numTunnels > 0) {
                // Target: share / numTunnels (each tunnel gets fair share)
                int targetDivisor = Math.max(10, Math.min(_max, maxBps / Math.max(numTunnels, 1)));
                if (targetDivisor < current)
                    return Math.max(_min, current - _step);
                if (targetDivisor > current)
                    return Math.min(_max, current + _step);
                return current;
            }

            // Heavy load: increase divisor (each tunnel gets less)
            // But NOT during build storms — congestion is temporary, divisor will recover
            if ((usagePct > 0.5 || congested || systemBusy) && !buildStorm)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Tunnel growth factor for probabilistic acceptance.
     * Higher factor = more tunnels accepted before throttling kicks in.
     * Lower factor = throttle sooner when growing fast.
     * Relaxes when capacity is available.
     */
    private class TunnelGrowthFactorParam extends BaseParam {

        TunnelGrowthFactorParam() {
            super("router.tunnelGrowthFactor", "Tunnel growth tolerance",
                  SUB_TUNNEL,

                  10, 100, 5, "tunnel.participating InBps", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("router.tunnelGrowthFactor", String.valueOf(value / 10.0d));
        }

        protected int getRuntimeValue() {
            double factor = 2.0d;
            String p = _context.getProperty("router.tunnelGrowthFactor");
            if (p != null) {
                try { factor = Double.parseDouble(p); } catch (NumberFormatException nfe) {}
            }
            return (int)(factor * 10);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            int maxBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1024;
            if (maxBps <= 0) return current;
            double usagePct = observed / maxBps;

            double buildSuccess = getBuildSuccessRate(_context);
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            // Spare capacity + healthy: increase factor (accept more aggressively)
            if (usagePct < 0.1 && networkHealthy && !congested && !systemBusy)
                return Math.min(_max, current + _step);

            // Heavy load or congested: decrease factor (throttle sooner)
            if (usagePct > 0.5 || congested || systemBusy)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Max participating tunnels.
     * Master knob for transit capacity.
     */
    private class MaxParticipatingTunnelsParam extends BaseParam {

        MaxParticipatingTunnelsParam() {
            super("router.maxParticipatingTunnels", "Max transit tunnels",
                  SUB_ROUTER,

                  500, 20000, 500, "tunnel.participating InBps", _context);
        }

        protected void applyValue(int value) {
            RouterThrottleImpl.setDefaultMaxTunnels(value);
            _context.router().saveConfig("router.maxParticipatingTunnels", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return RouterThrottleImpl.getDefaultMaxTunnels();
        }

        protected double getObservedStat(RouterContext ctx) {
            // Primary: tunnel.participating InBps
            // Fallback: transport.sendProcessingTime
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs != null) {
                Rate rate = rs.getRate(STAT_PERIOD);
                if (rate != null && rate.getLastEventCount() > 0) return rate.getAverageValue();
            }
            RateStat fb = _context.statManager().getRate("transport.sendProcessingTime");
            if (fb != null) {
                Rate rate = fb.getRate(STAT_PERIOD);
                if (rate != null && rate.getLastEventCount() > 0) return rate.getAverageValue();
            }
            return Double.NaN;
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            int maxBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1024;
            if (maxBps <= 0) {return current;}

            // Use actual tunnel count vs max as the trigger — not bandwidth.
            Rate avgTunnels = _context.statManager().getRate("tunnel.participatingTunnels") != null
                ? _context.statManager().getRate("tunnel.participatingTunnels").getRate(RateConstants.TEN_MINUTES)
                : null;
            double tunnelCount = (avgTunnels != null && avgTunnels.getLastEventCount() > 0)
                ? avgTunnels.getAverageValue() : 0;
            double countPct = current > 0 ? tunnelCount / current : 0;

            // Cross-refs: buildSuccessRate, sendMessageFailureLifetime, jobLag
            double buildSuccess = getBuildSuccessRate(_context);
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double memPressure = getMemoryPressure();

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            // Transit tunnels are provisioned by peers — max is capacity, not a target.
            // Scale up when approaching capacity and healthy.
            if (countPct > 0.7 && networkHealthy && !congested && !systemBusy) {
                return Math.min(_max, current + _step * 2);
            }
            if (countPct > 0.5 && networkHealthy && !congested && !systemBusy) {
                return Math.min(_max, current + _step);
            }

            // Only scale down under extreme memory pressure — never for low utilization
            if (memPressure > 0.9) {
                return Math.max(_min, current - _step);
            }

            return current;
        }
    }

    /**
     * Build handler max queue size.
     * Overflow drops tunnel build requests.
     */
    private class BuildHandlerMaxQueueParam extends BaseParam {

        BuildHandlerMaxQueueParam() {
            super("router.buildHandlerMaxQueue", "Tunnel build handler queue",
                  SUB_ROUTER,

                  64, 2048, 32, "jobQueue.jobLag", _context);
        }

        protected void applyValue(int value) {
            BuildHandler.setMaxQueue(value);
        }

        protected int getRuntimeValue() {
            return BuildHandler.getMaxQueue();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = jobQueue.jobLag (ms)
            // Cross-refs: buildSuccessRate, concurrentBuilds, memory
            double buildSuccess = getBuildSuccessRate(_context);
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double hourlyLag = getAdditionalStatHourly(_context, _statName);
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;
            boolean sustainedHighLag = !Double.isNaN(hourlyLag) && hourlyLag > 500;

            // Build storm: hold steady
            if (observed > 1000 && buildStorm) return current;

            // High lag + degraded builds + sustained = increase queue
            if (observed > 1000 && !networkHealthy && sustainedHighLag && !highLoad)
                return Math.min(_max, current + _step);

            // Proactive: builds happening + headroom + healthy = grow buffer for spikes
            if (!buildStorm && networkHealthy && !highLoad && memPressure < 0.5
                && observed < 500)
                return Math.min(_max, current + Math.max(1, _step / 2));

            // Shrink only when truly idle or under real pressure
            if (observed < 200 && networkHealthy && !buildStorm && !highLoad && memPressure < 0.6)
                return Math.max(_min, current - _step);

            if (highLoad && observed < 500)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Good deficit throttle interval.
     * Minimum time between rebuilds when pools are healthy.
     */
    private class GoodDeficitThrottleParam extends BaseParam {

        GoodDeficitThrottleParam() {
            super("i2p.tunnel.goodDeficitThrottle", "Rebuild throttle interval (ms)",
                  SUB_ROUTER,

                  1000, 60000, 5000, "tunnel.buildSuccessRate", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.tunnel.goodDeficitThrottle", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return (int) BuildExecutor.getGoodDeficitThrottle(_context);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            // Stat emitted as 0-100; normalize to 0-1 for threshold comparisons
            return rate.getAverageValue() / 100.0;
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.buildSuccessRate (normalized 0.0-1.0)
            // Cross-refs: concurrentBuilds (storm), participating InBps (load),
            //             participatingMessageCountAvgPerTunnel (per-tunnel load)
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double msgsPerTunnel = getAdditionalStat(_context, "tunnel.participatingMessageCountAvgPerTunnel");

            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;
            boolean heavyLoad = !Double.isNaN(transitBps) && transitBps > 80000;
            boolean perTunnelHigh = !Double.isNaN(msgsPerTunnel) && msgsPerTunnel > 200;

            // Build storm + low success = increase throttle (back off, let congestion clear)
            if (buildStorm && observed < 0.7)
                return Math.min(_max, current + _step);

            // Build storm but good success = hold (throttle is working)
            if (buildStorm) return current;

            // Low success + light load = increase throttle (conservative, avoid transport burn)
            if (observed < 0.7 && !heavyLoad && !perTunnelHigh)
                return Math.min(_max, current + _step);

            // High success + low load = decrease throttle (builds are cheap, replenish quickly)
            if (observed > 0.95 && !heavyLoad && !perTunnelHigh)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    // =====================================================================
    // Streaming congestion params
    // =====================================================================

    /**
     * Max retransmission timeout. Caps the upper bound for streaming RTO.
     *
     * <p>Primary signal: {@code stream.con.initialRTT.out} with
     * {@code udp.sendConfirmTime} fallback. Cross-refs:
     * {@code stream.con.sendDuplicateSize}, {@code stream.con.lifetimeRTT}.
     *
     * <p>Increases when duplicates detected (congestion). Decreases when
     * connection is healthy with low loss.
     *
     * @since 0.9.70+
     */
    private class MaxRTOParam extends BaseParam {

        MaxRTOParam() {
            super("i2p.streaming.maxRTO", "Streaming max RTO (ms)",
                  SUB_STREAMING,

                  1000, 30000, 1000, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            StreamingConnectionReflector.invokeConnectionOptionsSet("setMaxRTO", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingConnectionReflector.invokeConnectionOptionsInt("getMaxRTOStatic");
            return v > 0 ? v : 30000;
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (ms, actual network RTT)
            // Cross-refs: stream.con.sendDuplicateSize (retransmit pressure),
            //             transport.sendMessageFailureLifetime (congestion)
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean highRTT = !Double.isNaN(observed) && observed > 20000;
            boolean highDups = !Double.isNaN(dupSize) && dupSize > 1000;

            // High retransmit pressure + high RTT = raise RTO ceiling (allow more headroom)
            if (highDups && highRTT && !congested)
                return Math.min(_max, current + _step);

            // Congestion + high RTT = raise ceiling (slow pipe needs larger timeout)
            if (congested && highRTT)
                return Math.min(_max, current + _step);

            // Low RTT + no dups + no congestion = tighten loss detection
            if (observed < 5000 && !congested && !highDups)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Max delay between retransmissions.
     * Controls how long to wait before retrying a lost packet.
     */
    private class MaxResendDelayParam extends BaseParam {

        MaxResendDelayParam() {
            super("i2p.streaming.maxResendDelay", "Streaming max resend delay (ms)",
                  SUB_STREAMING,

                  1000, 30000, 1000, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            StreamingConnectionReflector.invokeConnectionOptionsSet("setMaxResendDelay", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingConnectionReflector.invokeConnectionOptionsInt("getMaxResendDelayStatic");
            return v > 0 ? v : 30000;
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (ms, actual network RTT)
            // Cross-refs: stream.con.sendDuplicateSize (retransmit pressure),
            //             stream.sendsBeforeAck (ACK efficiency),
            //             transport.sendMessageFailureLifetime (congestion)
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");
            double sendsBeforeAck = getAdditionalStat(_context, "stream.sendsBeforeAck");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean highRTT = !Double.isNaN(observed) && observed > 20000;
            boolean highDups = !Double.isNaN(dupSize) && dupSize > 500;
            boolean inefficientAck = !Double.isNaN(sendsBeforeAck) && sendsBeforeAck > 8;

            // Spurious retransmits (high dups) + slow network = raise delay (stop hammering)
            if (highDups && highRTT)
                return Math.min(_max, current + _step);

            // Inefficient ACKs + congestion = raise delay (ACKs are slow)
            if (inefficientAck && congested)
                return Math.min(_max, current + _step);

            // Low RTT + no spurious retransmits + no congestion = lower delay (fast recovery)
            if (observed < 5000 && !highDups && !congested)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Max retransmissions before giving up.
     * Controls how many times to retry a lost packet before abandoning the stream.
     */
    private class MaxRetransmissionsParam extends BaseParam {

        MaxRetransmissionsParam() {
            super("i2p.streaming.maxRetransmissions", "Streaming max retransmissions",
                  SUB_STREAMING,

                  8, 16, 2, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            StreamingConnectionReflector.invokeConnectionSet("setMaxRetransmissions", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingConnectionReflector.invokeConnectionInt("getMaxRetransmissionsStatic");
            return v > 0 ? v : 64;
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (ms, actual network RTT)
            // Cross-refs: stream.con.sendDuplicateSize (retransmit volume),
            //             stream.con.lifetimeSendWindowSize (connection health),
            //             transport.sendMessageFailureLifetime (congestion)
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");
            double lifetimeWindowSize = getAdditionalStat(_context, "stream.con.lifetimeSendWindowSize");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean highRTT = !Double.isNaN(observed) && observed > 20000;
            boolean retransmitsWorking = !Double.isNaN(dupSize) && dupSize > 0;
            boolean healthyWindow = !Double.isNaN(lifetimeWindowSize) && lifetimeWindowSize > 32;

            // Healthy stream + retransmits working + no congestion = allow more retries
            if (healthyWindow && retransmitsWorking && !congested)
                return Math.min(_max, current + _step);

            // High RTT + no congestion = allow more retries (slow pipe needs persistence)
            if (highRTT && !congested)
                return Math.min(_max, current + _step);

            // Low RTT + fast network + congestion = fewer retries needed
            if (observed < 5000 && congested)
                return Math.max(_min, current - _step);

            // Low RTT + no retransmit success + congestion = give up sooner
            if (observed < 5000 && !retransmitsWorking && congested)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Min delay between retransmissions.
     * Lower = faster loss detection, higher = avoid spurious retransmits.
     */
    private class MinResendDelayParam extends BaseParam {

        MinResendDelayParam() {
            super("i2p.streaming.minResendDelay", "Streaming min resend delay (ms)",
                  SUB_STREAMING,

                  100, 5000, 50, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            StreamingConnectionReflector.invokeConnectionOptionsSet("setMinResendDelay", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingConnectionReflector.invokeConnectionOptionsInt("getMinResendDelayStatic");
            return v > 0 ? v : 100;
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (ms, actual network RTT)
            // Cross-refs: stream.con.sendDuplicateSize (spurious retransmit rate),
            //             stream.sendsBeforeAck (ACK efficiency),
            //             transport.sendMessageFailureLifetime (congestion)
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");
            double sendsBeforeAck = getAdditionalStat(_context, "stream.sendsBeforeAck");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean spuriousFlood = !Double.isNaN(dupSize) && dupSize > 2000;
            boolean highRTT = !Double.isNaN(observed) && observed > 20000;
            boolean inefficientAck = !Double.isNaN(sendsBeforeAck) && sendsBeforeAck > 8;

            // Spurious flood = raise min delay (stop hammering the pipe)
            if (spuriousFlood)
                return Math.min(_max, current + _step);

            // Congestion + high RTT = raise min delay
            if (congested && highRTT)
                return Math.min(_max, current + _step);

            // Efficient ACKing + low RTT + no congestion = lower min delay
            if (!inefficientAck && !highRTT && !congested && !spuriousFlood)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Congestion avoidance growth rate factor.
     * Higher = more aggressive window growth in steady state.
     * Primary signal: stream.con.sendDuplicateSize (retransmit pressure).
     */
    private class CongestionAvoidanceGrowthParam extends BaseParam {

        CongestionAvoidanceGrowthParam() {
            super("i2p.streaming.congestionAvoidanceGrowthRateFactor", "Streaming CA growth rate",
                  SUB_STREAMING,

                  1, 16, 1, "stream.con.sendDuplicateSize", _context);
        }

        protected void applyValue(int value) {
            // Invert: Tuner treats higher=more aggressive, but code treats higher=slower
            _context.router().saveConfig("i2p.streaming.congestionAvoidanceGrowthRateFactor",
                                         Integer.toString(_min + _max - value));
        }

        protected int getRuntimeValue() {
            int codeVal = _context.getProperty("i2p.streaming.congestionAvoidanceGrowthRateFactor", 1);
            return Math.max(_min, Math.min(_max, _min + _max - codeVal));
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = stream.con.sendDuplicateSize (retransmit volume)
            // Cross-refs: sendMessageFailureLifetime (congestion), buildSuccessRate (network health),
            //             lifetimeRTT (completed stream RTT), lifetimeSendWindowSize (final window),
            //             chokeSizeBegin (choke pressure), windowSizeAtCongestion (CWIN at dup)
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double buildSuccess = getBuildSuccessRate(_context);
            double lifetimeRTT = getAdditionalStat(_context, "stream.con.lifetimeRTT");
            double lifetimeWindowSize = getAdditionalStat(_context, "stream.con.lifetimeSendWindowSize");
            double chokeSize = getAdditionalStat(_context, "stream.chokeSizeBegin");
            double congWindowSize = getAdditionalStat(_context, "stream.con.windowSizeAtCongestion");
            boolean windowsCongesting = !Double.isNaN(congWindowSize) && congWindowSize < 5;

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean dropping = observed > 500;
            boolean streamsSlow = !Double.isNaN(lifetimeRTT) && lifetimeRTT > 5000;
            boolean windowsSmall = !Double.isNaN(lifetimeWindowSize) && lifetimeWindowSize < 4;
            boolean choking = !Double.isNaN(chokeSize) && chokeSize > 5;

            // Recovery floor: if below 50% of default, always increase back toward default
            int recoveryFloor = Math.max(_min, _defaultValue / 2);
            if (current < recoveryFloor && !congested)
                return Math.min(_defaultValue, current + _step);

            // Drops or congestion = slow growth (loss minimization)
            if (dropping || congested || windowsCongesting)
                return Math.max(recoveryFloor, current - _step);

            // Network unhealthy = slow growth
            if (!networkHealthy)
                return Math.max(recoveryFloor, current - _step);

            // Choking = decrease growth (too aggressive)
            if (choking)
                return Math.max(recoveryFloor, current - _step);

            // Dead zone: hold within 50%-200% of default unless signal is strong
            if (current >= recoveryFloor && current <= _defaultValue * 2 && !dropping && !congested && !windowsCongesting && networkHealthy)
                return current;

            // Completed streams slow + windows small = increase growth (need faster ramp)
            if (streamsSlow && windowsSmall && !dropping && !congested && !windowsCongesting)
                return Math.min(_max, current + _step);

            // Large window at congestion + healthy network + no drops = increase growth
            if (!Double.isNaN(lifetimeWindowSize) && lifetimeWindowSize > 20 && networkHealthy && !dropping && !congested && !windowsCongesting)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Slow start growth rate factor.
     * Higher = more aggressive ramp-up during slow start.
     * Dead zone: hold at default unless signal is strong. This prevents
     * the classic ping-pong where RTT fluctuates around the threshold.
     */
    private class SlowStartGrowthParam extends BaseParam {

        SlowStartGrowthParam() {
            super("i2p.streaming.slowStartGrowthRateFactor", "Streaming SS growth rate",
                  SUB_STREAMING,

                  1, 16, 1, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            // Invert: Tuner treats higher=more aggressive, but code treats higher=slower
            _context.router().saveConfig("i2p.streaming.slowStartGrowthRateFactor",
                                         Integer.toString(_min + _max - value));
        }

        protected int getRuntimeValue() {
            int codeVal = _context.getProperty("i2p.streaming.slowStartGrowthRateFactor", 1);
            return Math.max(_min, Math.min(_max, _min + _max - codeVal));
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = stream.con.initialRTT.out (RTT, ms)
            // Cross-refs: buildSuccessRate (network health), sendMessageFailureLifetime (congestion),
            //             sendDuplicateSize (drops!), lifetimeRTT (completed stream RTT),
            //             lifetimeSendWindowSize (final window size at stream close),
            //             chokeSizeBegin (choke pressure)
            double buildSuccess = getBuildSuccessRate(_context);
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");
            double lifetimeRTT = getAdditionalStat(_context, "stream.con.lifetimeRTT");
            double lifetimeWindowSize = getAdditionalStat(_context, "stream.con.lifetimeSendWindowSize");
            double chokeSize = getAdditionalStat(_context, "stream.chokeSizeBegin");
            double congWindowSize = getAdditionalStat(_context, "stream.con.windowSizeAtCongestion");
            boolean windowsCongesting = !Double.isNaN(congWindowSize) && congWindowSize < 5;

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean dropping = !Double.isNaN(dupSize) && dupSize > 500;
            boolean streamsSlow = !Double.isNaN(lifetimeRTT) && lifetimeRTT > 5000;
            boolean windowsSmall = !Double.isNaN(lifetimeWindowSize) && lifetimeWindowSize < 4;
            boolean choking = !Double.isNaN(chokeSize) && chokeSize > 5;

            // Recovery floor: if below 50% of default, always increase back toward default
            int recoveryFloor = Math.max(_min, _defaultValue / 2);
            if (current < recoveryFloor && !congested)
                return Math.min(_defaultValue, current + _step);

            // Drops or congestion = slow ramp (loss minimization)
            if (dropping || congested || windowsCongesting)
                return Math.max(recoveryFloor, current - _step);

            // Network unhealthy = slow ramp
            if (!networkHealthy)
                return Math.max(recoveryFloor, current - _step);

            // Choking = decrease ramp (too aggressive)
            if (choking)
                return Math.max(recoveryFloor, current - _step);

            // Dead zone: hold within 50% of default unless signal is strong
            if (current >= recoveryFloor && current <= _defaultValue * 2 && !dropping && !congested && !windowsCongesting && networkHealthy)
                return current;

            // Completed streams slow + windows small = increase ramp (need faster ramp)
            if (streamsSlow && windowsSmall && !dropping && !congested && !windowsCongesting)
                return Math.min(_max, current + _step);

            // Below default: increase if healthy
            if (current < _defaultValue && networkHealthy && !dropping && !congested && !windowsCongesting)
                return Math.min(_max, current + _step);

            // Above default: decrease if RTT is high OR network unhealthy
            if (current > _defaultValue && (observed > 8000 || !networkHealthy))
                return Math.max(recoveryFloor, current - _step);

            return current;
        }
    }

    /**
     * Caps the RTT estimate to prevent pathological values from breaking RTO.
     * I2P typically has 2-10s RTT. Lower cap = tighter RTO bounds.
     */
    private class MaxRttParam extends BaseParam {

        MaxRttParam() {
            super("i2p.streaming.maxRtt", "Streaming RTT cap (ms)",
                  SUB_STREAMING,

                  500, 30000, 1000, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.maxRtt", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.maxRtt", 60000);
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (ms, actual network RTT)
            // Cross-refs: stream.con.sendDuplicateSize (retransmit pressure),
            //             transport.sendMessageFailureLifetime (congestion)
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean highRTT = !Double.isNaN(observed) && observed > 20000;
            boolean highDups = !Double.isNaN(dupSize) && dupSize > 1000;

            // Spurious retransmits + congestion = raise cap (RTT spikes are real)
            if (highDups && congested)
                return Math.min(_max, current + _step);

            // High RTT = raise cap (need headroom)
            if (highRTT)
                return Math.min(_max, current + _step);

            // Low RTT + no dups + no congestion = tighten cap
            if (observed < 5000 && !highDups && !congested)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Delay before the first retransmission of an unACKed packet.
     * Lower = faster loss detection, higher = avoid spurious retransmits on slow pipes.
     */
    private class InitialResendDelayParam extends BaseParam {

        InitialResendDelayParam() {
            super("i2p.streaming.initialResendDelay", "Streaming initial resend delay (ms)",
                  SUB_STREAMING,

                  100, 5000, 50, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.initialResendDelay", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.initialResendDelay", 1000);
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = stream.con.initialRTT.out (RTT, ms)
            // Cross-refs: sendsBeforeAck (ACK efficiency),
            //             sendDuplicateSize (spurious retransmit rate),
            //             sendMessageFailureLifetime (congestion)
            double sendsBeforeAck = getAdditionalStat(_context, "stream.sendsBeforeAck");
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            // High sends-before-ACK = ACKs are slow, retransmits may be spurious
            boolean slowAcks = !Double.isNaN(sendsBeforeAck) && sendsBeforeAck > 8;
            // High dups = retransmits are happening when they shouldn't
            boolean spuriousRetransmits = !Double.isNaN(dupSize) && dupSize > 2000;

            // Spurious retransmits or slow ACKs = raise delay (avoid hammering the pipe)
            if (spuriousRetransmits || (slowAcks && congested))
                return Math.min(_max, current + _step);

            // Low RTT + efficient ACKs + no spurious retransmits = lower delay (fast loss recovery)
            if (observed < 10000 && !slowAcks && !spuriousRetransmits && !congested)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Delay before sending an ACK for a duplicate or out-of-order packet.
     * Lower = faster sender feedback, higher = allow piggybacking on data.
     */
    private class ImmediateAckDelayParam extends BaseParam {

        ImmediateAckDelayParam() {
            super("i2p.streaming.immediateAckDelay", "Streaming dup ACK delay (ms)",
                  SUB_STREAMING,

                  1, 1000, 10, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.immediateAckDelay", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.immediateAckDelay",
                                        SystemVersion.isSlow() ? 100 : 80);
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = stream.con.initialRTT.out (RTT, ms)
            // Cross-refs: sendDuplicateSize (retransmit pressure),
            //             sendMessageSize (throughput opportunity),
            //             sendMessageFailureLifetime (congestion)
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");
            double msgSize = getAdditionalStat(_context, "stream.con.sendMessageSize");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            // High dups = sender is retransmitting = needs faster ACK feedback
            boolean retransmitPressure = !Double.isNaN(dupSize) && dupSize > 1000;
            // Large messages = more data to piggyback ACKs on
            boolean largeMessages = !Double.isNaN(msgSize) && msgSize > 2000;

            // Retransmit pressure = lower delay (sender needs to know packets arrived)
            if (retransmitPressure && !congested)
                return Math.max(_min, current - _step);

            // Large messages + no congestion = raise delay (allow ACK piggybacking)
            if (largeMessages && !congested)
                return Math.min(_max, current + _step);

            // Congestion = raise delay (don't add ACK-only packets to congested pipe)
            if (congested)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Inactivity timeout for streaming connections.
     * Higher values keep slow transfers alive; lower values free resources faster.
     * Primary signal: udp.sendConfirmTime (transport RTT).
     * Cross-refs: stream.con.lifetimeRTT (completed stream RTT),
     *             stream.con.sendDuplicateSize (retransmit pressure).
     */
    private class InactivityTimeoutParam extends BaseParam {

        InactivityTimeoutParam() {
            super("i2p.streaming.inactivityTimeout", "Streaming inactivity timeout (ms)",
                  SUB_STREAMING,

                  120000, 600000, 30000, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.inactivityTimeout", Integer.toString(value));
            StreamingReflector.invokeSetInt("setDefaultInactivityTimeout", value);
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.inactivityTimeout", 300000);
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (ms, actual network RTT)
            // Cross-refs: stream.con.lifetimeRTT (slow pipe indicator),
            //             stream.con.sendDuplicateSize (retransmit pressure)
            double lifetimeRTT = getAdditionalStat(_context, "stream.con.lifetimeRTT");
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");

            boolean highRTT = !Double.isNaN(observed) && observed > 20000;
            boolean streamsSlow = !Double.isNaN(lifetimeRTT) && lifetimeRTT > 8000;
            boolean congested = !Double.isNaN(dupSize) && dupSize > 1000;

            // High RTT or slow streams = raise timeout (avoid killing slow transfers)
            if (highRTT || (streamsSlow && !congested))
                return Math.min(_max, current + _step);

            // Low RTT + no congestion = tighten timeout (faster resource reclamation)
            if (observed < 5000 && !congested)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    // =====================================================================
    // NetDB params
    // =====================================================================

    /**
     * NetDB search limit (max peers per iterative search).
     * Scales with network latency.
     */
    private class NetDBSearchLimitParam extends BaseParam {

        NetDBSearchLimitParam() {
            super("netdb.searchLimit", "NetDB peers per search",
                  SUB_NETDB,

                  8, 20, 1, "transport.sendProcessingTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("netdb.searchLimit", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("netdb.searchLimit", 16);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = transport.sendProcessingTime (ms, network latency proxy)
            // Cross-refs: netDb.lookupsMatched (match events), client.leaseSetFoundRemoteTime, jobLag (CPU)
            double matchCount = getAdditionalEventCount(_context, "netDb.lookupsMatched");
            double leaseTime = getAdditionalStat(_context, "client.leaseSetFoundRemoteTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean networkSlow = observed > 300;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasMatches = !Double.isNaN(matchCount) && matchCount > 0;
            boolean lsSlow = !Double.isNaN(leaseTime) && leaseTime > 500;

            // No matches = lookups are failing, shrink limit to reduce load
            if (!hasMatches && !systemBusy)
                return Math.max(_min, current - _step);

            // Fast network + matches present = decrease limit (searches succeed with fewer peers)
            if (!networkSlow && hasMatches && !lsSlow)
                return Math.max(_min, current - _step);

            // Slow LS lookups + matches present = increase limit slightly
            if (lsSlow && hasMatches && !systemBusy)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * NetDB max concurrent searches.
     * Scales with network latency.
     */
    private class NetDBMaxConcurrentParam extends BaseParam {

        NetDBMaxConcurrentParam() {
            super("netdb.maxConcurrent", "NetDB max concurrent searches",
                  SUB_NETDB,

                  4, 64, 1, "transport.sendProcessingTime", _context);
        }

        protected void applyValue(int value) {
            IterativeSearchJob.setMaxConcurrentDefault(value);
        }

        protected int getRuntimeValue() {
            return IterativeSearchJob.getMaxConcurrentDefault();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = transport.sendProcessingTime (ms, network latency proxy)
            double matchCount = getAdditionalEventCount(_context, "netDb.lookupsMatched");
            double leaseTime = getAdditionalStat(_context, "client.leaseSetFoundRemoteTime");
            double lsDropped = getAdditionalStat(_context, "client.requestLeaseSetDropped");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean networkSlow = observed > 300;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasMatches = !Double.isNaN(matchCount) && matchCount > 0;
            boolean lsSlow = !Double.isNaN(leaseTime) && leaseTime > 500;
            boolean lsUnhealthy = !Double.isNaN(lsDropped) && lsDropped > 0;

            // No matches = lookups failing, pull back concurrency
            if (!hasMatches && !systemBusy)
                return Math.max(_min, current - 1);

            // LS requests getting dropped = too much concurrency, pull back
            if (lsUnhealthy)
                return Math.max(_min, current - 1);

            // Slow LS lookups + matches present = increase concurrency slightly
            if (lsSlow && hasMatches && !systemBusy && !lsUnhealthy)
                return Math.min(_max, current + 1);

            // Fast network + matches + healthy LS = decrease concurrency (not needed)
            if (!networkSlow && hasMatches && !lsSlow && !lsUnhealthy)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * NetDB single search time (per-peer timeout).
     * Scales with network latency.
     */
    private class NetDBSingleSearchTimeParam extends BaseParam {

        NetDBSingleSearchTimeParam() {
            super("netdb.singleSearchTime", "NetDB search timeout (ms)",
                  SUB_NETDB,

                  500, 10000, 250, "transport.sendProcessingTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("netdb.singleSearchTime", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("netdb.singleSearchTime", 3000);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = transport.sendProcessingTime (ms, network latency proxy)
            double matchCount = getAdditionalEventCount(_context, "netDb.lookupsMatched");
            double netdbTime = getAdditionalStat(_context, "netDb.successTime");
            double leaseTime = getAdditionalStat(_context, "client.leaseSetFoundRemoteTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasMatches = !Double.isNaN(matchCount) && matchCount > 0;

            boolean searchSlow = !Double.isNaN(netdbTime) && netdbTime > 300;
            if (!searchSlow)
                searchSlow = !Double.isNaN(leaseTime) && leaseTime > 300;

            // No matches = lookups failing, shorten timeout to fail fast
            if (!hasMatches && !systemBusy)
                return Math.max(_min, current - _step);

            // Slow lookups + matches present = increase timeout slightly
            if (searchSlow && hasMatches && !systemBusy)
                return Math.min(_max, current + _step);

            // Fast lookups + matches present = decrease timeout (searches complete quickly)
            if (!searchSlow && hasMatches)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    // =====================================================================
    // Transport params (additional)
    // =====================================================================

    /**
     * Max concurrent SSU session establishment.
     */
    private class MaxConcurrentEstablishParam extends BaseParam {

        MaxConcurrentEstablishParam() {
            super("i2np.udp.maxConcurrentEstablish", "I2NP max concurrent handshakes",
                  SUB_TRANSPORT,

                  32, 1024, 32, "udp.outboundEstablishTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2np.udp.maxConcurrentEstablish", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2np.udp.maxConcurrentEstablish",
                   EstablishmentManager.getDefaultLowMaxConcurrentEstablish());
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.outboundEstablishTime (avg ms per outbound handshake)
            // Cross-refs: udp.establishRejected (hitting limit), udp.establishOverflow (queue overflow),
            //             udp.sendFailed (hard failures), jobLag (CPU)
            double rejected = getAdditionalStat(_context, "udp.establishRejected");
            double overflow = getAdditionalStat(_context, "udp.establishOverflow");
            double sendFailed = getAdditionalStat(_context, "udp.sendFailed");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;

            boolean slowEstablish = !Double.isNaN(observed) && observed > 5000;
            boolean hittingLimit = !Double.isNaN(rejected) && rejected > 0;
            boolean overflowing = !Double.isNaN(overflow) && overflow > 0;
            boolean failing = !Double.isNaN(sendFailed) && sendFailed > 0;

            // Hitting the limit + fast establishes + no CPU pressure = raise limit
            if (hittingLimit && !slowEstablish && !cpuPressure)
                return Math.min(_max, current + _step * 2);

            // Overflow + fast establishes = raise limit
            if (overflowing && !slowEstablish && !cpuPressure)
                return Math.min(_max, current + _step);

            // Failures + fast establishes + no CPU = raise limit
            if (failing && !slowEstablish && !cpuPressure)
                return Math.min(_max, current + _step);

            // Slow establishes + hitting limit = hold (latency problem, not concurrency)
            if (slowEstablish && hittingLimit)
                return current;

            // Slow establishes = decrease (overloaded, reduce concurrency)
            if (slowEstablish && cpuPressure)
                return Math.max(_min, current - _step);

            // No pressure + low establish time + headroom = grow for spikes
            if (!slowEstablish && !hittingLimit && !overflowing && !failing && !cpuPressure && observed < 1000)
                return Math.min(_max, current + Math.max(1, _step / 2));

            // High load + no establishment activity = shrink
            if (cpuPressure && !hittingLimit && !overflowing && !failing)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    // =====================================================================
    // Peer management params
    // =====================================================================

    /**
     * Max peer profiles in memory.
     */
    private class MaxProfilesParam extends BaseParam {

        MaxProfilesParam() {
            super("profileOrganizer.maxProfiles", "Max peer profiles in RAM",
                  SUB_PEER,

                  800, Math.min(ProfileOrganizer.ABSOLUTE_MAX_PROFILES,
                      Math.max(ProfileOrganizer.MIN_MAX_PROFILES,
                          (int) (SystemVersion.getMaxMemory() / (128 * 1024)))),
                  200, "peer.activeProfileCount", _context);
        }

        protected void applyValue(int value) {
            ProfileOrganizer.setDefaultMaxProfiles(value);
        }

        protected int getRuntimeValue() {
            return ProfileOrganizer.getDefaultMaxProfilesValue();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = peer.activeProfileCount (active profiles in RAM)
            // Cross-refs: fastPeerCount (peer quality)
            // Hourly trend: confirm profile count trend isn't just a short-term spike
            double fastPeers = getAdditionalStat(_context, "peer.fastPeerCount");
            double hourlyProfiles = getAdditionalStatHourly(_context, _statName);

            boolean manyFastPeers = !Double.isNaN(fastPeers) && fastPeers > current * 0.3;
            // Confirm sustained high profile count
            boolean sustainedHighProfiles = !Double.isNaN(hourlyProfiles) && hourlyProfiles > current * 0.8;

            // Near cap + healthy fast peers = increase capacity
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > current * 0.9 && manyFastPeers && sustainedHighProfiles)
                return Math.min(_max, current + _step);

            // Well below cap = decrease capacity (save memory)
            if (observed < current * 0.4)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Minimum fast-tier peers.
     */
    private class MinFastPeersParam extends BaseParam {

        MinFastPeersParam() {
            super("profileOrganizer.minFastPeers", "Min fast-tier peers",
                  SUB_PEER,

                  50, 2000, 50, "peer.fastPeerCount", _context);
        }

        protected void applyValue(int value) {
            ProfileOrganizer.setDefaultMinFastPeers(value);
        }

        protected int getRuntimeValue() {
            return ProfileOrganizer.getDefaultMinFastPeers();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            int maxFast = ProfileOrganizer.getDefaultMaxFastPeers();
            // observed = peer.fastPeerCount (fast peers available)
            // Cross-refs: activeProfileCount, build success rate, send confirm time
            double activeProfiles = getAdditionalStat(_context, "peer.activeProfileCount");
            double buildSuccess = getBuildSuccessRate(_context);
            double confirmTime = getAdditionalStat(_context, "transport.sendProcessingTime");
            double hourlyFastPeers = getAdditionalStatHourly(_context, _statName);

            // Ensure min always stays below max - 100
            int ceiling = Math.max(maxFast - 100, _min);

            boolean largePool = !Double.isNaN(activeProfiles) && activeProfiles > 500;
            boolean sustainedHighFastPeers = !Double.isNaN(hourlyFastPeers) && hourlyFastPeers > current;

            // Peer quality: low latency + good acceptance = more fast peers are useful
            boolean lowLatency = !Double.isNaN(confirmTime) && confirmTime < 200;
            boolean goodAcceptance = !Double.isNaN(buildSuccess) && buildSuccess > 0.7;
            boolean poorQuality = (!Double.isNaN(buildSuccess) && buildSuccess < 0.4) ||
                                  (!Double.isNaN(confirmTime) && confirmTime > 500);

            // Many fast peers + large pool + quality peers = raise minimum
            if (observed > current * 1.5 && largePool && sustainedHighFastPeers)
                return Math.min(ceiling, Math.min(_max, current + _step));

            // Good quality + available peers = raise minimum (quality peers worth keeping)
            if (goodAcceptance && lowLatency && observed > current && largePool)
                return Math.min(ceiling, Math.min(_max, current + _step));

            // Poor quality peers: decrease (fast count is inflated by low-quality peers)
            if (poorQuality && observed < current * 1.2)
                return Math.max(_min, current - _step);

            // Few fast peers OR small pool = decrease minimum
            if (observed < current * 0.6 || !largePool)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Maximum fast-tier peers.
     */
    private class MaxFastPeersParam extends BaseParam {

        MaxFastPeersParam() {
            super("profileOrganizer.maxFastPeers", "Max fast-tier peers",
                  SUB_PEER,

                  200, 3000, 50, "peer.qualityPeerCount", _context);
        }

        protected void applyValue(int value) {
            ProfileOrganizer.setDefaultMaxFastPeers(value);
        }

        protected int getRuntimeValue() {
            return ProfileOrganizer.getDefaultMaxFastPeers();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            int minFast = ProfileOrganizer.getDefaultMinFastPeers();
            // observed = peer.qualityPeerCount (fast/high-cap peers with good acceptance + recent activity)
            // Cross-refs: peer.fastPeerCount (raw count), peer.activeProfileCount (total pool)
            double activeProfiles = getAdditionalStat(_context, "peer.activeProfileCount");
            double hourlyQuality = getAdditionalStatHourly(_context, _statName);

            boolean largePool = !Double.isNaN(activeProfiles) && activeProfiles > 500;
            boolean sustainedHighQuality = !Double.isNaN(hourlyQuality) && hourlyQuality > current * 0.8;

            // Ensure max always stays above min + 100
            int floor = Math.min(minFast + 100, _max);

            // Quality peers near or above max + large pool + sustained = raise max
            if (observed > current * 0.85 && largePool && sustainedHighQuality)
                return Math.max(floor, Math.min(_max, current + _step));

            // Well below max = decrease (save resources)
            if (observed < current * 0.4 && observed < floor)
                return Math.max(floor, current - _step);

            // Quality peers declining but still above min = tighten slightly
            if (observed < current * 0.6 && !sustainedHighQuality)
                return Math.max(floor, current - _step);

            return current;
        }
    }

    /**
     * Minimum high-capacity peers.
     */
    private class MinHighCapPeersParam extends BaseParam {

        MinHighCapPeersParam() {
            super("profileOrganizer.minHighCapacityPeers", "Min high-capacity peers",
                  SUB_PEER,

                  50, 2000, 50, "peer.qualityPeerCount", _context);
        }

        protected void applyValue(int value) {
            ProfileOrganizer.setMinHighCapacityPeers(value);
        }

        protected int getRuntimeValue() {
            return ProfileOrganizer.getMinHighCapacityPeers();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            int maxHighCap = ProfileOrganizer.getDefaultMaxHighCapPeers();
            // observed = peer.qualityPeerCount (fast/high-cap peers with good acceptance + recent activity)
            // Cross-refs: peer.fastPeerCount, tunnel build success rate
            double fastPeers = getAdditionalStat(_context, "peer.fastPeerCount");
            double buildSuccess = getBuildSuccessRate(_context);
            double hourlyQuality = getAdditionalStatHourly(_context, _statName);

            boolean manyFastPeers = !Double.isNaN(fastPeers) && fastPeers > 300;
            boolean goodBuild = !Double.isNaN(buildSuccess) && buildSuccess > 0.6;
            boolean sustainedHighQuality = !Double.isNaN(hourlyQuality) && hourlyQuality > current;

            // Ensure min always stays below max - 100
            int ceiling = Math.max(maxHighCap - 100, _min);

            // Quality peers growing + good build success + sustained = raise minimum
            if (observed > current * 1.5 && manyFastPeers && sustainedHighQuality)
                return Math.min(ceiling, Math.min(_max, current + _step));

            // Good build acceptance + many fast peers = more high-cap peers useful
            if (goodBuild && manyFastPeers && observed > current)
                return Math.min(ceiling, Math.min(_max, current + _step));

            // Poor build success or small pool = decrease minimum
            if ((!Double.isNaN(buildSuccess) && buildSuccess < 0.4) || observed < current * 0.5)
                return Math.max(_min, Math.max(current - _step, _min));

            // Few quality peers = decrease minimum
            if (observed < current * 0.6)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Maximum high-capacity peers.
     */
    private class MaxHighCapPeersParam extends BaseParam {

        MaxHighCapPeersParam() {
            super("profileOrganizer.maxHighCapacityPeers", "Max high-capacity peers",
                  SUB_PEER,

                  200, 4000, 50, "peer.qualityPeerCount", _context);
        }

        protected void applyValue(int value) {
            ProfileOrganizer.setDefaultMaxHighCapPeers(value);
        }

        protected int getRuntimeValue() {
            return ProfileOrganizer.getDefaultMaxHighCapPeers();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            int minHighCap = ProfileOrganizer.getMinHighCapacityPeers();
            // observed = peer.qualityPeerCount (fast/high-cap peers with good acceptance + recent activity)
            // Cross-refs: peer.fastPeerCount, minHighCapacityPeers (must stay above min)
            double fastPeers = getAdditionalStat(_context, "peer.fastPeerCount");
            double hourlyQuality = getAdditionalStatHourly(_context, _statName);

            boolean manyFastPeers = !Double.isNaN(fastPeers) && fastPeers > 300;
            boolean sustainedHighQuality = !Double.isNaN(hourlyQuality) && hourlyQuality > current * 0.8;

            // Ensure max always stays above min + 100
            int floor = Math.min(minHighCap + 100, _max);

            // Quality peers growing + many fast peers + sustained = raise max
            if (observed > current * 0.85 && manyFastPeers && sustainedHighQuality)
                return Math.max(floor, Math.min(_max, current + _step));

            // Well below max = decrease (save resources)
            if (observed < current * 0.4 && observed < floor)
                return Math.max(floor, current - _step);

            // Quality peers declining but still above min = tighten slightly
            if (observed < current * 0.6 && !sustainedHighQuality)
                return Math.max(floor, current - _step);

            return current;
        }
    }

    // =====================================================================
    // Build timeout params
    // =====================================================================

    /**
     * Build request reply timeout.
     */
    private class BuildRequestTimeoutParam extends BaseParam {

        BuildRequestTimeoutParam() {
            super("i2p.tunnel.build.requestTimeout", "Build request reply timeout (ms)",
                  SUB_ROUTER,

                  5000, 15000, 1000, "tunnel.buildClientExpire", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.tunnel.build.requestTimeout", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return BuildRequestor.getRequestTimeout(_context);
        }

        protected double getObservedStat(RouterContext ctx) {
            return getAdditionalEventCount(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.buildClientExpire event count (timed-out builds per period)
            // Primary signal: direct count of builds that expired waiting for a reply.
            // If builds are timing out, we need MORE time (not less).
            // Cross-refs: concurrentBuilds (storm detection), dropLoadBacklog (pending build queue),
            //             buildSuccessRate (overall health), testSuccessTime (actual tunnel latency)
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double buildSuccess = getBuildSuccessRate(_context);
            double testTime = getAdditionalStat(_context, "tunnel.testSuccessTime");
            double backlog = getAdditionalStat(_context, "tunnel.dropLoadBacklog");

            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;
            boolean successLow = !Double.isNaN(buildSuccess) && buildSuccess < 0.5;
            boolean tunnelSlow = !Double.isNaN(testTime) && testTime > 5000;
            boolean buildsBackedUp = !Double.isNaN(backlog) && backlog > 10;

            // Build storm: DON'T increase timeout (storm = too many concurrent builds, not timeout issue)
            // Only decrease or hold during storms
            if (buildStorm) {
                // Storm + decent success = decrease (the storm is the problem, not timeout)
                if (buildSuccess > 0.7)
                    return Math.max(_min, current - _step * 2);
                return current;
            }

            // Builds backed up + expiring: increase timeout (queue pressure means peers need more time)
            if (buildsBackedUp && observed > 5)
                return Math.min(_max, current + _step * 2);

            // Builds expiring + low success = increase timeout (peers need more time to respond)
            if (observed > 5 && successLow)
                return Math.min(_max, current + _step * 2);

            // Tunnel tests slow = increase timeout (network latency is high)
            if (tunnelSlow && observed > 2)
                return Math.min(_max, current + _step);

            // Builds expiring but success ok = increase slightly (edge case)
            if (observed > 10)
                return Math.min(_max, current + _step);

            // Storm cleared (concurrentBuilds < 5) + no expirations + backlog drained = decrease aggressively
            if (!Double.isNaN(concurrentBuilds) && concurrentBuilds < 5 && observed < 1 && !buildsBackedUp)
                return Math.max(_min, current - _step * 2);

            // No expirations + high success + backlog drained = decrease (room to go faster)
            if (observed < 1 && !Double.isNaN(buildSuccess) && buildSuccess > 0.9 && !buildsBackedUp)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Build first-hop delivery timeout.
     * Primary signal: tunnel.buildFailFirstHop (direct first-hop failures).
     */
    private class BuildFirstHopTimeoutParam extends BaseParam {

        BuildFirstHopTimeoutParam() {
            super("i2p.tunnel.build.firstHopTimeout", "Build first-hop delivery timeout (ms)",
                  SUB_ROUTER,

                  5000, 10000, 1000, "tunnel.buildFailFirstHop", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.tunnel.build.firstHopTimeout", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return BuildRequestor.getFirstHopTimeout(_context);
        }

        protected double getObservedStat(RouterContext ctx) {
            return getAdditionalEventCount(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.buildFailFirstHop event count (first-hop delivery failures per period)
            // Primary signal: if first hops are failing, we need MORE delivery time.
            // Cross-refs: concurrentBuilds (storm detection), dropLoadBacklog (pending build queue),
            //             testSuccessTime (actual tunnel latency)
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double testTime = getAdditionalStat(_context, "tunnel.testSuccessTime");
            double backlog = getAdditionalStat(_context, "tunnel.dropLoadBacklog");

            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;
            boolean tunnelSlow = !Double.isNaN(testTime) && testTime > 5000;
            boolean buildsBackedUp = !Double.isNaN(backlog) && backlog > 10;

            // Build storm: DON'T increase (storm = too many concurrent, not timeout issue)
            if (buildStorm) return current;

            // Builds backed up + first-hop failures: increase (queue pressure + delivery issue)
            if (buildsBackedUp && observed > 2)
                return Math.min(_max, current + _step * 2);

            // First-hop failures = increase timeout (OB delivery needs more time)
            if (observed > 5)
                return Math.min(_max, current + _step * 2);

            // Tunnel tests slow + first-hop failures = increase more aggressively
            if (tunnelSlow && observed > 1)
                return Math.min(_max, current + _step * 2);

            if (observed > 2)
                return Math.min(_max, current + _step);

            // Storm cleared + no first-hop failures + backlog drained = decrease aggressively
            if (!Double.isNaN(concurrentBuilds) && concurrentBuilds < 5 && observed < 1 && !buildsBackedUp)
                return Math.max(_min, current - _step * 2);

            // No failures + backlog drained = decrease (room to go faster)
            if (observed < 1 && !buildsBackedUp)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Tunes maximum concurrent tunnel builds based on success rate and backlog.
     *
     * <p>Primary signal: {@code tunnel.buildSuccessRate} (0.0-1.0).
     * Cross-refs: {@code tunnel.concurrentBuilds}, {@code tunnel.buildClientExpire},
     * {@code tunnel.dropLoadBacklog}, {@code tunnel.testSuccessTime},
     * {@code jobQueue.jobLag}, {@code udp.avgSendWindow}, {@code udp.congestionOccurred},
     * {@code tunnel.buildRequestTime}, {@code tunnel.buildHandler.queueSize},
     * {@code tunnel.nextHopLookupSuccessTime}, {@code router.tunnelBacklog}.
     *
     * <p>Increases when builds succeed and capacity is available.
     * Decreases when success drops, builds are slow/expiring, or backlog
     * is high. Uses 2x step for aggressive decrease during build storms.
     *
     * @since 0.9.70+
     */
    private class MaxConcurrentBuildsParam extends BaseParam {

        MaxConcurrentBuildsParam() {
            super("tunnel.build.maxConcurrent", "Tunnel max concurrent builds",
                  SUB_TUNNEL,

                  4, 64, 2, "tunnel.buildSuccessRate", _context);
        }

        protected void applyValue(int value) {
            BuildExecutor.setMaxConcurrentBuilds(value);
        }

        protected int getRuntimeValue() {
            return BuildExecutor.getMaxConcurrentBuilds();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            // Stat emitted as 0-100; normalize to 0-1 for threshold comparisons
            return rate.getAverageValue() / 100.0;
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.buildSuccessRate (normalized 0.0-1.0)
            // Cross-refs: concurrentBuilds (current usage), buildClientExpire (timeouts),
            //             dropLoadBacklog (pending builds), testSuccessTime (latency),
            //             buildRequestTime (build latency), buildHandler.queueSize (queue depth),
            //             congestionOccurred (CWIN at congestion)
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double buildExpires = getAdditionalEventCount(_context, "tunnel.buildClientExpire");
            double backlog = getAdditionalStat(_context, "tunnel.dropLoadBacklog");
            double testTime = getAdditionalStat(_context, "tunnel.testSuccessTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double sendWindow = getAdditionalStat(_context, "udp.avgSendWindow");
            boolean cwinCollapsed = !Double.isNaN(sendWindow) && sendWindow < 20000;
            double congCWIN = getAdditionalStat(_context, "udp.congestionOccurred");
            boolean congestionActive = !Double.isNaN(congCWIN) && congCWIN > 0;
            double buildTime = getAdditionalStat(_context, "tunnel.buildRequestTime");
            boolean buildsSlow = !Double.isNaN(buildTime) && buildTime > 15000;
            double handlerQueue = getAdditionalStat(_context, "tunnel.buildHandler.queueSize");
            boolean handlerBackedUp = !Double.isNaN(handlerQueue) && handlerQueue > 50;
            double lookupTime = getAdditionalStat(_context, "tunnel.nextHopLookupSuccessTime");
            boolean lookupsSlow = !Double.isNaN(lookupTime) && lookupTime > 10000;
            double tunnelBacklog = getAdditionalStat(_context, "router.tunnelBacklog");
            boolean backlogHigh = !Double.isNaN(tunnelBacklog) && tunnelBacklog > 100;

            boolean successHigh = !Double.isNaN(observed) && observed > 0.9;
            boolean successLow = !Double.isNaN(observed) && observed < 0.7;
            boolean buildsExpiring = !Double.isNaN(buildExpires) && buildExpires > 10;
            boolean buildsBackedUp = !Double.isNaN(backlog) && backlog > 20;
            boolean buildsSlowLegacy = !Double.isNaN(testTime) && testTime > 10000;
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean usingCapacity = !Double.isNaN(concurrentBuilds) && concurrentBuilds > current * 0.7;

            // Transport congested: builds compete with data for bandwidth — back off hard
            if ((cwinCollapsed || congestionActive) && !cpuPressure)
                return Math.max(_min, current - _step * 2);

            // Build handler backed up + slow = decrease (processing bottleneck, not demand)
            if (handlerBackedUp && buildsSlow)
                return Math.max(_min, current - _step);

            // Next-hop lookups slow = network bottleneck, not build capacity issue
            if (lookupsSlow && successLow)
                return Math.max(_min, current - _step);

            // Tunnel backlog high = accept queue overflow, back off
            if (backlogHigh && !successHigh)
                return Math.max(_min, current - _step);

            // Startup / high demand: builds backed up + success ok = increase
            if (buildsBackedUp && successHigh && !cpuPressure && !cwinCollapsed && !congestionActive)
                return Math.min(_max, current + _step * 2);

            // Builds expiring + success ok = increase (peers can handle more)
            if (buildsExpiring && successHigh && !cpuPressure && !cwinCollapsed && !congestionActive)
                return Math.min(_max, current + _step);

            // Using most capacity + no issues = increase cautiously
            if (usingCapacity && successHigh && !buildsExpiring && !cpuPressure && !cwinCollapsed && !congestionActive)
                return Math.min(_max, current + _step);

            // Success dropping + builds slow = decrease (too much network noise)
            if (successLow && (buildsSlowLegacy || buildsSlow))
                return Math.max(_min, current - _step * 2);

            // Low success + CPU pressure = decrease
            if (successLow && cpuPressure)
                return Math.max(_min, current - _step);

            // Using less than 30% capacity + high success + no backlog = decrease (steady state)
            if (!Double.isNaN(concurrentBuilds) && concurrentBuilds < current * 0.3
                && successHigh && !buildsBackedUp && !buildsExpiring && !cwinCollapsed && !congestionActive)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Tunes the maximum concurrent next-hop RouterInfo lookups.
     *
     * <p>Primary signal: {@code tunnel.dropLookupThrottle} (dropped builds from lookup limit).
     * Cross-refs: {@code tunnel.pendingLookupQueue} (pending lookup queue depth),
     *             {@code tunnel.buildClientExpire} (timed-out builds).
     *
     * @since 0.9.70+
     */
    private class LookupLimitParam extends BaseParam {

        LookupLimitParam() {
            super("i2p.tunnel.build.maxLookupLimit", "Tunnel max concurrent RI lookups",
                  SUB_TUNNEL,
                  10, 64, 2, "tunnel.dropLookupThrottle", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.tunnel.build.maxLookupLimit", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.tunnel.build.maxLookupLimit", 32);
        }

        protected double getObservedStat(RouterContext ctx) {
            return getAdditionalEventCount(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double pendingQueue = getAdditionalStat(_context, "tunnel.pendingLookupQueue");
            double buildExpires = getAdditionalEventCount(_context, "tunnel.buildClientExpire");

            boolean lookupsDropped = !Double.isNaN(observed) && observed > 5;
            boolean queueBackedUp = !Double.isNaN(pendingQueue) && pendingQueue > 10;
            boolean buildsFailing = !Double.isNaN(buildExpires) && buildExpires > 20;

            // Lookups dropping + queue backed up = increase aggressively
            if (lookupsDropped && queueBackedUp)
                return Math.min(_max, current + _step * 2);

            // Builds failing from lookups = increase
            if (lookupsDropped && buildsFailing)
                return Math.min(_max, current + _step);

            // No drops + low usage = decrease cautiously
            if (!lookupsDropped && !queueBackedUp) {
                double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
                if (!Double.isNaN(concurrentBuilds) && concurrentBuilds < 5)
                    return Math.max(_min, current - _step);
            }

            return current;
        }
    }

    /**
     * Tunes NTCP reader thread count based on send path latency.
     *
     * <p>Primary signal: {@code ntcp.sendTime} (ms, message send latency).
     * Cross-refs: {@code ntcp.writeQueueFull}, {@code ntcp.sendBacklogTime},
     * {@code jobQueue.jobLag}.
     *
     * <p>Increases when send is slow/backlogged and CPU has headroom.
     * Decreases when send is fast, no backlog, and CPU is idle.
     * Supports dynamic thread addition/removal via
     * {@link Reader#adjustThreads()}.
     *
     * @since 0.9.70+
     */
    private class NtcpReaderThreadsParam extends BaseParam {

        NtcpReaderThreadsParam() {
            super("ntcp.reader.threads", "NTCP reader threads",
                  SUB_TRANSPORT,

                  2, 16, 1, "ntcp.readQueueSize", _context);
        }

        protected void applyValue(int value) {
            Reader.setThreadCount(value);
            Transport t = _context.commSystem().getTransports().get(NTCPTransport.STYLE);
            if (t instanceof NTCPTransport) ((NTCPTransport) t).adjustReaderThreads();
        }

        protected int getRuntimeValue() { return Reader.getThreadCount(); }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = ntcp.readQueueSize (avg pending connections waiting for a reader)
            // With 300+ connections, queue averages ~1. Threads grab a connection,
            // decrypt + parse, return it. No I/O blocking — EventPumper fills buffers.
            // Scale based on queue pressure relative to thread count.
            // Note: ntcp.readError is an event-count stat (addRateData(1));
            //       use getAdditionalEventCount() for the raw count.
            double readErrors = getAdditionalEventCount(_context, "ntcp.readError");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean errorsHigh = !Double.isNaN(readErrors) && readErrors > 10;
            int queue = !Double.isNaN(observed) ? (int) Math.round(observed) : 0;

            // Utilization: ratio of threads actively processing vs total pool size.
            // Directly measures CPU consumption by reader threads.
            double utilization = getReaderUtilization(_context);
            boolean highUtilization = !Double.isNaN(utilization) && utilization > 0.8;
            boolean lowUtilization = !Double.isNaN(utilization) && utilization < 0.2;

            // Growing: queue is backing up faster than current threads can drain
            if (queue > current * 2 && !cpuPressure)
                return Math.min(_max, current + Math.max(1, queue / current));

            // High utilization + queue pressure = definitely need more threads
            if (highUtilization && queue > current && !cpuPressure)
                return Math.min(_max, current + 1);

            // Urgent: errors + queue backing up
            if (errorsHigh && queue > current && !cpuPressure)
                return Math.min(_max, current + 1);

            // Shrinking: queue is well below capacity — threads sitting idle
            // Shrink faster when queue is empty and utilization is low
            if (queue < current / 2 && current > _min) {
                int shrinkBy = (queue == 0 && lowUtilization) ? 2 : 1;
                return Math.max(_min, current - shrinkBy);
            }

            // Long-term idle: queue near zero, no errors, no CPU pressure
            if (queue == 0 && !errorsHigh && !cpuPressure && lowUtilization && current > _min)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * Tunes NTCP writer thread count based on send path latency.
     *
     * <p>Primary signal: {@code ntcp.sendTime} (ms, message send latency).
     * Cross-refs: {@code ntcp.writeQueueFull}, {@code ntcp.sendBacklogTime},
     * {@code jobQueue.jobLag}.
     *
     * <p>Increases when send is slow/backlogged and CPU has headroom.
     * Decreases when send is fast, no backlog, and CPU is idle.
     * Supports dynamic thread addition/removal via
     * {@link Writer#adjustThreads()}.
     *
     * @since 0.9.70+
     */
    private class NtcpWriterThreadsParam extends BaseParam {

        NtcpWriterThreadsParam() {
            super("ntcp.writer.threads", "NTCP writer threads",
                  SUB_TRANSPORT,

                  2, 16, 1, "ntcp.sendTime", _context);
        }

        protected void applyValue(int value) {
            Writer.setThreadCount(value);
            Transport t = _context.commSystem().getTransports().get(NTCPTransport.STYLE);
            if (t instanceof NTCPTransport) ((NTCPTransport) t).adjustWriterThreads();
        }

        protected int getRuntimeValue() { return Writer.getThreadCount(); }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = ntcp.sendTime (ms, avg message send latency)
            // Writers are pure CPU — encrypt + prepare buffer. EventPumper does NIO write.
            // Scale based on send latency and downstream pressure.
            double sendPoolUtil = getAdditionalStat(_context, "ntcp.sendPool utilization");
            double writeBufs = getAdditionalStat(_context, "ntcp.writeBufs.size");
            double writeQueueFull = getAdditionalStat(_context, "ntcp.writeQueueFull");
            double backlogTime = getAdditionalStat(_context, "ntcp.sendBacklogTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double finisherQueue = getAdditionalStat(_context, "ntcp.sendFinisher.queueSize");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean sendSlow = observed > 50;
            boolean queueFull = !Double.isNaN(writeQueueFull) && writeQueueFull > 0;
            boolean backlogged = !Double.isNaN(backlogTime) && backlogTime > 20;
            boolean poolBusy = !Double.isNaN(sendPoolUtil) && sendPoolUtil > 50;
            boolean bufsHigh = !Double.isNaN(writeBufs) && writeBufs > current * 16;
            boolean finisherBacklogged = !Double.isNaN(finisherQueue) && finisherQueue > 50;

            // Utilization: ratio of threads actively encrypting vs total pool size
            double utilization = getWriterUtilization(_context);
            boolean highUtilization = !Double.isNaN(utilization) && utilization > 0.8;
            boolean lowUtilization = !Double.isNaN(utilization) && utilization < 0.2;

            int pressure = (sendSlow ? 1 : 0) + (queueFull ? 1 : 0) + (backlogged ? 1 : 0)
                         + (poolBusy ? 1 : 0) + (bufsHigh ? 1 : 0) + (finisherBacklogged ? 1 : 0);

            // High pressure: multiple signals firing + no CPU = add threads
            if (pressure >= 2 && !cpuPressure)
                return Math.min(_max, current + 1);

            // High utilization + any pressure = add threads
            if (highUtilization && (sendSlow || backlogged || poolBusy) && !cpuPressure)
                return Math.min(_max, current + 1);

            // Moderate pressure: single signal + no CPU = add threads only if near saturation
            if (pressure == 1 && !cpuPressure && (sendSlow || backlogged || poolBusy))
                return Math.min(_max, current + 1);

            // Idle: no pressure signals, queue not backing up = shrink
            if (pressure == 0 && current > _min) {
                int shrinkBy = (!sendSlow && !backlogged && !poolBusy && lowUtilization) ? 2 : 1;
                return Math.max(_min, current - shrinkBy);
            }

            return current;
        }
    }

    /**
     * Tunes NTCP failsafe iteration frequency based on connection count.
     *
     * <p>Primary signal: {@code ntcp.failsafeIterationTime} (ms, iteration duration).
     * Cross-refs: {@code ntcp.pumperKeySetSize} (connection count), {@code jobQueue.jobLag} (CPU).
     *
     * <p>Increases interval under high connection counts to reduce pumper overhead.
     * Decreases interval under low connection counts for faster idle detection.
     *
     * @since 0.9.70+
     */
    private class NtcpFailsafeFreqParam extends BaseParam {

        NtcpFailsafeFreqParam() {
            super("ntcp.failsafe.iterationFreq", "NTCP pumper failsafe interval (ms)",
                  SUB_TRANSPORT,

                  2000, 30000, 1000, "ntcp.failsafeIterationTime", _context);
        }

        protected void applyValue(int value) {
            NTCPTransport.setFailsafeIterationFreq(value);
        }

        protected int getRuntimeValue() {
            return (int) NTCPTransport.getFailsafeIterationFreq();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = ntcp.failsafeIterationTime (ms, time to iterate all connections)
            // Cross-refs: ntcp.pumperKeySetSize (connection count), jobQueue.jobLag (CPU)
            double keysetSize = getAdditionalStat(_context, "ntcp.pumperKeySetSize");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 50;
            int connections = !Double.isNaN(keysetSize) ? (int) keysetSize : 0;
            boolean highIterationTime = observed > 50;
            boolean manyConnections = connections > 500;

            // High iteration time or many connections + CPU pressure = increase interval
            if ((highIterationTime || manyConnections) && cpuPressure)
                return Math.min(_max, current + _step);

            // High iteration time without CPU pressure = moderate increase
            if (highIterationTime && current < _max)
                return Math.min(_max, current + _step);

            // Many connections = increase interval to reduce overhead
            if (manyConnections && current < _max)
                return Math.min(_max, current + _step);

            // Low iteration time + few connections + no CPU pressure = decrease interval
            if (observed < 10 && connections < 100 && !cpuPressure && current > _min)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Tunes NTCP send finisher thread count based on send pool utilization
     * and send finisher queue depth. More threads when the pool is saturated
     * or the finisher queue is backing up.
     * Primary signal: ntcp.sendPool utilization.
     * Cross-refs: ntcp.sendFinisher.queueSize, ntcp.writeBufs.size.
     *
     * @since 0.9.70+
     */
    private class NtcpSendFinisherThreadsParam extends BaseParam {

        NtcpSendFinisherThreadsParam() {
            super("ntcp.sendFinisher.threads", "NTCP send finisher threads",
                  SUB_TRANSPORT,

                  2, 16, 1, "ntcp.sendPool utilization", _context);
        }

        protected void applyValue(int value) {
            NTCPTransport.setSendFinisherMaxThreads(value);
            Transport t = _context.commSystem().getTransports().get(NTCPTransport.STYLE);
            if (t instanceof NTCPTransport)
                ((NTCPTransport) t).adjustSendFinisherThreads(value);
        }

        protected int getRuntimeValue() {
            return NTCPTransport.getSendFinisherMaxThreads();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = ntcp.sendPool utilization (0-100%)
            // Cross-refs: ntcp.sendFinisher.queueSize, ntcp.writeBufs.size
            double finisherQueue = getAdditionalStat(_context, "ntcp.sendFinisher.queueSize");
            double writeBufs = getAdditionalStat(_context, "ntcp.writeBufs.size");
            boolean highUtilization = !Double.isNaN(observed) && observed > 50;
            boolean finisherBacklog = !Double.isNaN(finisherQueue) && finisherQueue > 50;
            boolean highWriteBufs = !Double.isNaN(writeBufs) && writeBufs > current * 16;

            // Utilization: ratio of threads actively finishing vs total pool size
            double utilization = getSendFinisherUtilization(_context);
            boolean highUtil = !Double.isNaN(utilization) && utilization > 0.8;
            boolean lowUtil = !Double.isNaN(utilization) && utilization < 0.2;

            // High send pool utilization or finisher backlog = increase threads
            if (highUtilization || finisherBacklog || highWriteBufs)
                return Math.min(_max, current + 1);

            // High utilization + any queue pressure = add threads
            if (highUtil && (finisherBacklog || highWriteBufs))
                return Math.min(_max, current + 1);

            // Idle + no work = shrink (threads doing nothing useful)
            if (!highUtilization && !finisherBacklog && !highWriteBufs && lowUtil && current > _min)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * Tunes max concurrent outbound messages per peer.
     * Higher values allow more parallelism; lower values reduce congestion.
     * Primary signal: udp.rejectConcurrentActive (rejection rate).
     * Cross-refs: udp.sendConfirmTime (RTT), tunnel.participating InBps (bandwidth).
     *
     * @since 0.9.70+
     */
    private class MaxConcurrentMessagesParam extends BaseParam {

        MaxConcurrentMessagesParam() {
            super("udp.peer.concurrentMaxMessages", "Max UDP concurrent messages",
                  SUB_TRANSPORT,

                  128, Math.max(256, Math.min(4096,
                      (int) (SystemVersion.getMaxMemory() / (8 * 1024 * 1024)))),
                  16, "udp.allowConcurrentActive", _context);
        }

        protected void applyValue(int value) {
            PeerState.setMaxConcurrentMessages(value);
        }

        protected int getRuntimeValue() {
            return PeerState.getMaxConcurrentMessages();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.allowConcurrentActive (avg concurrent messages per peer)
            // Cross-refs: udp.sendConfirmTime (RTT), tunnel.participating InBps (transit load),
            //             udp.rejectConcurrentActive (rejection events), jobQueue.jobLag (CPU),
            //             udp.outboundQueueDepth (total outbound pressure)
            double rtt = getAdditionalStat(_context, "udp.sendConfirmTime");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double rejections = getAdditionalStat(_context, "udp.rejectConcurrentActive");
            double sendPoolUtil = getAdditionalStat(_context, "ntcp.sendPool utilization");
            double queueDepth = getAdditionalStat(_context, "udp.outboundQueueDepth");
            double memPressure = getMemoryPressure();
            boolean cpuFree = jobLag < 5 || Double.isNaN(jobLag);
            boolean lowRTT = !Double.isNaN(rtt) && rtt < 200;
            boolean moderateRTT = !Double.isNaN(rtt) && rtt < 500;
            boolean highUsage = !Double.isNaN(observed) && observed > current * 0.7;
            boolean nearCapacity = !Double.isNaN(observed) && observed > current * 0.9;
            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > getHeavyTransitThreshold(_context);
            boolean hasRejections = !Double.isNaN(rejections) && rejections > 0;
            boolean poolFree = !Double.isNaN(sendPoolUtil) && sendPoolUtil < 30;
            boolean deepQueue = !Double.isNaN(queueDepth) && queueDepth > 2000;
            boolean memOk = memPressure < 0.75;
            boolean memCritical = memPressure > 0.85;

            // Memory critical: shrink immediately regardless of other signals
            if (memCritical && current > _min)
                return Math.max(_min, current - _step * 4);

            // Deep queue + acceptable RTT + CPU free = grow aggressively to drain
            if (deepQueue && moderateRTT && cpuFree && memOk)
                return Math.min(_max, current + _step * 4);

            // Near capacity + low RTT + CPU free + memory OK = grow aggressively (4× step)
            if (nearCapacity && lowRTT && cpuFree && memOk)
                return Math.min(_max, current + _step * 4);

            // Near capacity + moderate RTT + CPU free + memory OK = grow fast (2× step)
            if (nearCapacity && moderateRTT && cpuFree && memOk)
                return Math.min(_max, current + _step * 2);

            // High usage + low RTT + CPU free + send pool headroom + memory OK = grow fast
            if (highUsage && lowRTT && cpuFree && poolFree && memOk)
                return Math.min(_max, current + _step * 2);

            // Heavy transit + low RTT + CPU free + memory OK = grow to handle load
            if (heavyTransit && lowRTT && cpuFree && memOk)
                return Math.min(_max, current + _step * 2);

            // Moderate usage + low RTT + CPU free + memory OK = grow proactively
            if (!Double.isNaN(observed) && observed > current * 0.4 && lowRTT && cpuFree && memOk)
                return Math.min(_max, current + _step);

            // Rejections + CPU headroom + memory OK = increase (need throughput even if RTT rises)
            if (hasRejections && cpuFree && memOk)
                return Math.min(_max, current + _step);

            // Low usage + high RTT + no transit = decrease to reduce pressure
            if ((!Double.isNaN(observed) && observed < current * 0.3) && !heavyTransit && !moderateRTT && current > _min)
                return Math.max(_min, current - _step);

            // High RTT + no rejections = decrease to reduce pressure
            if (!Double.isNaN(rtt) && rtt > 2000 && !hasRejections && current > _min)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Initial concurrent messages per peer — starting point for new connections.
     * Lower = conservative start, higher = aggressive start.
     * Primary signal: udp.sendConfirmTime (RTT).
     *
     * @since 0.9.70+
     */
    private class InitConcurrentMsgsParam extends BaseParam {

        InitConcurrentMsgsParam() {
            super("udp.peer.initConcurrentMsgs", "Initial UDP concurrent messages",
                  SUB_TRANSPORT,

                  16, 256, 4, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.transport.udp.initConcurrentMsgs", Integer.toString(value));
            PeerState.setInitConcurrentMsgs(value);
        }

        protected int getRuntimeValue() {
            return PeerState.getInitConcurrentMsgs();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) { return Double.NaN; }
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) { return Double.NaN; }
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double memPressure = getMemoryPressure();
            boolean cpuFree = jobLag < 10 || Double.isNaN(jobLag);
            boolean lowRTT = !Double.isNaN(observed) && observed < 500;
            boolean highRTT = !Double.isNaN(observed) && observed > 2000;
            boolean memOk = memPressure < 0.75;

            if (memPressure > 0.85) { return Math.max(_min, current - _step * 2); }
            if (lowRTT && cpuFree && memOk) { return Math.min(_max, current + _step); }
            if (highRTT && current > _min) { return Math.max(_min, current - _step); }
            return current;
        }
    }

    /**
     * Minimum concurrent messages per peer — floor below which we never drop.
     * Scales down under memory/CPU pressure, up when idle headroom available.
     *
     * @since 0.9.70+
     */
    private class MinConcurrentMsgsParam extends BaseParam {

        MinConcurrentMsgsParam() {
            super("udp.peer.minConcurrentMsgs", "Min UDP concurrent messages",
                  SUB_TRANSPORT,

                  8, 128, 2, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.transport.udp.minConcurrentMsgs", Integer.toString(value));
            PeerState.setMinConcurrentMsgs(value);
        }

        protected int getRuntimeValue() {
            return PeerState.getMinConcurrentMsgs();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) { return Double.NaN; }
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) { return Double.NaN; }
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double memPressure = getMemoryPressure();
            boolean highRTT = !Double.isNaN(observed) && observed > 2000;

            if (memPressure > 0.85) { return Math.max(_min, current - _step); }
            if (highRTT && current > _min) { return Math.max(_min, current - _step); }
            return current;
        }
    }

    /**
     * Initial RTO for new UDP peers — starting point before RTT estimation kicks in.
     * Lower = faster retransmit on lossy links, higher = avoids spurious retransmits on high-latency links.
     * Primary signal: udp.sendConfirmTime (measured RTT).
     * Cross-refs: peer.testOK (peer test time), peer.testTimeout (timeout frequency).
     *
     * @since 0.9.70+
     */
    private class InitRTOParam extends BaseParam {

        InitRTOParam() {
            super("udp.peer.initRTO", "Initial UDP RTO (ms)",
                  SUB_TRANSPORT,

                  250, 2000, 50, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.transport.udp.initRTO", Integer.toString(value));
            PeerState.setInitRTO(value);
        }

        protected int getRuntimeValue() {
            return PeerState.getInitRTO();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) { return Double.NaN; }
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) { return Double.NaN; }
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = avg RTT in ms
            // Cross-refs: peer.testOK (peer test time), peer.testTimeout (timeout freq)
            // Set init RTO to ~1.5× observed RTT, clamped to range
            if (Double.isNaN(observed) || observed <= 0) {
                // No RTT data — use peer test time as fallback
                double peerTestTime = getAdditionalStat(_context, "peer.testOK");
                if (!Double.isNaN(peerTestTime) && peerTestTime > 0) {
                    int target = (int)(peerTestTime * 1.5);
                    return Math.max(_min, Math.min(_max, target));
                }
                return current;
            }
            int target = (int)(observed * 1.5);
            // Peer test timeouts indicate unreliable peers — raise init RTO to avoid
            // premature retransmits to slow-but-alive peers
            double testTimeouts = getAdditionalStat(_context, "peer.testTimeout");
            if (!Double.isNaN(testTimeouts) && testTimeouts > 5) {
                target = Math.max(target, current + _step);
            }
            return Math.max(_min, Math.min(_max, target));
        }
    }

    /**
     * Minimum RTO — floor for retransmission timeout.
     * Lower = more aggressive retransmit, higher = avoids retransmit storms.
     * Primary signal: udp.sendConfirmTime (RTT) and udp.retransmitEvents (retransmit count).
     * Cross-refs: udp.avgSendWindow (CWIN), udp.congestionOccurred (congestion events),
     *             udp.congestedRTO (RTO inflation during congestion).
     *
     * @since 0.9.70+
     */
    private class MinRTOParam extends BaseParam {

        MinRTOParam() {
            super("udp.peer.minRTO", "Min UDP RTO (ms)",
                  SUB_TRANSPORT,

                  250, 1000, 50, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.transport.udp.minRTO", Integer.toString(value));
            PeerState.setMinRTO(value);
        }

        protected int getRuntimeValue() {
            return PeerState.getMinRTO();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) { return Double.NaN; }
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) { return Double.NaN; }
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double retransmits = getAdditionalStat(_context, "udp.retransmitEvents");
            boolean hasRetransmits = !Double.isNaN(retransmits) && retransmits > 0;
            double sendWindow = getAdditionalStat(_context, "udp.avgSendWindow");
            boolean cwinCollapsed = !Double.isNaN(sendWindow) && sendWindow < 20000;
            double congCWIN = getAdditionalStat(_context, "udp.congestionOccurred");
            boolean congestionActive = !Double.isNaN(congCWIN) && congCWIN > 0;
            double congestedRTO = getAdditionalStat(_context, "udp.congestedRTO");
            boolean rtoInflated = !Double.isNaN(congestedRTO) && congestedRTO > current * 2;

            // CWIN collapse + retransmits = lower minRTO for faster loss detection.
            // This breaks the death spiral: congestion → retransmits → higher RTO → more delay.
            if (hasRetransmits && cwinCollapsed) {
                return Math.max(_min, current - _step);
            }
            // Active congestion + RTO already inflated = lower aggressively to break spiral
            if (congestionActive && rtoInflated) {
                return Math.max(_min, current - _step * 2);
            }
            // Normal retransmits without CWIN collapse = raise to avoid retransmit storms
            if (hasRetransmits) { return Math.min(_max, current + _step); }
            // No retransmits + low RTT = can safely lower
            if (!Double.isNaN(observed) && observed < 300 && !hasRetransmits) {
                return Math.max(_min, current - _step);
            }
            return current;
        }
    }

    /**
     * Maximum RTO — ceiling for retransmission timeout.
     * Lower = fail peers faster, higher = tolerates longer outages.
     * Primary signal: udp.sendConfirmTime (RTT) and udp.sendExpired (timeout failures).
     * Cross-refs: udp.avgSendWindow (CWIN), udp.congestionOccurred (congestion events),
     *             udp.congestedRTO (RTO inflation during congestion).
     *
     * @since 0.9.70+
     */
    private class UdpMaxRtoParam extends BaseParam {

        UdpMaxRtoParam() {
            super("udp.peer.maxRTO", "Max UDP RTO (ms)",
                  SUB_TRANSPORT,

                  10000, 60000, 1000, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.transport.udp.maxRTO", Integer.toString(value));
            PeerState.setMaxRTO(value);
        }

        protected int getRuntimeValue() {
            return PeerState.getMaxRTO();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) { return Double.NaN; }
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) { return Double.NaN; }
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double failures = getAdditionalStat(_context, "udp.sendExpired");
            boolean hasFailures = !Double.isNaN(failures) && failures > 0;
            double sendWindow = getAdditionalStat(_context, "udp.avgSendWindow");
            boolean cwinCollapsed = !Double.isNaN(sendWindow) && sendWindow < 20000;
            double congCWIN = getAdditionalStat(_context, "udp.congestionOccurred");
            boolean congestionActive = !Double.isNaN(congCWIN) && congCWIN > 0;
            double congestedRTO = getAdditionalStat(_context, "udp.congestedRTO");
            boolean rtoInflated = !Double.isNaN(congestedRTO) && congestedRTO > current;

            // CWIN collapse = congestion is active, tighten maxRTO for fast failover
            if (cwinCollapsed) { return Math.max(_min, current - _step * 2); }
            // Active congestion + RTO already inflated = tighten aggressively
            if (congestionActive && rtoInflated) {
                return Math.max(_min, current - _step * 2);
            }
            // Failures + low RTT = fail peers faster, don't let maxRTO balloon
            if (hasFailures && !Double.isNaN(observed) && observed < 3000) {
                return Math.max(_min, current - _step);
            }
            // Failures + high RTT = raise cautiously (slow network, not dead peer)
            if (hasFailures && !Double.isNaN(observed) && observed > 5000) {
                return Math.min(_max, current + _step);
            }
            // No failures + low RTT = lower to fail faster
            if (!hasFailures && !Double.isNaN(observed) && observed < 1000) {
                return Math.max(_min, current - _step);
            }
            return current;
        }
    }

    /**
     * Max send window (CWIN) — bytes in flight per peer.
     * Higher = more aggressive sending, lower = less buffering.
     * Primary signal: udp.avgSendWindow (current usage across peers).
     * Cross-refs: udp.retransmitEvents (retransmits), udp.congestionOccurred (congestion events),
     *             jobQueue.jobLag (CPU pressure).
     *
     * @since 0.9.70+
     */
    private class MaxSendWindowParam extends BaseParam {

        MaxSendWindowParam() {
            super("udp.peer.maxSendWindow", "Max UDP send window (bytes)",
                  SUB_TRANSPORT,

                  65536, 1048576, 8192, "udp.avgSendWindow", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.transport.udp.maxSendWindow", Integer.toString(value));
            PeerState.setMaxSendWindow(value);
        }

        protected int getRuntimeValue() {
            return PeerState.getMaxSendWindow();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) { return Double.NaN; }
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) { return Double.NaN; }
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double memPressure = getMemoryPressure();
            boolean cpuFree = jobLag < 10 || Double.isNaN(jobLag);
            boolean memOk = memPressure < 0.75;
            boolean highUsage = !Double.isNaN(observed) && observed > current * 0.7;
            double retransmits = getAdditionalStat(_context, "udp.retransmitEvents");
            boolean congested = !Double.isNaN(retransmits) && retransmits > 0;
            double congCWIN = getAdditionalStat(_context, "udp.congestionOccurred");
            boolean congestionActive = !Double.isNaN(congCWIN) && congCWIN > 0;
            double failsafeCloses = getAdditionalEventCount(_context, "ntcp.failsafeCloses");
            boolean failsafeActive = !Double.isNaN(failsafeCloses) && failsafeCloses > 0;
            double mtuDecrease = getAdditionalEventCount(_context, "udp.mtuDecrease");
            boolean mtuShrinking = !Double.isNaN(mtuDecrease) && mtuDecrease > 0;

            // Memory critical: shrink fast
            if (memPressure > 0.85) { return Math.max(_min, current - _step * 4); }
            // Failsafe closes or MTU shrinking = extreme congestion — shrink window
            if (failsafeActive || mtuShrinking) {
                return Math.max(_min, current - _step * 2);
            }
            // CWIN collapse during congestion: grow aggressively to provide headroom
            // (the old "low usage" logic incorrectly shrunk during collapse)
            if ((congested || congestionActive) && cpuFree && memOk) {
                return Math.min(_max, current + _step * 4);
            }
            // High usage + CPU free + memory OK = grow
            if (highUsage && cpuFree && memOk) { return Math.min(_max, current + _step * 2); }
            // Low usage + no congestion + healthy = shrink to reduce buffering
            boolean lowUsage = !Double.isNaN(observed) && observed < current * 0.3;
            if (lowUsage && !congested && !congestionActive && current > _min) {
                return Math.max(_min, current - _step);
            }
            return current;
        }
    }

    /**
     * Tunes NTCP send pool capacity (outbound message buffering).
     * Sweet spot: large enough to prevent send stalls, small enough to avoid buffering latency.
     * Queue-thread coupling: must scale with ntcp.writer.threads.
     * Primary signal: ntcp.sendPool utilization (pct of capacity in use).
     * Cross-refs: ntcp.writer.threads, transport.sendProcessingTime.
     *
     * @since 0.9.70+
     */
    private class SendPoolCapacityParam extends BaseParam {

        SendPoolCapacityParam() {
            super("ntcp.sendPool.capacity", "NTCP send pool capacity",
                  SUB_TRANSPORT,

                  64, 8192, 32, "ntcp.sendPool utilization", _context);
        }

        @Override
        protected int getDefaultMax(RouterContext ctx) {
            int def = SystemVersion.isSlow() ? 64 : 128;
            // Scale max with cores: more connections = more queued sends.
            // Mirrors TransportImpl.setSendPoolCapacity() clamp.
            return Math.max(def * 16, SystemVersion.getCores() * 512);
        }

        protected void applyValue(int value) {
            TransportImpl.setSendPoolCapacity(value);
            Transport t = _context.commSystem().getTransports().get(NTCPTransport.STYLE);
            if (t instanceof TransportImpl) ((TransportImpl) t).resizeSendPool();
        }

        protected int getRuntimeValue() {
            return TransportImpl.getSendPoolCapacity();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = ntcp.sendPool utilization (pct 0-100)
            // Cross-refs: transport.sendProcessingTime (latency), jobLag (CPU),
            //             ntcp.sendFinisher.queueSize (backlog), udp.sendConfirmTime (RTT)
            double sendTime = getAdditionalStat(_context, "transport.sendProcessingTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double finisherQ = getAdditionalStat(_context, "ntcp.sendFinisher.queueSize");
            double rtt = getAdditionalStat(_context, "udp.sendConfirmTime");
            double memPressure = getMemoryPressure();
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean memOk = memPressure < 0.75;
            boolean memCritical = memPressure > 0.85;
            boolean criticalUtilization = !Double.isNaN(observed) && observed > 80;
            boolean highUtilization = !Double.isNaN(observed) && observed > 60;
            boolean finisherBacklog = !Double.isNaN(finisherQ) && finisherQ > 200;
            double dispatchExpired = getAdditionalStat(_context, "transport.dispatchExpired");
            boolean dispatchPressure = !Double.isNaN(dispatchExpired) && dispatchExpired > 100;

            // Memory critical: shrink immediately
            if (memCritical && current > _min)
                return Math.max(_min, current - _step * 2);

            // Critical saturation: pool IS the bottleneck, grow regardless of latency.
            // Only CPU or memory pressure stops us — latency is a symptom of the pool being too small.
            if (criticalUtilization && !cpuPressure && memOk)
                return Math.min(_max, current + _step * 2);

            // Dispatch pressure + low utilization + CPU free + memory OK = grow to absorb backlog
            if (dispatchPressure && !criticalUtilization && !cpuPressure && memOk)
                return Math.min(_max, current + _step);

            // High utilization or finisher backlog + no CPU + memory OK = grow pool
            if ((highUtilization || finisherBacklog) && !cpuPressure && memOk)
                return Math.min(_max, current + _step * 2);

            // Moderate utilization + low latency + low RTT + memory OK = grow pool proactively
            boolean lowLatency = !Double.isNaN(sendTime) && sendTime < 100;
            boolean lowRTT = !Double.isNaN(rtt) && rtt < 200;
            if (!Double.isNaN(observed) && observed > 40 && !cpuPressure && lowLatency && lowRTT && memOk)
                return Math.min(_max, current + _step);

            // Low utilization + no backlog = shrink to reduce memory
            if ((observed < 20 || Double.isNaN(observed)) && !cpuPressure
                && !finisherBacklog && current > _min)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Tunes I2CP internal client queue size.
     * Sweet spot: large enough to prevent client drops, small enough to avoid buffering latency.
     * Primary signal: udp.sendConfirmTime (actual network RTT).
     * Cross-refs: client.writerQueueFull (overflow events), jobLag (CPU), transport.sendProcessingTime.
     *
     * @since 0.9.70+
     */
    private class InternalQueueSizeParam extends BaseParam {

        InternalQueueSizeParam() {
            super("i2cp.internalQueueSize", "I2CP internal queue (messages)",
                  SUB_I2CP,

                  128, 1024, 32, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            Tuner.setInternalQueueSize(value);
        }

        protected int getRuntimeValue() {
            return Tuner.getInternalQueueSize();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (ms, actual network RTT)
            // Cross-refs: client.writerQueueFull (overflow events), client.dispatchTime, jobLag (CPU), sendProcessingTime
            double overflows = getAdditionalEventCount(_context, "client.writerQueueFull");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double sendTime = getAdditionalStat(_context, "transport.sendProcessingTime");
            double dispatchTime = getAdditionalStat(_context, "client.dispatchTime");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasOverflows = !Double.isNaN(overflows) && overflows > 0;
            boolean highRTT = !Double.isNaN(observed) && observed > 1000;
            boolean lowLatency = !Double.isNaN(sendTime) && sendTime < 50;
            boolean dispatchSlow = !Double.isNaN(dispatchTime) && dispatchTime > 500;

            // Overflow events + headroom = increase queue (prevent client drops)
            if (hasOverflows && !cpuPressure)
                return Math.min(_max, current + _step);

            // High RTT + no CPU pressure = increase queue (clients back up on slow links)
            if (highRTT && !cpuPressure)
                return Math.min(_max, current + _step);

            // Dispatch time high = queuing delays, increase capacity
            if (dispatchSlow && !cpuPressure && !hasOverflows)
                return Math.min(_max, current + _step);

            // Low latency + no overflows + fast dispatch = decrease queue (reduce buffering)
            if (lowLatency && !hasOverflows && !cpuPressure && !dispatchSlow && current > _min)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Tunes max queued outbound SSU connections.
     * Sweet spot: large enough to prevent connection drops, small enough to avoid stale connections.
     * Primary signal: udp.queuedOutbound.size (queue depth at check).
     * Cross-refs: i2np.udp.maxConcurrentEstablish, udp.sendConfirmTime.
     *
     * @since 0.9.70+
     */
    private class MaxQueuedOutboundParam extends BaseParam {

        MaxQueuedOutboundParam() {
            super("udp.establish.maxQueuedOutbound", "Max pending UDP handshakes",
                  SUB_TRANSPORT,

                  32, 512, 8, "udp.sendConfirmTime", _context);
        }

        protected void applyValue(int value) {
            EstablishmentManager.setMaxQueuedOutbound(value);
        }

        protected int getRuntimeValue() {
            return EstablishmentManager.getMaxQueuedOutbound();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = udp.sendConfirmTime (ms, actual network RTT)
            // Cross-refs: udp.establishBadIP (bad connection attempts), jobLag (CPU)
            // Note: establishBadIP is an event-count stat (addRateData(1));
            //       use getAdditionalEventCount() for the raw count.
            double badIP = getAdditionalEventCount(_context, "udp.establishBadIP");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean highRTT = !Double.isNaN(observed) && observed > 1000;
            boolean lowRTT = !Double.isNaN(observed) && observed < 200;
            boolean hasBadIPs = !Double.isNaN(badIP) && badIP > 5;

            // High RTT + no CPU pressure = increase queue (slow connections back up)
            if (highRTT && !cpuPressure)
                return Math.min(_max, current + _step);

            // Bad IPs + high RTT = increase queue (connection attempts failing)
            if (hasBadIPs && highRTT && !cpuPressure)
                return Math.min(_max, current + _step);

            // Low RTT + no bad IPs = decrease queue (connections establishing fast)
            if (lowRTT && !hasBadIPs && !cpuPressure && current > _min)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Tunes max write buffers per NTCP connection.
     * Sweet spot: large enough to prevent write stalls, small enough to avoid memory waste.
     * Queue-thread coupling: must scale with ntcp.writer.threads.
     * Primary signal: ntcp.writeBufs.size (avg write buffer count).
     * Cross-refs: ntcp.writer.threads, transport.sendProcessingTime.
     *
     * @since 0.9.70+
     */
    private class MaxWriteBufsParam extends BaseParam {

        MaxWriteBufsParam() {
            super("ntcp.maxWriteBufs", "NTCP max write buffers per connection",
                  SUB_TRANSPORT,

                  128, 2048, 16, "ntcp.writeBufs.size", _context);
        }

        protected void applyValue(int value) {
            NTCPConnection.setMaxWriteBufs(value);
        }

        protected int getRuntimeValue() {
            return NTCPConnection.getMaxWriteBufs();
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = ntcp.writeBufs.size (avg write buffer count per connection)
            // Cross-refs: ntcp.writer.threads, udp.sendConfirmTime (RTT),
            //             transport.sendProcessingTime, ntcp.writeQueueFull
            int writers = Writer.getThreadCount();
            double rtt = getAdditionalStat(_context, "udp.sendConfirmTime");
            double sendTime = getAdditionalStat(_context, "transport.sendProcessingTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double writeQueueFull = getAdditionalStat(_context, "ntcp.writeQueueFull");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean highUtilization = !Double.isNaN(observed) && observed > current * 0.5;
            boolean queueFull = !Double.isNaN(writeQueueFull) && writeQueueFull > 0;
            boolean highRTT = !Double.isNaN(rtt) && rtt > 200;
            boolean lowLatency = !Double.isNaN(sendTime) && sendTime < 50;

            // High utilization or queue full + no CPU = increase (more buffering for throughput)
            if ((highUtilization || queueFull) && !cpuPressure) {
                // Queue-thread coupling: don't exceed writers * 128
                int maxByThreads = writers * 128;
                return Math.min(Math.min(_max, maxByThreads), current + _step);
            }

            // High RTT + writer headroom + no CPU = increase (slow links need more buffering)
            if (highRTT && !cpuPressure && writers > 2)
                return Math.min(_max, current + _step);

            // Low utilization + low latency + no queue pressure = decrease (reduce memory)
            if ((observed < current * 0.2 || Double.isNaN(observed)) && lowLatency
                && !queueFull && !highRTT && current > _min)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Computes a composite system health score from multiple cross-cutting stats.
     * Score ranges from 0.0 (severely degraded) to 1.0 (perfect).
     *
     * <p>Computed once per tuning cycle and shared by all params. Used for:
     * <ul>
     *   <li>Auto-revert: when score &lt; 0.3, all params snap to defaults</li>
     *   <li>Dampening: when score &lt; 0.6, step sizes are reduced proportionally</li>
     *   <li>Latency weighting: transport.sendProcessingTime gets 30% weight</li>
     * </ul>
     *
     * <p>Scoring uses a weighted geometric mean so that poor performance in
     * any factor drags the overall score down (multiplicative, not additive).
     *
     * <p>Factors and weights:
     * <ul>
     *   <li>Job queue lag (20%) — CPU saturation</li>
     *   <li>Build success rate (15%) — network health</li>
     *   <li>Message failure lifetime (15%) — congestion</li>
     *   <li>Concurrent builds (10%) — build storms</li>
     *   <li>Transit load (10%) — bandwidth utilization</li>
     *   <li>Send latency (30%) — end-to-end responsiveness</li>
     * </ul>
     *
     * @since 0.9.70+
     */

    /**
     * Tunes max concurrent test jobs based on transport latency.
     * When latency is high, fewer tests = less transport pressure.
     * When latency is low, more tests = faster pool recovery.
     * Primary signal: transport.sendProcessingTime (message latency).
     * Cross-refs: udp.sendConfirmTime (RTT), jobQueue.testJobCount.
     *
     * @since 0.9.70+
     */
    private class TestJobMaxQueuedParam extends BaseParam {

        TestJobMaxQueuedParam() {
            super("tunnel.testJob.maxQueued", "Tunnel max concurrent test jobs",
                  SUB_TUNNEL,

                  12, 256, 4, "transport.sendProcessingTime", _context);
        }

        protected void applyValue(int value) {
            TestJob.maxQueuedTests = value;
        }

        protected int getRuntimeValue() {
            return TestJob.maxQueuedTests;
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = transport.sendProcessingTime (ms, message lifetime)
            // Cross-refs: udp.sendConfirmTime (RTT), jobQueue.testJobCount
            double rtt = getAdditionalStat(_context, "udp.sendConfirmTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 50;
            boolean highLatency = !Double.isNaN(observed) && observed > 200;
            boolean severeLatency = !Double.isNaN(observed) && observed > 1000;
            boolean highRTT = !Double.isNaN(rtt) && rtt > 500;

            // Severe latency — slash test count to free transport
            if (severeLatency || highRTT)
                return Math.max(_min, current / 2);

            // Moderate latency — reduce tests
            if (highLatency && !cpuPressure)
                return Math.max(_min, current - _step * 2);

            // Low latency + no CPU pressure — allow more tests for faster pool recovery
            if (!highLatency && !highRTT && !cpuPressure && current < _max)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Tunes minimum delay between tests per tunnel.
     * Longer delay = fewer tests = less transport pressure.
     * Shorter delay = faster detection of tunnel failures.
     * Primary signal: transport.sendProcessingTime.
     * Cross-refs: tunnel.testFailedTime, tunnel.testSuccessTime.
     *
     * @since 0.9.70+
     */
    private class TestJobMinDelayParam extends BaseParam {

        TestJobMinDelayParam() {
            super("tunnel.testJob.minTestDelay", "Tunnel min test delay (ms)",
                  SUB_TUNNEL,

                  10000, 120000, 5000, "transport.sendProcessingTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.tunnel.testJob.minTestDelay", String.valueOf(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.tunnel.testJob.minTestDelay", 30000);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = transport.sendProcessingTime (ms)
            // Cross-refs: tunnel.testFailedTime, tunnel.testSuccessTime
            double testFailTime = getAdditionalStat(_context, "tunnel.testFailedTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 50;
            boolean highLatency = !Double.isNaN(observed) && observed > 200;
            boolean testsSlow = !Double.isNaN(testFailTime) && testFailTime > 15000;

            // High transport latency or slow tests — increase delay to reduce pressure
            if (highLatency || testsSlow || cpuPressure) {
                int maxDelay = _context.getProperty("i2p.tunnel.testJob.maxTestDelay", 90000);
                return Math.min(Math.min(_max, maxDelay), current + _step);
            }

            // Low latency — decrease delay for faster failure detection
            if (!highLatency && !cpuPressure && current > _min)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Tunes maximum delay between tests per tunnel.
     * Works with min delay — max caps the backoff for healthy tunnels.
     * Primary signal: transport.sendProcessingTime.
     * Cross-refs: tunnel.testSuccessTime.
     *
     * @since 0.9.70+
     */
    private class TestJobMaxDelayParam extends BaseParam {

        TestJobMaxDelayParam() {
            super("tunnel.testJob.maxTestDelay", "Tunnel max test delay (ms)",
                  SUB_TUNNEL,

                  60000, 600000, 10000, "transport.sendProcessingTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.tunnel.testJob.maxTestDelay", String.valueOf(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.tunnel.testJob.maxTestDelay", 90000);
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = transport.sendProcessingTime (ms)
            boolean highLatency = !Double.isNaN(observed) && observed > 200;

            // High latency — increase max delay (healthier tunnels tested less often)
            if (highLatency)
                return Math.min(_max, current + _step);

            // Low latency — decrease max delay (catch failures faster)
            if (!highLatency && current > _min) {
                int minDelay = _context.getProperty("i2p.tunnel.testJob.minTestDelay", 30000);
                return Math.max(Math.max(_min, minDelay), current - _step);
            }

            return current;
        }
    }

    /**
     * Tunes the I2PTunnel server handler thread pool size.
     * Primary signal: serverHandler.queueDepth (tasks waiting for a handler thread).
     * Cross-refs: jobQueue.jobLag (CPU pressure).
     *
     * @since 0.9.70+
     */
    private class I2PTunnelServerHandlerThreadsParam extends BaseParam {

        I2PTunnelServerHandlerThreadsParam() {
            super("i2ptunnel.serverHandler.threads", "I2PTunnel server threads",
                  SUB_TUNNEL,
                  2, 128, 1, "i2ptunnel.serverHandler.queueDepth", _context);
        }

        protected void applyValue(int value) {
            I2PTunnelReflector.invokeSetInt("setServerHandlerThreads", value);
        }

        protected int getRuntimeValue() {
            return I2PTunnelReflector.invokeGetInt("getServerHandlerThreads");
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = i2ptunnel.serverHandler.queueDepth (60s rolling avg)
            // Cross-refs: jobQueue.jobLag (CPU pressure)
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;

            // Queue backlog + no CPU pressure — grow pool
            if (!cpuPressure && observed > 100)
                return Math.min(_max, current + 1);

            // Idle pool with minimal queue — shrink
            if (observed < 5 && current > _min)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * Tunes I2PTunnel client runner max pool size.
     * Pool is cached-style (core=0, SynchronousQueue), so threads are created on demand
     * and time out after 2 min idle. This caps the burst ceiling.
     */
    private class I2PTunnelClientRunnerMaxParam extends BaseParam {

        I2PTunnelClientRunnerMaxParam() {
            super("i2ptunnel.clientRunner.max", "I2PTunnel client threads",
                  SUB_TUNNEL,
                   4, 8192, 4, "i2ptunnel.clientRunner.poolSize", _context);
        }

        protected void applyValue(int value) {
            I2PTunnelReflector.invokeSetInt("setClientRunnerMax", value);
        }

        protected int getRuntimeValue() {
            return I2PTunnelReflector.invokeGetInt("getClientRunnerMax");
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = i2ptunnel.clientRunner.poolSize (60s rolling avg)
            // If active threads are near the max ceiling, raise it
            if (observed > current * 0.75 && current < _max)
                return Math.min(_max, current + Math.max(current / 4, 16));
            // If active threads are well below max, lower the ceiling
            if (observed < current * 0.25 && current > _min)
                return Math.max(_min, current - Math.max(current / 4, 16));
            return current;
        }
    }

    /**
     * Tunes BuildHandler thread count based on inbound build request backlog.
     * BuildHandler processes incoming tunnel build requests. More threads reduce
     * backlog when under load; fewer threads save resources when idle.
     */
    private class BuildHandlerThreadsParam extends BaseParam {

        BuildHandlerThreadsParam() {
            super("router.buildHandlerThreads", "Tunnel build handler threads",
                  SUB_ROUTER,
                  2, 8, 1, "tunnel.buildHandler.queueSize", _context);
        }

        protected void applyValue(int value) {
            if (_context.tunnelManager() instanceof TunnelPoolManager) {
                ((TunnelPoolManager) _context.tunnelManager()).setBuildHandlerThreads(value);
                ((TunnelPoolManager) _context.tunnelManager()).adjustBuildHandlerThreads(value);
            }
        }

        protected int getRuntimeValue() {
            int stored = TunnelPoolManager.getBuildHandlerThreads();
            if (_context.tunnelManager() instanceof TunnelPoolManager) {
                TunnelPoolManager mgr = (TunnelPoolManager) _context.tunnelManager();
                int actual = mgr.getBuildHandlerThreadCount();
                if (actual != stored) {
                    if (_log.shouldWarn())
                        _log.warn("BuildHandler thread mismatch: stored=" + stored + " actual=" + actual + " — reconciling");
                    TunnelPoolManager.setBuildHandlerThreads(actual);
                    stored = actual;
                }
                if (stored < 2) {
                    if (_log.shouldWarn())
                        _log.warn("BuildHandler thread count " + stored + " below floor 2 — rescaling");
                    stored = 2;
                    TunnelPoolManager.setBuildHandlerThreads(2);
                    mgr.adjustBuildHandlerThreads(2);
                }
            }
            return stored;
        }

        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double acceptLoad = getAdditionalStat(_context, "tunnel.acceptLoad");
            boolean dropping = getAdditionalStat(_context, "tunnel.dropLoadProactive") > 0 ||
                               getAdditionalStat(_context, "tunnel.rejectOverloaded") > 0;

            // CPU saturated — hold
            if (cpuPressure)
                return current;

            // Scale up on queue backlog — only when genuine need
            if (observed > 0 && current < _max) {
                // Strong signal: dropping or rejecting requests — scale up aggressively
                if (dropping) {
                    int inc = Math.min(Math.max(1, (int) observed / 2), 3);
                    return Math.min(_max, current + inc);
                }
                // Moderate signal: backlog with significant queue wait time (>500ms avg)
                if (!Double.isNaN(acceptLoad) && acceptLoad > 500) {
                    int inc = Math.min(Math.max(1, (int) observed / 3), 2);
                    return Math.min(_max, current + inc);
                }
                // Backlog but fast service — transient, don't scale
                return current;
            }

            // Scale up when concurrent builds exceed threads
            if (!Double.isNaN(concurrentBuilds) && concurrentBuilds > current * 2 && current < _max) {
                int inc = Math.min(Math.max(1, (int) concurrentBuilds / 6), 2);
                return Math.min(_max, current + inc);
            }

            // Shrink when queue idle AND service time is negligible (<10ms)
            if (observed < 1 && current > 2) {
                if (!Double.isNaN(acceptLoad) && acceptLoad < 10)
                    return current - 1;
            }

            return current;
        }
    }

    /**
     * Minimum uptime (ms) before system health scoring begins.
     * During this window, getScore() returns NaN (UI shows "not yet available")
     * and subsystem rings show gray "collecting" per their own min-event checks.
     */
    private static final long STARTUP_GRACE_MS = 5 * 60 * 1000L;

    static class SystemHealth {
        private final RouterContext _ctx;
        private double _score = Double.NaN;
        private final long _uptime;

        SystemHealth(RouterContext ctx) {
            _ctx = ctx;
            _uptime = ctx.router().getUptime();
            compute();
        }

        double getScore() { return _score; }

        private void compute() {
            // Defer assessment during startup — too few events = unreliable
            if (_uptime < STARTUP_GRACE_MS) {
                _score = Double.NaN;
                return;
            }
            double jobLagScore = scoreJobLag();
            double buildScore = scoreBuildSuccess();
            double sendFailScore = scoreSendFailure();
            double buildStormScore = scoreBuildStorms();
            double latencyScore = scoreLatency();

            // Weighted geometric mean — skip NaN factors (insufficient data).
            // Renormalize weights so the total is 1.0 across available factors.
            double total = 1.0;
            double weightSum = 0;
            if (!Double.isNaN(jobLagScore))     { total *= Math.pow(jobLagScore, 0.20);     weightSum += 0.20; }
            if (!Double.isNaN(buildScore))      { total *= Math.pow(buildScore, 0.15);      weightSum += 0.15; }
            if (!Double.isNaN(sendFailScore))   { total *= Math.pow(sendFailScore, 0.15);   weightSum += 0.15; }
            if (!Double.isNaN(buildStormScore)) { total *= Math.pow(buildStormScore, 0.10); weightSum += 0.10; }
            if (!Double.isNaN(latencyScore))    { total *= Math.pow(latencyScore, 0.30);    weightSum += 0.30; }
            _score = (weightSum > 0) ? Math.pow(total, 1.0 / weightSum) : 1.0;
        }

        /**
         * Job queue lag: 0ms = 1.0, >50ms = degraded, >200ms = 0.0
         * Also considers jobRunSlow (jobs >1s) as sustained overload signal.
         */
        private double scoreJobLag() {
            RateStat rs = _ctx.statManager().getRate("jobQueue.jobLag");
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() < 3) return Double.NaN;
            double avg = rate.getAverageValue();
            if (avg <= 0) return 1.0;
            // 0ms→1.0, 50ms→0.75, 200ms→0.0
            double lagScore = clamp(1.0 - (avg / 200.0));
            // jobRunSlow: sustained jobs >1s — penalize if frequent
            RateStat slowRs = _ctx.statManager().getRate("jobQueue.jobRunSlow");
            if (slowRs != null) {
                Rate slowRate = slowRs.getRate(STAT_PERIOD);
                if (slowRate != null && slowRate.getLastEventCount() > 0) {
                    double slowAvg = slowRate.getAverageValue();
                    // slowAvg is avg duration of slow jobs; >5000ms = severe
                    if (slowAvg > 5000) lagScore *= 0.5;
                    else if (slowAvg > 2000) lagScore *= 0.75;
                }
            }
            return lagScore;
        }

        /**
         * Build success rate from SystemVersion.getTunnelBuildSuccess()
         * (returns 0–100 from 10-minute build stats).
         * When firewalled, lower thresholds since inbound builds are rejected.
         */
        private double scoreBuildSuccess() {
            double pct = SystemVersion.getTunnelBuildSuccess();
            if (pct <= 0) {return Double.NaN;}
            if (isFirewalled()) {
                // firewalled: 10%→0.0, 50%→1.0
                return clamp((pct - 10.0) / 40.0);
            }
            // normal: 30%→0.0, 80%→1.0
            return clamp((pct - 30.0) / 50.0);
        }

        /**
         * Fraction of registered tunnel pools that are alive.
         * 1.0 = all alive, 0.0 = half or more dead.
         */
        private double scorePoolAlive() {
            TunnelManagerFacade mgr = _ctx.tunnelManager();
            List<TunnelPool> pools = new ArrayList<>();
            mgr.listPools(pools);
            if (pools.isEmpty()) return Double.NaN;
            int alive = 0;
            for (TunnelPool p : pools) {
                if (p.isAlive()) alive++;
            }
            // 0 alive → 0.0, 50% alive → 0.0, 100% alive → 1.0
            // Mapping: alive/total → (2*alive/total - 1)
            double pct = (double) alive / pools.size();
            return clamp(2.0 * pct - 1.0);
        }

        /**
         * Fraction of alive pools meeting their target tunnel count.
         * 1.0 = all pools meet target, 0.0 = half or more fall short.
         */
        private double scorePoolDeficit() {
            TunnelManagerFacade mgr = _ctx.tunnelManager();
            List<TunnelPool> pools = new ArrayList<>();
            mgr.listPools(pools);
            if (pools.isEmpty()) return Double.NaN;
            int alive = 0;
            int met = 0;
            for (TunnelPool p : pools) {
                if (!p.isAlive()) continue;
                alive++;
                int target = p.getSettings().getTotalQuantity();
                // within 1 of target is acceptable (build in flight)
                if (p.getActiveTunnelCount() >= target - 1) met++;
            }
            if (alive == 0) return 0.0;
            double pct = (double) met / alive;
            return clamp(2.0 * pct - 1.0);
        }

        /**
         * @return true if any transport reports firewalled status
         */
        private boolean isFirewalled() {
            CommSystemFacade.Status status = _ctx.commSystem().getStatus();
            return status == CommSystemFacade.Status.REJECT_UNSOLICITED
                || status.toString().contains("FIREWALLED");
        }

        /**
         * Fetch the event count for a stat using the 60s period.
         */
        private double getEventCount(String statName) {
            RateStat rs = _ctx.statManager().getRate(statName);
            if (rs == null) return 0;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return 0;
            return rate.getLastEventCount();
        }

        /**
         * Return lifetime event count for a stat.
         * Use for low-frequency stats where 60s window is too narrow.
         */
        private double getLifetimeEventCount(String statName) {
            RateStat rs = _ctx.statManager().getRate(statName);
            if (rs == null) return 0;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null) return 0;
            return rate.getLifetimeEventCount();
        }

        /**
         * Build storms: 0 concurrent = 1.0, >10 = degraded, >30 = 0.0
         */
        private double scoreBuildStorms() {
            RateStat rs = _ctx.statManager().getRate("tunnel.concurrentBuilds");
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() < 5) return Double.NaN;
            double avg = rate.getAverageValue();
            if (avg <= 0) return 1.0;
            // 0→1.0, 10→0.7, 30→0.0
            return clamp(1.0 - ((avg - 10) / 20.0));
        }

        /**
         * Transport send failure rate: failed sends vs total sends.
         * Uses event counts (each send = 1 event, each failure = 1 event).
         * Requires 500+ sends to skip noisy startup window.
         * <1% = 1.0, >10% = 0.0
         */
        private double scoreSendFailure() {
            double failures = getEventCount("transport.sendMessageFailureLifetime");
            double sends = getEventCount("transport.sendMessageSize");
            if (sends < 500) return Double.NaN;
            double failRate = failures / sends;
            // 0%→1.0, 1%→1.0, 10%→0.0
            return clamp(1.0 - ((failRate - 0.01) / 0.09));
        }

        /**
         * Message send latency (transport.sendProcessingTime).
         * <100ms = 1.0, >500ms = degraded, >5000ms = 0.0
         */
        private double scoreLatency() {
            RateStat rs = _ctx.statManager().getRate("transport.sendProcessingTime");
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() < 3) return Double.NaN;
            double avg = rate.getAverageValue();
            if (avg <= 100) return 1.0;
            // 100ms→1.0, 500ms→0.75, 5000ms→0.0
            return clamp(1.0 - ((avg - 100) / 4900.0));
        }

        /**
         * Streaming health: RTT at connection close is the primary signal.
         * High RTT = congestion or poor path quality.
         * Uses stream.con.lifetimeRTT (fires on every connection close).
         * Falls back to sends-before-ack ratio if RTT unavailable.
         */
        private double scoreStreaming() {
            // Primary: RTT from closed connections
            double rtt = getStatValue("stream.con.lifetimeRTT");
            if (!Double.isNaN(rtt)) {
                double conns = getLifetimeEventCount("stream.con.lifetimeMessagesSent");
                if (conns < 3) return Double.NaN;
                // <1000ms→1.0, 3000ms→0.5, >5000ms→0.0
                return clamp(1.0 - ((rtt - 1000) / 4000.0));
            }
            // Fallback: sends-before-ack (high = delayed acks)
            double sendsBeforeAck = getStatValue("stream.sendsBeforeAck");
            if (!Double.isNaN(sendsBeforeAck)) {
                double msgs = getLifetimeEventCount("stream.con.lifetimeMessagesSent");
                if (msgs < 3) return Double.NaN;
                // <2→1.0, >10→0.0
                return clamp(1.0 - ((sendsBeforeAck - 2) / 8.0));
            }
            return Double.NaN;
        }

        private static double clamp(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }

        /**
         * Compute per-subsystem health scores for the ring chart dashboard.
         * Each subsystem gets a score based on its most relevant stat(s).
         *
         * @return map of subsystem key → {score, ...detail}
         * @since 0.9.70+
         */
        Map<String, double[]> computeSubsystemScores() {
            Map<String, double[]> scores = new LinkedHashMap<String, double[]>();

            // Router: job lag + memory
            double memUsedPct = getStatValue("jobQueue.memoryUsedPercent");
            scores.put(SUB_ROUTER, new double[] {
                scoreJobLag(),
                Double.isNaN(memUsedPct) ? Double.NaN : memUsedPct / 100.0
            });

            // Tunnel: build success + pool health + deficit
            scores.put(SUB_TUNNEL, new double[] {
                Math.min(Math.min(scoreBuildSuccess(), scorePoolAlive()), scorePoolDeficit())
            });

            // Transport: latency + send failure rate
            double latency = scoreLatency();
            double sendFail = scoreSendFailure();
            scores.put(SUB_TRANSPORT, new double[] {
                Math.min(latency, sendFail),
                latency,
                sendFail
            });

            // NetDB: lookup success rate
            scores.put(SUB_NETDB, new double[] {
                scoreNetDbLookup()
            });

            // Streaming: RTT + send-before-ack as health signals
            double streamScore = scoreStreaming();
            scores.put(SUB_STREAMING, new double[] { streamScore });

            // I2CP: queue utilization
            scores.put(SUB_I2CP, new double[] {
                scoreI2cpQueue()
            });

            // Peers: active profiles vs capacity
            scores.put(SUB_PEER, new double[] {
                scorePeerHealth()
            });

            // Congestion: BW throttle + congestion events
            scores.put(SUB_CONGESTION, new double[] {
                scoreCongestion()
            });

            // Crypto: DH queue pressure
            scores.put(SUB_CRYPTO, new double[] {
                scoreCryptoPressure()
            });

            return scores;
        }

        /**
         * NetDB health: known peer count.
         * 500+ peers = healthy, below that linearly degraded to 0 at 0 peers.
         * Penalized by netDb.replyTimeout (peers not responding to our sends).
         * Falls back to netDb.lookupWithTimeoutSuccess ratio if available.
         */
        private double scoreNetDbLookup() {
            double known = getStatValue("router.knownPeers");
            if (!Double.isNaN(known) && known >= 0) {
                double score = clamp(known / 500.0);
                // Reply timeouts indicate peer unresponsiveness — penalize
                double timeouts = getLifetimeEventCount("netDb.replyTimeout");
                if (timeouts > 50) {
                    score *= 0.8;
                }
                return score;
            }
            // Fallback: blocking lookup success rate
            double successEvents = getEventCount("netDb.lookupWithTimeoutSuccess");
            double failEvents = getEventCount("netDb.lookupWithTimeoutFail");
            double total = successEvents + failEvents;
            if (total >= 3) {
                return clamp(successEvents / total);
            }
            return Double.NaN;
        }

        /**
         * I2CP internal queue utilization.
         * Empty queue = 1.0, >80% capacity = 0.0
         * No I2CP traffic in the period is healthy (idle).
         * Cross-refs: i2ptunnel.serverHandler.queueDepth (server-side backlog).
         */
        private double scoreI2cpQueue() {
            double used = getStatValue("i2cp.internalQueueSize");
            if (Double.isNaN(used)) return 1.0;
            if (used <= 0) return 1.0;
            // used is the average queue size; capacity is ~65536 (internal default)
            double ratio = used / 65536.0;
            double score = clamp(1.0 - (ratio / 0.8));
            // Server handler backlog indicates client-side bottleneck
            double handlerDepth = getStatValue("i2ptunnel.serverHandler.queueDepth");
            if (!Double.isNaN(handlerDepth) && handlerDepth > 50) {
                score *= 0.7;
            }
            return score;
        }

        /**
         * Peer health: fast and high-capacity peer counts relative to their minimums.
         * Takes the minimum of both ratios so the ring shows the more-starved tier.
         * Returns NaN when peer profiling data unavailable.
         */
        private double scorePeerHealth() {
            double fast = getStatValue("peer.fastPeerCount");
            double highCap = getStatValue("peer.highCapPeerCount");
            if (Double.isNaN(fast) || Double.isNaN(highCap)) return Double.NaN;
            int minFast = ProfileOrganizer.getDefaultMinFastPeers();
            int minHighCap = ProfileOrganizer.getMinHighCapacityPeers();
            double fastScore = minFast > 0 ? clamp(fast / minFast) : 1.0;
            double highCapScore = minHighCap > 0 ? clamp(highCap / minHighCap) : 1.0;
            return Math.min(fastScore, highCapScore);
        }

        /**
         * Congestion: congestion event frequency vs total sends.
         * Low congestion ratio = 1.0, high = 0.0
         * Requires at least100 sends in the period for a stable ratio.
         */
        private double scoreCongestion() {
            double congEvents = getEventCount("udp.congestionOccurred");
            double totalSends = getEventCount("udp.sendPacketSize");
            if (totalSends < 100) return Double.NaN;
            double congRatio = congEvents / totalSends;
            // 0% congestion → 1.0, 5% → 0.0
            return clamp(1.0 - (congRatio / 0.05));
        }

        /**
         * Crypto: DH queue empty events vs used events.
         * Frequent empty queue = key generation bottleneck.
         * Only evaluates pools with actual activity; returns NaN when no crypto ops.
         */
        private double scoreCryptoPressure() {
            double bestScore = Double.NaN;
            // Score each pool independently, take the worst of active pools only
            double[][] pools = new double[][] {
                {getEventCount("crypto.EDHEmpty"), getEventCount("crypto.EDHUsed")},
                {getEventCount("crypto.XDHEmpty"), getEventCount("crypto.XDHUsed")},
                {getEventCount("crypto.MLKEMEmpty"), getEventCount("crypto.MLKEMUsed")}
            };
            for (double[] pool : pools) {
                double empty = pool[0];
                double used = pool[1];
                if (used <= 0) continue;
                double poolScore = clamp(1.0 - ((empty / used) / 0.3));
                if (Double.isNaN(bestScore) || poolScore < bestScore) {
                    bestScore = poolScore;
                }
            }
            return bestScore;
        }

        /**
         * Read a stat's average value using the 60s period.
         * Returns NaN if stat unavailable.
         */
        private double getStatValue(String statName) {
            RateStat rs = _ctx.statManager().getRate(statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
    }

    /**
     * Tunes the minimum per-participant transit tunnel limit in ParticipatingThrottler.
     * Raises when we have capacity (high build success, low CPU), lowers under stress.
     */
    private class ParticipatingThrottleMinParam extends BaseParam {
        ParticipatingThrottleMinParam() {
            super("i2p.tunnel.participatingThrottle.minLimit", "Transit throttle min (tunnels)",
                  SUB_TUNNEL, 20, 500, 4, "tunnel.buildSuccessRate", _context);
        }
        protected void applyValue(int value) { ParticipatingThrottler.setParticipatingMinLimit(value); }
        protected int getRuntimeValue() { return ParticipatingThrottler.getParticipatingMinLimit(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            // Note: throttleParticipating{Accept,Reject,Drop} are event-count stats
            //       (addRateData(..., 1)); use getAdditionalEventCount() for raw counts.
            double accept = getAdditionalEventCount(_context, "tunnel.throttleParticipatingAccept");
            double reject = getAdditionalEventCount(_context, "tunnel.throttleParticipatingReject");
            double drop = getAdditionalEventCount(_context, "tunnel.throttleParticipatingDrop");
            double bwQueue = getAdditionalStat(_context, "bwLimiter.participatingBandwidthQueue");
            boolean bwPressure = !Double.isNaN(bwQueue) && bwQueue > 50000;
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasCapacity = !Double.isNaN(observed) && observed > 90 && !cpuPressure && !bwPressure;
            boolean stressed = (!Double.isNaN(observed) && observed < 70) || cpuPressure || bwPressure;
            double totalThrottle = (!Double.isNaN(accept) && !Double.isNaN(reject) && !Double.isNaN(drop))
                ? accept + reject + drop : Double.NaN;
            double acceptRatio = !Double.isNaN(totalThrottle) && totalThrottle > 0
                ? accept / totalThrottle : Double.NaN;
            boolean tightThrottle = !Double.isNaN(acceptRatio) && acceptRatio < 0.3;
            // Don't decrease when throttle is already tight — that's the feedback loop
            if (tightThrottle) return Math.min(_max, current + _step);
            if (hasCapacity) return Math.min(_max, current + _step);
            if (stressed) return Math.max(_min, current - _step);
            return current;
        }
    }

    /**
     * Tunes the maximum per-participant transit tunnel limit in ParticipatingThrottler.
     */
    private class ParticipatingThrottleMaxParam extends BaseParam {
        ParticipatingThrottleMaxParam() {
            super("i2p.tunnel.participatingThrottle.maxLimit", "Transit throttle max (tunnels)",
                  SUB_TUNNEL, 50, 1000, 8, "tunnel.buildSuccessRate", _context);
        }
        protected void applyValue(int value) { ParticipatingThrottler.setParticipatingMaxLimit(value); }
        protected int getRuntimeValue() { return ParticipatingThrottler.getParticipatingMaxLimit(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double accept = getAdditionalEventCount(_context, "tunnel.throttleParticipatingAccept");
            double reject = getAdditionalEventCount(_context, "tunnel.throttleParticipatingReject");
            double drop = getAdditionalEventCount(_context, "tunnel.throttleParticipatingDrop");
            double bwQueue = getAdditionalStat(_context, "bwLimiter.participatingBandwidthQueue");
            boolean bwPressure = !Double.isNaN(bwQueue) && bwQueue > 50000;
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasCapacity = !Double.isNaN(observed) && observed > 90 && !cpuPressure && !bwPressure;
            boolean stressed = (!Double.isNaN(observed) && observed < 70) || cpuPressure || bwPressure;
            double totalThrottle = (!Double.isNaN(accept) && !Double.isNaN(reject) && !Double.isNaN(drop))
                ? accept + reject + drop : Double.NaN;
            double acceptRatio = !Double.isNaN(totalThrottle) && totalThrottle > 0
                ? accept / totalThrottle : Double.NaN;
            boolean tightThrottle = !Double.isNaN(acceptRatio) && acceptRatio < 0.3;
            if (tightThrottle) return Math.min(_max, current + _step);
            if (hasCapacity) return Math.min(_max, current + _step);
            if (stressed) return Math.max(_min, current - _step);
            return current;
        }
    }

    /**
     * Tunes the percentage-based transit tunnel limit in ParticipatingThrottler.
     */
    private class ParticipatingThrottlePctParam extends BaseParam {
        ParticipatingThrottlePctParam() {
            super("i2p.tunnel.participatingThrottle.percentLimit", "Transit throttle target (%)",
                  SUB_TUNNEL, 5, 100, 1, "tunnel.buildSuccessRate", _context);
        }
        protected void applyValue(int value) { ParticipatingThrottler.setParticipatingPctLimit(value); }
        protected int getRuntimeValue() { return ParticipatingThrottler.getParticipatingPctLimit(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double accept = getAdditionalEventCount(_context, "tunnel.throttleParticipatingAccept");
            double reject = getAdditionalEventCount(_context, "tunnel.throttleParticipatingReject");
            double drop = getAdditionalEventCount(_context, "tunnel.throttleParticipatingDrop");
            double bwQueue = getAdditionalStat(_context, "bwLimiter.participatingBandwidthQueue");
            boolean bwPressure = !Double.isNaN(bwQueue) && bwQueue > 50000;
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasCapacity = !Double.isNaN(observed) && observed > 90 && !cpuPressure && !bwPressure;
            boolean stressed = (!Double.isNaN(observed) && observed < 70) || cpuPressure || bwPressure;
            double totalThrottle = (!Double.isNaN(accept) && !Double.isNaN(reject) && !Double.isNaN(drop))
                ? accept + reject + drop : Double.NaN;
            double acceptRatio = !Double.isNaN(totalThrottle) && totalThrottle > 0
                ? accept / totalThrottle : Double.NaN;
            boolean tightThrottle = !Double.isNaN(acceptRatio) && acceptRatio < 0.3;
            if (tightThrottle) return Math.min(_max, current + _step);
            if (hasCapacity) return Math.min(_max, current + _step);
            if (stressed) return Math.max(_min, current - _step);
            return current;
        }
    }

    /**
     * Tunes the minimum per-peer request throttle limit.
     */
    private class RequestThrottleMinParam extends BaseParam {
        RequestThrottleMinParam() {
            super("i2p.tunnel.requestThrottle.minLimit", "Build request throttle min (ms)",
                  SUB_TUNNEL, 1, 100, 2, "tunnel.throttleParticipatingReject", _context);
        }
        protected void applyValue(int value) { RequestThrottler.setRequestMinLimit(value); }
        protected int getRuntimeValue() { return RequestThrottler.getRequestMinLimit(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            int numTunnels = _context.tunnelManager().getParticipatingCount();
            int maxTunnels = net.i2p.router.RouterThrottleImpl.getDefaultMaxTunnels();
            double utilization = (double) numTunnels / Math.max(1, maxTunnels);
            boolean lowUtilization = utilization < 0.3;
            boolean hasCapacity = lowUtilization && !cpuPressure;
            boolean rejecting = !Double.isNaN(observed) && observed > 0;
            if (hasCapacity) return Math.min(_max, current + _step * 2);
            if (rejecting || cpuPressure) return Math.max(_min, current - _step);
            return current;
        }
    }

    /**
     * Tunes the maximum per-peer request throttle limit.
     */
    private class RequestThrottleMaxParam extends BaseParam {
        RequestThrottleMaxParam() {
            super("i2p.tunnel.requestThrottle.maxLimit", "Build request throttle max (ms)",
                  SUB_TUNNEL, 10, 1000, 8, "tunnel.throttleParticipatingReject", _context);
        }
        protected void applyValue(int value) { RequestThrottler.setRequestMaxLimit(value); }
        protected int getRuntimeValue() { return RequestThrottler.getRequestMaxLimit(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            int numTunnels = _context.tunnelManager().getParticipatingCount();
            int maxTunnels = net.i2p.router.RouterThrottleImpl.getDefaultMaxTunnels();
            double utilization = (double) numTunnels / Math.max(1, maxTunnels);
            boolean lowUtilization = utilization < 0.3;
            boolean hasCapacity = lowUtilization && !cpuPressure;
            boolean rejecting = !Double.isNaN(observed) && observed > 0;
            // Raise ceiling faster when underutilized
            if (hasCapacity) return Math.min(_max, current + _step * 2);
            if (rejecting || cpuPressure) return Math.max(_min, current - _step);
            return current;
        }
    }

    /**
     * Tunes the percentage-based request throttle limit.
     * When utilization is low, raise faster to ensure limits keep pace
     * with the capacity factor in RequestThrottle.
     */
    private class RequestThrottlePctParam extends BaseParam {
        RequestThrottlePctParam() {
            super("i2p.tunnel.requestThrottle.percentLimit", "Build request throttle target (%)",
                  SUB_TUNNEL, 1, 100, 1, "tunnel.throttleParticipatingReject", _context);
        }
        protected void applyValue(int value) { RequestThrottler.setRequestPctLimit(value); }
        protected int getRuntimeValue() { return RequestThrottler.getRequestPctLimit(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            int numTunnels = _context.tunnelManager().getParticipatingCount();
            int maxTunnels = net.i2p.router.RouterThrottleImpl.getDefaultMaxTunnels();
            double utilization = (double) numTunnels / Math.max(1, maxTunnels);
            boolean lowUtilization = utilization < 0.3;
            boolean hasCapacity = lowUtilization && !cpuPressure;
            boolean rejecting = !Double.isNaN(observed) && observed > 0;
            if (hasCapacity) return Math.min(_max, current + _step * 2);
            if (rejecting || cpuPressure) return Math.max(_min, current - _step);
            return current;
        }
    }

    /**
     * Tunes the 1-second burst detection threshold in RequestThrottler.
     * Higher = less sensitive (fewer false positives). Lower = more aggressive (more bans).
     * Relax when healthy, tighten under stress.
     */
    private class RequestThrottleBurstParam extends BaseParam {
        RequestThrottleBurstParam() {
            super("i2p.tunnel.requestThrottle.burst1sThreshold", "Build request burst threshold",
                  SUB_TUNNEL, 5, 20, 1, "tunnel.throttleParticipatingReject", _context);
        }
        protected void applyValue(int value) { RequestThrottler.setRequestBurst1sThreshold(value); }
        protected int getRuntimeValue() { return RequestThrottler.getRequestBurst1sThreshold(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            int numTunnels = _context.tunnelManager().getParticipatingCount();
            int maxTunnels = net.i2p.router.RouterThrottleImpl.getDefaultMaxTunnels();
            double utilization = (double) numTunnels / Math.max(1, maxTunnels);
            boolean lowUtilization = utilization < 0.3;
            boolean hasCapacity = lowUtilization && !cpuPressure;
            boolean rejecting = !Double.isNaN(observed) && observed > 0;
            if (hasCapacity) return Math.min(_max, current + _step * 2);
            if (rejecting || cpuPressure) return Math.max(_min, current - _step);
            return current;
        }
    }

    /**
     * Tunes the high-load job lag threshold in RequestThrottler.
     * Higher = more tolerant (only rejects under severe lag).
     * Lower = more sensitive (rejects sooner under moderate lag).
     * Sustained load detection requires this threshold to persist for sustainedHighLoadMs.
     *
     * @since 0.9.70+
     */
    private class RequestHighLoadLagParam extends BaseParam {
        RequestHighLoadLagParam() {
            super("i2p.tunnel.requestThrottle.highLoadLagMs", "High-load lag threshold (ms)",
                  SUB_TUNNEL, 200, 5000, 100, "jobQueue.jobLag", _context);
        }
        protected void applyValue(int value) { RequestThrottler.setHighLoadLagMs(value); }
        protected int getRuntimeValue() { return RequestThrottler.getHighLoadLagMs(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = avg jobLag (ms)
            // When job lag is consistently high, tighten the threshold to catch problems sooner
            // When job lag is low, loosen to avoid false positives
            if (!Double.isNaN(observed) && observed > 200 && current > _min)
                return Math.max(_min, current - _step);
            if (!Double.isNaN(observed) && observed < 50 && current < _max)
                return Math.min(_max, current + _step);
            return current;
        }
    }

    /**
     * Tunes the high-load CPU threshold in RequestThrottler.
     *
     * @since 0.9.70+
     */
    private class RequestHighLoadCpuParam extends BaseParam {
        RequestHighLoadCpuParam() {
            super("i2p.tunnel.requestThrottle.highLoadCpuPct", "High-load CPU threshold (%)",
                  SUB_TUNNEL, 50, 100, 1, "jobQueue.jobLag", _context);
        }
        protected void applyValue(int value) { RequestThrottler.setHighLoadCpuPct(value); }
        protected int getRuntimeValue() { return RequestThrottler.getHighLoadCpuPct(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // Hold steady — CPU threshold is system-dependent, not load-reactive
            return current;
        }
    }

    /**
     * Tunes the moderate-load job lag threshold in RequestThrottler.
     * Controls when low-share peers start being disconnected.
     *
     * @since 0.9.70+
     */
    private class RequestModerateLoadLagParam extends BaseParam {
        RequestModerateLoadLagParam() {
            super("i2p.tunnel.requestThrottle.moderateLoadLagMs", "Moderate-load lag threshold (ms)",
                  SUB_TUNNEL, 100, 3000, 50, "jobQueue.jobLag", _context);
        }
        protected void applyValue(int value) { RequestThrottler.setModerateLoadLagMs(value); }
        protected int getRuntimeValue() { return RequestThrottler.getModerateLoadLagMs(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // Keep moderate threshold roughly half of high threshold
            int highLag = RequestThrottler.getHighLoadLagMs();
            int target = Math.max(_min, Math.min(_max, highLag / 2));
            if (current < target && current < _max) return Math.min(_max, current + _step);
            if (current > target && current > _min) return Math.max(_min, current - _step);
            return current;
        }
    }

    /**
     * Tunes the moderate-load CPU threshold in RequestThrottler.
     *
     * @since 0.9.70+
     */
    private class RequestModerateLoadCpuParam extends BaseParam {
        RequestModerateLoadCpuParam() {
            super("i2p.tunnel.requestThrottle.moderateLoadCpuPct", "Moderate-load CPU threshold (%)",
                  SUB_TUNNEL, 40, 100, 1, "jobQueue.jobLag", _context);
        }
        protected void applyValue(int value) { RequestThrottler.setModerateLoadCpuPct(value); }
        protected int getRuntimeValue() { return RequestThrottler.getModerateLoadCpuPct(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            return current;
        }
    }

    /**
     * Tunes how long high load must persist before gating requests.
     * Higher = more tolerant (waits longer before rejecting).
     * Lower = more responsive (rejects sooner under sustained load).
     *
     * @since 0.9.70+
     */
    private class RequestSustainedHighLoadParam extends BaseParam {
        RequestSustainedHighLoadParam() {
            super("i2p.tunnel.requestThrottle.sustainedHighLoadMs", "Sustained high-load window (ms)",
                  SUB_TUNNEL, 5000, 120_000, 5000, "jobQueue.jobLag", _context);
        }
        protected void applyValue(int value) { RequestThrottler.setSustainedHighLoadMs(value); }
        protected int getRuntimeValue() { return (int) RequestThrottler.getSustainedHighLoadMs(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = !Double.isNaN(jobLag) && jobLag > 500 && sysLoad > 70;
            // Under sustained high load, shorten window for faster reaction
            if (highLoad && current > _min) return Math.max(_min, current - _step * 2);
            // When idle, lengthen window to avoid reacting to transient spikes
            if (!highLoad && current < _max) return Math.min(_max, current + _step);
            return current;
        }
    }

    /**
     * Tunes how long moderate load must persist before disconnecting low-share peers.
     *
     * @since 0.9.70+
     */
    private class RequestSustainedModerateLoadParam extends BaseParam {
        RequestSustainedModerateLoadParam() {
            super("i2p.tunnel.requestThrottle.sustainedModerateLoadMs", "Sustained moderate-load window (ms)",
                  SUB_TUNNEL, 10_000, 300_000, 5000, "jobQueue.jobLag", _context);
        }
        protected void applyValue(int value) { RequestThrottler.setSustainedModerateLoadMs(value); }
        protected int getRuntimeValue() { return (int) RequestThrottler.getSustainedModerateLoadMs(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean moderateLoad = !Double.isNaN(jobLag) && jobLag > 200 && sysLoad > 50;
            // Moderate window should be ~2x the high window
            int highWindow = (int) RequestThrottler.getSustainedHighLoadMs();
            int target = Math.max(_min, Math.min(_max, highWindow * 2));
            if (current < target && current < _max) return Math.min(_max, current + _step);
            if (current > target && current > _min) return Math.max(_min, current - _step);
            return current;
        }
    }

    /**
     * Tunes the consecutive failure threshold for pool backoff.
     * Higher = more tolerant (pools keep building through failures).
     * Lower = more sensitive (backoff kicks in sooner).
     */
    private class PoolFailureThresholdParam extends BaseParam {
        PoolFailureThresholdParam() {
            super("tunnel.pool.failureThreshold", "Pool failure threshold (count)",
                  SUB_TUNNEL, 1, 20, 1, "tunnel.buildSuccessRate", _context);
        }
        protected void applyValue(int value) { BuildExecutor.setPoolFailureThreshold(value); }
        protected int getRuntimeValue() { return BuildExecutor.getPoolFailureThreshold(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean healthy = !Double.isNaN(observed) && observed > 90 && !cpuPressure;
            boolean failing = !Double.isNaN(observed) && observed < 70;
            if (healthy) return Math.min(_max, current + 1);
            if (failing) return Math.max(_min, current - 1);
            return current;
        }
    }

    /**
     * Tunes the pool backoff duration.
     * Shorter = faster recovery after failures. Longer = more cooling during storms.
     */
    private class PoolBackoffMsParam extends BaseParam {
        PoolBackoffMsParam() {
            super("tunnel.pool.backoffMs", "Pool rebuild backoff (ms)",
                  SUB_TUNNEL, 1000, 60000, 2000, "tunnel.buildSuccessRate", _context);
        }
        protected void applyValue(int value) { BuildExecutor.setPoolBackoffMs(value); }
        protected int getRuntimeValue() { return (int) BuildExecutor.getPoolBackoffMs(); }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean healthy = !Double.isNaN(observed) && observed > 90 && !cpuPressure;
            boolean failing = !Double.isNaN(observed) && observed < 70;
            if (healthy) return Math.max(_min, current - 2000);
            if (failing) return Math.min(_max, current + 2000);
            return current;
        }
    }

    /**
     * Tunes the spare tunnel buffer above target for each pool.
     * Increases when build success is low or pools have deficit
     * (builds extra spares to prevent collapse). Decreases toward
     * zero when pools are healthy and at target (saves bandwidth).
     */
    private class TunnelTargetBufferParam extends BaseParam {
        TunnelTargetBufferParam() {
            super("i2p.tunnel.targetBuffer", "Pool spare tunnel buffer",
                  SUB_TUNNEL, 0, 2, 1, "tunnel.buildSuccessRate", _context);
        }
        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.tunnel.targetBuffer", Integer.toString(value));
        }
        protected int getRuntimeValue() {
            return _context.getProperty("i2p.tunnel.targetBuffer", 0);
        }
        protected double getObservedStat(RouterContext ctx) {
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }
        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean healthy = !Double.isNaN(observed) && observed > 80 && !cpuPressure;
            boolean failing = !Double.isNaN(observed) && observed < 60;

            // Check pool deficits
            TunnelManagerFacade mgr = _context.tunnelManager();
            boolean anyDeficit = false;
            List<TunnelPool> pools = new ArrayList<>();
            mgr.listPools(pools);
            for (TunnelPool p : pools) {
                if (!p.isAlive()) continue;
                int target = p.getSettings().getTotalQuantity();
                if (p.getActiveTunnelCount() < target) {
                    anyDeficit = true;
                    break;
                }
            }

            // Failing or deficit = increase buffer (more spares prevent collapse)
            if (failing || anyDeficit)
                return Math.min(_max, current + 1);

            // Healthy and no deficit = decrease buffer toward 0
            if (healthy && !anyDeficit)
                return Math.max(_min, current - 1);

            return current;
        }
    }
}
