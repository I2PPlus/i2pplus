package net.i2p.router;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
import net.i2p.router.tunnel.pool.TestJob;
import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.ntcp.NTCPConnection;
import net.i2p.router.transport.ntcp.Reader;
import net.i2p.router.transport.ntcp.Writer;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.networkdb.kademlia.IterativeSearchJob;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.util.CoDelBlockingQueue;
import net.i2p.router.util.CoDelPriorityBlockingQueue;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;

/**
 * General-purpose adaptive tuner. Observes network and system stats,
 * adjusts tunable parameters to optimize router performance.
 *
 * <p>Runs every 60 seconds via {@link SimpleTimer}. Each parameter
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
public class Tuner implements SimpleTimer.TimedEvent {

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
    public static final String SUB_CODEL = "CoDel";
    public static final String SUB_WESTWOOD = "Westwood";
    public static final String SUB_BUFFERS = "Buffers & Threads";
    public static final String SUB_ROUTER = "Router Core";
    public static final String SUB_NETDB = "NetDB";
    public static final String SUB_PEER = "Peer Management";

    // System capability factors for scaling defaults
    private static final long MAX_MEMORY = SystemVersion.getMaxMemory();
    private static final int CORES = SystemVersion.getCores();
    private static final boolean IS_SLOW = SystemVersion.isSlow();
    private static final int MEM_FACTOR = Math.max(1, (int)(MAX_MEMORY / (256L * 1024 * 1024)));
    private static final int CORE_FACTOR = Math.max(1, CORES);

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
        _context = ctx;
        _log = ctx.logManager().getLog(Tuner.class);
        _autotune = new AutotuneConfig(ctx);
        BaseParam._sharedAutotune = _autotune;
        _maxDispatchAgeMs = ctx.getProperty("i2p.router.maxDispatchAge", 3000);
        _handlerThreadPriority = ctx.getProperty("i2p.router.handlerThreadPriority", Thread.NORM_PRIORITY);
        _params = new ArrayList<TunableParam>(24);

        // Transport params
        _params.add(new AckFrequencyParam());
        _params.add(new DataMessageTimeoutParam());
        _params.add(new ObEstablishTimeParam());
        _params.add(new IbEstablishTimeParam());
        _params.add(new MaxDispatchAgeParam());
        _params.add(new HandlerThreadPriorityParam());

        // Tunnel params
        _params.add(new RequeueTimeParam());
        _params.add(new ReplenishFrequencyParam());
        _params.add(new SelectorLoopDelayParam());
        _params.add(new ObMsgsPerPumpParam());
        _params.add(new IbMsgsPerPumpParam());

        // Streaming params
        _params.add(new InitialWindowSizeParam());
        _params.add(new InitialRTOParam());
        _params.add(new InitialAckDelayParam());
        _params.add(new PassiveFlushDelayParam());
        _params.add(new MaxSlowStartWindowParam());

        // I2CP params
        _params.add(new WriterQueueSizeParam());

        // CoDel params
        _params.add(new CodelTargetParam());
        _params.add(new CodelIntervalParam());

        // Westwood params
        _params.add(new WestwoodDecayFactorParam());

        // Buffer & thread params
        _params.add(new XDHPreCalcMinParam());
        _params.add(new EDHPreCalcMinParam());
        _params.add(new MLKEMPreCalcMinParam());
        _params.add(new NTCPQueueCapacityParam());
        _params.add(new UDPHandlerThreadsParam());
        _params.add(new UDPMessageReceiverThreadsParam());
        _params.add(new PeerOutboundQueueParam());

        // Router core params
        _params.add(new TransitThrottleFactorParam());
        _params.add(new ThrottleRejectExponentParam());
        _params.add(new MaxParticipatingTunnelsParam());
        _params.add(new BuildHandlerMaxQueueParam());
        _params.add(new GoodDeficitThrottleParam());
        _params.add(new PerTunnelBweDivisorParam());
        _params.add(new TunnelGrowthFactorParam());

        // Streaming congestion params
        _params.add(new MaxRTOParam());
        _params.add(new MaxResendDelayParam());
        _params.add(new MaxRetransmissionsParam());
        _params.add(new MinResendDelayParam());
        _params.add(new CongestionAvoidanceGrowthParam());
        _params.add(new SlowStartGrowthParam());
        _params.add(new MaxRttParam());
        _params.add(new InitialResendDelayParam());
        _params.add(new ImmediateAckDelayParam());

        // NetDB params
        _params.add(new NetDBSearchLimitParam());
        _params.add(new NetDBMaxConcurrentParam());
        _params.add(new NetDBSingleSearchTimeParam());

        // Transport params
        _params.add(new MaxConcurrentEstablishParam());

        // Peer management params
        _params.add(new MaxProfilesParam());
        _params.add(new MinFastPeersParam());
        _params.add(new MaxFastPeersParam());
        _params.add(new MinHighCapPeersParam());
        _params.add(new MaxHighCapPeersParam());

        // Build timeout params
        _params.add(new BuildRequestTimeoutParam());
        _params.add(new BuildFirstHopTimeoutParam());
        _params.add(new MaxConcurrentBuildsParam());

        // NTCP thread params
        _params.add(new NtcpReaderThreadsParam());
        _params.add(new NtcpWriterThreadsParam());
        _params.add(new NtcpFailsafeFreqParam());
        _params.add(new MaxConcurrentMessagesParam());
        _params.add(new SendPoolCapacityParam());
        _params.add(new InternalQueueSizeParam());
        _params.add(new MaxQueuedOutboundParam());
        _params.add(new MaxWriteBufsParam());
        _params.add(new NtcpSendFinisherThreadsParam());

        // TestJob params
        _params.add(new TestJobMaxQueuedParam());
        _params.add(new TestJobMinDelayParam());
        _params.add(new TestJobMaxDelayParam());

        // Tunnel gateway pumper params
        _params.add(new PumperQueueCapacityParam());
        _params.add(new PumperThreadsParam());

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
            Map<String, String> removeMap = new HashMap<String, String>();
            for (String k : toRemove) { removeMap.put(k, null); }
            ctx.router().saveConfig(null, toRemove);
            if (_log.shouldInfo())
                _log.info("Purged " + toRemove.size() + " stale tuner.* keys from router.config");
        }
        ctx.router().saveConfig(markerKey, "true");
    }

    public void timeReached() {
        // Compute system health once per cycle, shared by all params
        SystemHealth health = new SystemHealth(_context);
        _lastHealthScore = health.getScore();
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
                bp._defaultValue = bp.getRuntimeValue();
                bp._autotune.setProperty(bp._name + ".default", String.valueOf(bp._defaultValue));
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
            if (existingDefault == null) {
                _defaultValue = runtimeDefault;
                _autotune.setProperty(defaultKey, String.valueOf(runtimeDefault));
                _autotune.setProperty(valueKey, String.valueOf(runtimeDefault));
            } else {
                _defaultValue = Integer.parseInt(existingDefault);
            }
            // Read persisted tuned value, or use factory default
            _initialValue = _autotune.getInt(valueKey, _defaultValue);
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
            _min = _autotune.getInt(name + ".min", _defaultMin);
            _max = _autotune.getInt(name + ".max", _defaultMax);
            _step = _autotune.getInt(name + ".step", _defaultStep);
            // Clamp loaded ranges to constructor limits (enforce setter constraints)
            // Prevents stale autotune.config values from exceeding actual setter limits
            _min = Math.max(_defaultMin, _min);
            _max = Math.min(_defaultMax, _max);
            if (_min > _max) {
                _min = _defaultMin;
                _max = _defaultMax;
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
            } catch (Exception e) {}
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
            super("ACK_FREQUENCY", SUB_TRANSPORT,
                  "Packets between ACKs (lower = more ACKs, less loss)",
                  50, 300, 10, "udp.sendConfirmTime", _context);
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
            //             udp.sendFailed (loss)
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double sendFailed = getAdditionalStat(_context, "udp.sendFailed");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasLoss = !Double.isNaN(sendFailed) && sendFailed > 0;

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
     * Tunes DATA_MESSAGE_TIMEOUT based on observed send confirm time.
     * Target: timeout = 3x observed p95 send confirm time, bounded min-max.
     */
    private class DataMessageTimeoutParam extends BaseParam {

        DataMessageTimeoutParam() {
            super("DATA_MESSAGE_TIMEOUT", SUB_TRANSPORT,
                  "DSM message expiration (ms)",
                  1000, 10000, 500, "transport.sendProcessingTime", _context);
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
            // observed = transport.sendProcessingTime (ms, how long a send+confirm takes)
            // Cross-refs: sendFailed (establish failures), sendMessageFailureLifetime (congestion),
            //             jobLag (CPU), participating InBps (transit load)
            double sendFailed = getAdditionalStat(_context, "udp.sendFailed");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");

            boolean hasSendFailures = !Double.isNaN(sendFailed) && sendFailed > 0;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 50;
            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > 50000;

            // Target: timeout = 3x observed confirm time, with minimum floor
            int target = Math.max(5000, (int) (observed * 3));

            // Dead zone: if current is already within 50% of target, hold steady
            if (current >= target * 0.5 && current <= target * 1.5 && !hasSendFailures && !congested)
                return current;

            // Never decrease when there are failures, congestion, or heavy transit
            if (target < current && (hasSendFailures || congested || heavyTransit))
                return current;

            // Never decrease below 5000ms (5s floor for I2P multi-hop)
            target = Math.max(5000, target);
            return clamp(current, target, _step);
        }
    }

        /**
         * Tunes MAX_OB_ESTABLISH_TIME based on outbound establish time stat.
         * Target: ~3x observed average outbound establish time with floor.
         */
    private class ObEstablishTimeParam extends BaseParam {

        ObEstablishTimeParam() {
            super("MAX_OB_ESTABLISH_TIME", SUB_TRANSPORT,
                  "Outbound establish timeout (ms)",
                  3000, 30000, 500, "udp.outboundEstablishTime", _context);
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
            // observed = udp.outboundEstablishTime (ms)
            // Cross-refs: sendFailed (establish failures), udp.sendConfirmTime (actual RTT),
            //             sendMessageFailureLifetime (congestion)
            double sendFailed = getAdditionalStat(_context, "udp.sendFailed");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean hasEstablishFailures = !Double.isNaN(sendFailed) && sendFailed > 0;
            boolean highRTT = !Double.isNaN(confirmTime) && confirmTime > 20000;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;

            // Target: 5x observed, but floor at 3000ms
            int target = Math.max(3000, (int) (observed * 5));

            // Dead zone: if current is already within 50% of target and no failures, hold
            if (current >= target * 0.5 && current <= target * 1.5 && !hasEstablishFailures)
                return current;

            // Never decrease when there are establish failures or network is slow
            if (target < current && (hasEstablishFailures || highRTT || congested))
                return current;

            return clamp(current, target, _step);
        }
    }

    /**
     * Tunes MAX_IB_ESTABLISH_TIME based on inbound establish time stat.
     * Target: ~3x observed average inbound establish time with floor.
     */
    private class IbEstablishTimeParam extends BaseParam {

        IbEstablishTimeParam() {
            super("MAX_IB_ESTABLISH_TIME", SUB_TRANSPORT,
                  "Inbound establish timeout (ms)",
                  3000, 20000, 500, "udp.inboundEstablishTime", _context);
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
            // observed = udp.inboundEstablishTime (ms)
            // Cross-refs: sendFailed (establish failures), udp.sendConfirmTime (actual RTT),
            //             sendMessageFailureLifetime (congestion)
            double sendFailed = getAdditionalStat(_context, "udp.sendFailed");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean hasEstablishFailures = !Double.isNaN(sendFailed) && sendFailed > 0;
            boolean highRTT = !Double.isNaN(confirmTime) && confirmTime > 20000;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;

            // Target: 5x observed, but floor at 3000ms
            int target = Math.max(3000, (int) (observed * 5));

            if (current >= target * 0.5 && current <= target * 1.5 && !hasEstablishFailures)
                return current;

            if (target < current && (hasEstablishFailures || highRTT || congested))
                return current;

            return clamp(current, target, _step);
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
            super("i2p.router.maxDispatchAge", SUB_TRANSPORT,
                  "Max message queue age (ms)",
                  500, 30000, 500, "transport.expiredOnQueueLifetime", _context);
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
            // Cross-refs: transport.bidFailAllTransports (messages with no transport)
            double bidFails = getAdditionalStat(_context, "transport.bidFailAllTransports");

            boolean lotsOfExpirations = observed > 5000;
            boolean lotsOfBidFails = !Double.isNaN(bidFails) && bidFails > 10;

            // Many expirations = lower age limit (stop hoarding dead messages)
            if (lotsOfExpirations)
                return Math.max(_min, current - _step);

            // Bid fails + expirations = lower age (can't route, stop trying)
            if (lotsOfBidFails && lotsOfExpirations)
                return Math.max(_min, current - _step * 2);

            // Few expirations + few bid fails = raise age limit (messages are being delivered)
            if (observed < 1000 && !lotsOfBidFails)
                return Math.min(_max, current + _step);

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
            super("i2p.router.handlerThreadPriority", SUB_TRANSPORT,
                  "I/O thread priority (1-10)",
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
            // Cross-refs: ntcp.readQueueSize, ntcp.sendFinisher.queueSize, jobQueue.jobLag
            double readQueue = getAdditionalStat(_context, "ntcp.readQueueSize");
            double finisherQueue = getAdditionalStat(_context, "ntcp.sendFinisher.queueSize");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean highLatency = observed > 200;
            boolean moderateLatency = observed > 100;
            boolean queuesBackedUp = (!Double.isNaN(readQueue) && readQueue > 5) ||
                                     (!Double.isNaN(finisherQueue) && finisherQueue > 5);
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 50;

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
            super("REQUEUE_TIME", SUB_TUNNEL,
                  "Pumper requeue delay (ms)",
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
            // Cross-refs: gateway overflow drops, queue sizes, job lag
            double obDrops = getAdditionalStat(_context, "tunnel.dropGatewayOverflowOB");
            double ibDrops = getAdditionalStat(_context, "tunnel.dropGatewayOverflowIB");
            double obQueue = getAdditionalStat(_context, "tunnel.obgw.queueSize");
            double ibQueue = getAdditionalStat(_context, "tunnel.ibgw.queueSize");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean hasDrops = (!Double.isNaN(obDrops) && obDrops > 0) ||
                               (!Double.isNaN(ibDrops) && ibDrops > 0);
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean queuesBackedUp = (!Double.isNaN(obQueue) && obQueue > 200) ||
                                     (!Double.isNaN(ibQueue) && ibQueue > 200);

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
            super("tunnel.pumper.queueCapacity", SUB_TUNNEL,
                  "Pumper queue capacity",
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
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.pumperQueueFull (drop events per minute)
            // Cross-refs: tunnel.ibgw/obgw.queueSize, tunnel.dispatchParticipant,
            //             jobQueue.jobLag (CPU)
            double ibgwQueue = getAdditionalStat(_context, "tunnel.ibgw.queueSize");
            double obgwQueue = getAdditionalStat(_context, "tunnel.obgw.queueSize");
            double transitLoad = getAdditionalStat(_context, "tunnel.dispatchParticipant");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasDrops = !Double.isNaN(observed) && observed > 0;
            boolean queuesBackedUp = (!Double.isNaN(ibgwQueue) && ibgwQueue > 50) ||
                                     (!Double.isNaN(obgwQueue) && obgwQueue > 50);
            boolean heavyTransit = !Double.isNaN(transitLoad) && transitLoad > 2000;

            // Drops or backed-up queues + no CPU pressure = grow queue
            if ((hasDrops || queuesBackedUp) && !cpuPressure)
                return Math.min(_max, current + _step);

            // Heavy transit + no pressure = grow proactively
            if (heavyTransit && !cpuPressure && current < _max / 2)
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
            super("tunnel.pumper.threads", SUB_TUNNEL,
                  "Pumper threads",
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

            // Queue depth high, drops, or backed up + no CPU = add threads
            if ((queueHigh || hasDrops || queuesBackedUp) && !cpuPressure)
                return Math.min(_max, current + 1);

            // Idle + no pressure = remove threads
            if (!queueHigh && !hasDrops && !queuesBackedUp && !cpuPressure && current > _min)
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
            super("REPLENISH_FREQUENCY", SUB_TUNNEL,
                  "Bandwidth token refill interval (ms)",
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
            super("SELECTOR_LOOP_DELAY", SUB_TUNNEL,
                  "NTCP selector sleep (ms)",
                  1, 100, 1, "ntcp.pumperLoopsPerSecond", _context);
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
            super("MAX_OB_MSGS_PER_PUMP", SUB_TUNNEL,
                  "Outbound gateway batch size",
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
            double drops = getAdditionalStat(_context, "tunnel.dropGatewayOverflowOB");
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
            super("MAX_IB_MSGS_PER_PUMP", SUB_TUNNEL,
                  "Inbound gateway batch size",
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
            double drops = getAdditionalStat(_context, "tunnel.dropGatewayOverflowIB");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();

            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > 50000;
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
            super("INITIAL_WINDOW_SIZE", SUB_STREAMING,
                  "Streaming initial congestion window",
                  1, 32, 4, "stream.con.initialRTT.in", _context);
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
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
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
            super("INITIAL_RTO", SUB_STREAMING,
                  "Streaming initial RTO (ms)",
                  1000, 30000, 500, "stream.con.initialRTT.out", _context);
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
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean spuriousRetransmits = !Double.isNaN(dupSize) && dupSize > 1000;
            boolean highRTT = !Double.isNaN(confirmTime) && confirmTime > 20000;

            // Target: 2x RTT as baseline (standard TCP-like behavior)
            int target = Math.max(2000, Math.min(10000, (int) (observed * 2)));

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
            super("INITIAL_ACK_DELAY", SUB_STREAMING,
                  "Streaming ACK delay (ms)",
                  1, 500, 5, "stream.sendsBeforeAck", _context);
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
            super("PASSIVE_FLUSH_DELAY", SUB_STREAMING,
                  "Streaming Nagle flush delay (ms)",
                  10, 300, 10, "stream.con.sendMessageSize", _context);
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
            super("i2p.streaming.maxSlowStartWindow", SUB_STREAMING,
                  "Streaming congestion window cap during slow start",
                  4, 256, 4, "stream.con.initialRTT.out", _context);
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
            super("CLIENT_WRITER_QUEUE_SIZE", SUB_I2CP,
                  "I2CP write queue size",
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
            // Cross-refs: client.writerQueueFull (overflow events), jobLag (CPU), sendProcessingTime
            double overflows = getAdditionalStat(_context, "client.writerQueueFull");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double sendTime = getAdditionalStat(_context, "transport.sendProcessingTime");
            double hourlyOverflows = getAdditionalStatHourly(_context, "client.writerQueueFull");
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasOverflows = !Double.isNaN(overflows) && overflows > 0;
            boolean sustainedOverflows = !Double.isNaN(hourlyOverflows) && hourlyOverflows > 2;
            boolean highRTT = !Double.isNaN(observed) && observed > 20000;
            boolean moderateRTT = !Double.isNaN(observed) && observed > 10000;
            boolean lowLatency = !Double.isNaN(sendTime) && sendTime < 50;

            // Overflow or sustained overflows + headroom = increase queue
            if ((hasOverflows || sustainedOverflows) && !systemBusy)
                return Math.min(_max, current + _step);

            // High RTT + no CPU pressure = increase queue (clients back up on slow links)
            if (highRTT && !systemBusy)
                return Math.min(_max, current + _step);

            // Moderate RTT + internal processing slow = increase queue
            if (moderateRTT && !Double.isNaN(sendTime) && sendTime > 100 && !systemBusy)
                return Math.min(_max, current + Math.max(1, _step / 2));

            // Low latency + no overflows = decrease queue (save memory, reduce buffering)
            if (lowLatency && !hasOverflows && !sustainedOverflows && !systemBusy && current > _min)
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
                  SUB_CODEL, 1, 10, 1, "codel.UDP-Sender.delay", _context);
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
            // Cross-refs: codel.UDP-Sender.drop (drop rate), jobLag (CPU)
            double drops = getAdditionalStat(_context, "codel.UDP-Sender.drop");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean hasDrops = !Double.isNaN(drops) && drops > 5;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            // High delay + drops = bufferbloat, lower target for more aggressive dropping
            if (observed > 5 && hasDrops)
                return Math.max(_min, current - _step);

            // High system load = lower target for more aggressive dropping
            if (highLoad)
                return Math.max(_min, current - _step);

            // Low delay + no drops + system not loaded = raise target (less aggressive, things are fine)
            if (observed < 2 && !hasDrops && !systemBusy && !highLoad)
                return Math.min(_max, current + _step);

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
                  SUB_CODEL, 20, 200, 10, "codel.UDP-Sender.delay", _context);
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
            // Cross-refs: codel.UDP-Sender.drop (drop rate), jobLag (CPU)
            double drops = getAdditionalStat(_context, "codel.UDP-Sender.drop");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean hasDrops = !Double.isNaN(drops) && drops > 5;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            // High delay + drops = shorten interval for faster reaction
            if (observed > 10 && hasDrops)
                return Math.max(_min, current - _step);

            // High system load = shorten for more aggressive congestion response
            if (highLoad)
                return Math.max(_min, current - _step);

            // Low delay + no drops + system not loaded = lengthen interval (smoother)
            if (observed < 5 && !hasDrops && !systemBusy && !highLoad)
                return Math.min(_max, current + _step);

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
                  SUB_WESTWOOD, 2, 16, 1, "transport.sendProcessingTime", _context);
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

    // =====================================================================
    // Buffer & Thread params
    // =====================================================================

    /**
     * X25519 key precalculation minimum pool size.
     * Scales up when usage is high, scales down when idle.
     */
    private class XDHPreCalcMinParam extends BaseParam {

        XDHPreCalcMinParam() {
            super("crypto.x25519.precalcMin", SUB_BUFFERS,
                  "Min precomputed X25519 key pairs",
                  8, 1024, 8, "crypto.XDHUsed", _context);
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
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = crypto.XDHUsed (key usage events/sec)
            // Cross-refs: jobLag (CPU pressure), crypto.XDHEmpty (empty pool events),
            //             memory pressure, system load
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double empties = getAdditionalStat(_context, "crypto.XDHEmpty");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean demandHigh = observed > 10;
            boolean poolEmptying = !Double.isNaN(empties) && empties > 0;

            // Severe memory pressure: shrink
            if (memPressure > 0.85)
                return Math.max(_min, current - _step * 2);

            // Moderate memory pressure: grow only if pool is emptying
            if (memPressure > 0.7) {
                if (poolEmptying && demandHigh)
                    return Math.min(_max, current + _step);
                return current;
            }

            // High usage + pool emptying = increase pool aggressively
            if (demandHigh && poolEmptying && !highLoad) {
                int step = (memPressure < 0.5 && sysLoad < 40) ? _step * 2 : _step;
                return Math.min(_max, current + step);
            }

            // High usage but pool not emptying yet = preemptive growth
            if (demandHigh && !highLoad && memPressure < 0.6)
                return Math.min(_max, current + Math.max(1, _step / 2));

            // Low usage + no emptying + tight resources = shrink slowly
            if (!demandHigh && !poolEmptying && (memPressure > 0.6 || highLoad))
                return Math.max(_min, current - 1);

            // Low usage + headroom = maintain or grow slightly for future demand
            if (!demandHigh && !highLoad && memPressure < 0.5)
                return Math.min(_max, current + 1);

            return current;
        }
    }

    /**
     * EDH (Elligator2) key precalculation minimum pool size.
     * Scales up when usage is high, scales down when idle.
     */
    private class EDHPreCalcMinParam extends BaseParam {

        EDHPreCalcMinParam() {
            super("crypto.edh.precalcMin", SUB_BUFFERS,
                  "Min precomputed EDH key pairs",
                  8, 1024, 8, "crypto.EDHUsed", _context);
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
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = crypto.EDHUsed (key usage events/sec)
            // Cross-refs: jobLag, crypto.EDHEmpty (empty pool events), memory, system load
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double empties = getAdditionalStat(_context, "crypto.EDHEmpty");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();
            boolean demandHigh = observed > 10;
            boolean poolEmptying = !Double.isNaN(empties) && empties > 0;

            if (memPressure > 0.85)
                return Math.max(_min, current - _step * 2);

            if (memPressure > 0.7) {
                if (poolEmptying && demandHigh)
                    return Math.min(_max, current + _step);
                return current;
            }

            if (demandHigh && poolEmptying && !highLoad) {
                int step = (memPressure < 0.5 && sysLoad < 40) ? _step * 2 : _step;
                return Math.min(_max, current + step);
            }

            if (demandHigh && !highLoad && memPressure < 0.6)
                return Math.min(_max, current + Math.max(1, _step / 2));

            if (!demandHigh && !poolEmptying && (memPressure > 0.6 || highLoad))
                return Math.max(_min, current - 1);

            if (!demandHigh && !highLoad && memPressure < 0.5)
                return Math.min(_max, current + 1);

            return current;
        }
    }

    /**
     * ML-KEM key precalculation minimum pool size.
     */
    private class MLKEMPreCalcMinParam extends BaseParam {

        MLKEMPreCalcMinParam() {
            super("crypto.mlkem.precalcMin", SUB_BUFFERS,
                  "Min precomputed ML-KEM key pairs",
                  2, 512, 4, "crypto.MLKEMEmpty", _context);
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
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = crypto.MLKEMEmpty (queue empty events/sec)
            // Cross-refs: jobLag (CPU pressure), memory pressure, system load
            // Hourly trend: confirm empty events trend isn't just a short-term spike
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyEmpties = getAdditionalStatHourly(_context, _statName);
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            boolean sustainedEmpties = !Double.isNaN(hourlyEmpties) && hourlyEmpties > 0;
            double memPressure = getMemoryPressure();

            if (memPressure > 0.85) {
                return Math.max(_min, current - _step * 2);
            }
            if (memPressure > 0.7) {
                if (observed > 0)
                    return Math.min(_max, current + _step);
                return current;
            }

            if (observed > 0 && !highLoad) {
                int step = (memPressure < 0.5 && sysLoad < 40) ? _step * 2 : _step;
                return Math.min(_max, current + step);
            }

            if (observed > 0 && sustainedEmpties)
                return Math.min(_max, current + _step);

            if (!highLoad && memPressure < 0.6)
                return Math.min(_max, current + Math.max(1, _step / 2));

            if (observed == 0 && !highLoad && memPressure < 0.5)
                return Math.min(_max, current + 1);

            if (observed == 0 && (memPressure > 0.6 || highLoad))
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * NTCP send finisher thread count. Scales with send path congestion.
     *
     * <p>Primary signal: {@code ntcp.sendTime} (ms, message lifetime in
     * finisher pipeline). Cross-refs: {@code ntcp.writeQueueFull},
     * {@code ntcp.sendQueueSize}, {@code jobQueue.jobLag}.
     *
     * <p>Increases when send bottleneck or queue overflow detected.
     * Decreases only under CPU pressure with low send time.
     *
     * @since 0.9.70+
     */
    private class NTCPThreadsParam extends BaseParam {

        NTCPThreadsParam() {
            super("ntcp.sendFinisher.maxThreads", SUB_BUFFERS,
                  "Max NTCP send finisher threads",
                  1, 64, 1, "ntcp.sendTime", _context);
        }

        protected void applyValue(int value) {
            NTCPTransport.setSendFinisherMaxThreads(value);
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
            // observed = ntcp.sendTime (ms, message lifetime in finisher pipeline)
            // Cross-refs: ntcp.writeQueueFull (overflow), ntcp.sendQueueSize (backlog), jobLag (CPU)
            double writeQueueFull = getAdditionalStat(_context, "ntcp.writeQueueFull");
            double sendQueueSize = getAdditionalStat(_context, "ntcp.sendQueueSize");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();

            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 50;
            boolean sendBottleneck = observed > 200 && !Double.isNaN(sendQueueSize) && sendQueueSize > 10;
            boolean queueOverflow = !Double.isNaN(writeQueueFull) && writeQueueFull > 0;

            // Emergency: bottleneck or overflow, no CPU pressure
            if ((sendBottleneck || queueOverflow) && !cpuPressure && !highLoad)
                return Math.min(_max, current + 1);

            // Proactive growth: no pressure, no overflow, send time low
            if (!cpuPressure && !highLoad && memPressure < 0.6 && queueOverflow
                && observed < 100)
                return Math.min(_max, current + 1);

            // Only shrink under real pressure: high CPU or system overloaded + send time low
            if ((cpuPressure || highLoad) && observed < 50 && !sendBottleneck && !queueOverflow)
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
            super("ntcp.sendFinisher.queueCapacity", SUB_BUFFERS,
                  "NTCP send queue capacity",
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
            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > 50000;
            boolean sustainedHeavyTransit = !Double.isNaN(hourlyBps) && hourlyBps > 40000;
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
            super("udp.packetHandler.maxThreads", SUB_BUFFERS,
                  "Max UDP packet handler threads",
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
            // Cross-refs: transit InBps, jobLag (CPU), memory, msgRx.queueSize (downstream)
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyBps = getAdditionalStatHourly(_context, "tunnel.participating InBps");
            double msgRxQueue = getAdditionalStat(_context, "udp.msgRx.queueSize");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();

            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > 50000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean sustainedHeavyTransit = !Double.isNaN(hourlyBps) && hourlyBps > 40000;
            boolean downstreamBackedUp = !Double.isNaN(msgRxQueue) && msgRxQueue > 50;

            // Any push time pressure + no CPU = add threads (max throughput)
            if (observed > 10 && !systemBusy && !highLoad)
                return Math.min(_max, current + 1);

            // Heavy transit + headroom = add threads proactively
            if (heavyTransit && !systemBusy && !highLoad && memPressure < 0.7)
                return Math.min(_max, current + 1);

            // Downstream backed up = add threads to drain faster
            if (downstreamBackedUp && !systemBusy && !highLoad)
                return Math.min(_max, current + 1);

            // Idle + no pressure = shrink (threads doing nothing useful)
            if (observed < 1 && !heavyTransit && !sustainedHeavyTransit && !downstreamBackedUp && current > _min)
                return Math.max(_min, current - 1);

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
            super("udp.messageReceiver.threads", SUB_BUFFERS,
                  "UDP message receiver threads",
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
            //             jobLag (CPU), udp.pushTime (downstream handler pressure)
            double expired = getAdditionalStat(_context, "udp.inboundExpired");
            double queueSize = getAdditionalStat(_context, "udp.msgRx.queueSize");
            double sojournTime = getAdditionalStat(_context, "codel.UDP-MessageReceiver.delay");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double pushTime = getAdditionalStat(_context, "udp.pushTime");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();

            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean upstreamBackpressure = observed > 5;
            boolean queueBackedUp = !Double.isNaN(queueSize) && queueSize > 50;
            boolean messagesExpiring = !Double.isNaN(expired) && expired > 0;
            boolean longSojourn = !Double.isNaN(sojournTime) && sojournTime > 2;
            boolean downstreamSlow = !Double.isNaN(pushTime) && pushTime > 10;

            // Any upstream pressure + no CPU = add threads (max throughput)
            if (upstreamBackpressure && !cpuPressure && !highLoad)
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
                && !longSojourn && !downstreamSlow && observed < 1 && current > _min)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * Per-peer outbound message queue size.
     * Scales with peer count and network load.
     */
    private class PeerOutboundQueueParam extends BaseParam {

        PeerOutboundQueueParam() {
            super("router.peerOutboundQueueSize", SUB_BUFFERS,
                  "Max outbound messages per peer",
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
            // Cross-refs: udp.rejectConcurrentActive (rejections), jobLag, memory
            double rejections = getAdditionalStat(_context, "udp.rejectConcurrentActive");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyRejections = getAdditionalStatHourly(_context, "udp.rejectConcurrentActive");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            double memPressure = getMemoryPressure();

            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasRejections = !Double.isNaN(rejections) && rejections > 0;
            boolean sustainedRejections = !Double.isNaN(hourlyRejections) && hourlyRejections > 5;
            boolean manyPeers = observed > 500;

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
            super("router.transitThrottleFactor", SUB_ROUTER,
                  "Transit throttle aggressiveness (0.0=disabled, 1.0=max drop)",
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
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
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
            super("router.throttleRejectExponent", SUB_ROUTER,
                  "Rejection curve steepness (higher = sharper cutoff)",
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
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = build success ratio (0.0-1.0)
            // Cross-refs: concurrentBuilds (storm), participating InBps (load)
            // Hourly trend: confirm build success trend isn't just a short-term dip
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double hourlySuccess = getAdditionalStatHourly(_context, _statName);

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
            super("router.tunnel.perTunnelBweDivisor", SUB_TUNNEL,
                  "Per-tunnel bandwidth divisor (lower = more per tunnel)",
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

            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
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
            super("router.tunnelGrowthFactor", SUB_TUNNEL,
                  "Tunnel growth tolerance (higher = accept more)",
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

            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
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
            super("router.maxParticipatingTunnels", SUB_ROUTER,
                  "Max transit tunnels",
                  500, 20000, 500, "tunnel.participating InBps", _context);
        }

        protected void applyValue(int value) {
            RouterThrottleImpl.setDefaultMaxTunnels(value);
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
            if (maxBps <= 0) return current;

            // Use actual tunnel count vs max as the trigger — not bandwidth.
            Rate avgTunnels = _context.statManager().getRate("tunnel.participatingTunnels") != null
                ? _context.statManager().getRate("tunnel.participatingTunnels").getRate(RateConstants.TEN_MINUTES)
                : null;
            double tunnelCount = (avgTunnels != null && avgTunnels.getLastEventCount() > 0)
                ? avgTunnels.getAverageValue() : 0;
            double countPct = current > 0 ? tunnelCount / current : 0;

            // Cross-refs: buildSuccessRate, sendMessageFailureLifetime, jobLag
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 8000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            // Tunnel count approaching max (>50%) + healthy = increase
            if (countPct > 0.5 && networkHealthy && !congested && !systemBusy)
                return Math.min(_max, current + _step);

            // Tunnel count approaching max (>70%) + healthy = increase aggressively
            if (countPct > 0.7 && networkHealthy && !congested && !systemBusy)
                return Math.min(_max, current + _step * 2);

            // Far below max + congested or unhealthy = decrease toward actual need
            if (countPct < 0.1 && (congested || !networkHealthy || systemBusy))
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Build handler max queue size.
     * Overflow drops tunnel build requests.
     */
    private class BuildHandlerMaxQueueParam extends BaseParam {

        BuildHandlerMaxQueueParam() {
            super("router.buildHandlerMaxQueue", SUB_ROUTER,
                  "Build handler queue",
                  16, 2048, 32, "jobQueue.jobLag", _context);
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
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
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
            super("i2p.tunnel.goodDeficitThrottle", SUB_ROUTER,
                  "Rebuild throttle when pools healthy (ms)",
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
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.buildSuccessRate (0.0-1.0)
            // Cross-refs: concurrentBuilds (storm), participating InBps (load),
            //             participatingMessageCountAvgPerTunnel (per-tunnel load)
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double msgsPerTunnel = getAdditionalStat(_context, "tunnel.participatingMessageCountAvgPerTunnel");

            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;
            boolean heavyLoad = !Double.isNaN(transitBps) && transitBps > 80000;
            boolean perTunnelHigh = !Double.isNaN(msgsPerTunnel) && msgsPerTunnel > 200;

            // Build storm + low success = actively decrease throttle (rebuild faster to fix pools)
            if (buildStorm && observed < 0.7)
                return Math.max(_min, current - _step);

            // Build storm but good success = hold (throttle is working)
            if (buildStorm) return current;

            // Low success + light load = decrease throttle (rebuild faster to fix pools)
            if (observed < 0.7 && !heavyLoad && !perTunnelHigh)
                return Math.max(_min, current - _step);

            // High success + low load = increase throttle (no need to rebuild often)
            if (observed > 0.95 && !heavyLoad && !perTunnelHigh)
                return Math.min(_max, current + _step);

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
            super("i2p.streaming.maxRTO", SUB_STREAMING,
                  "Max retransmission timeout (ms)",
                  1000, 120000, 1000, "udp.sendConfirmTime", _context);
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
            super("i2p.streaming.maxResendDelay", SUB_STREAMING,
                  "Max delay between retransmissions (ms)",
                  1000, 120000, 1000, "stream.con.initialRTT.out", _context);
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
            super("i2p.streaming.maxRetransmissions", SUB_STREAMING,
                  "Max retransmissions before giving up",
                  1, 256, 8, "stream.con.initialRTT.out", _context);
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
            super("i2p.streaming.minResendDelay", SUB_STREAMING,
                  "Min resend delay (ms)",
                  50, 10000, 50, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.minResendDelay", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.minResendDelay", 500);
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
            super("i2p.streaming.congestionAvoidanceGrowthRateFactor", SUB_STREAMING,
                  "CA growth rate (higher = faster)",
                  1, 16, 1, "stream.con.sendDuplicateSize", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.congestionAvoidanceGrowthRateFactor", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.congestionAvoidanceGrowthRateFactor", 1);
        }

        protected double getObservedStat(RouterContext ctx) {
            return getObservedRTT(_context, _statName);
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = stream.con.sendDuplicateSize (retransmit volume)
            // Cross-refs: sendMessageFailureLifetime (congestion), buildSuccessRate (network health),
            //             lifetimeRTT (completed stream RTT), lifetimeSendWindowSize (final window),
            //             chokeSizeBegin (choke pressure)
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double lifetimeRTT = getAdditionalStat(_context, "stream.con.lifetimeRTT");
            double lifetimeWindowSize = getAdditionalStat(_context, "stream.con.lifetimeSendWindowSize");
            double chokeSize = getAdditionalStat(_context, "stream.chokeSizeBegin");

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
            if (dropping || congested)
                return Math.max(recoveryFloor, current - _step);

            // Network unhealthy = slow growth
            if (!networkHealthy)
                return Math.max(recoveryFloor, current - _step);

            // Choking = decrease growth (too aggressive)
            if (choking)
                return Math.max(recoveryFloor, current - _step);

            // Dead zone: hold within 50%-200% of default unless signal is strong
            if (current >= recoveryFloor && current <= _defaultValue * 2 && !dropping && !congested && networkHealthy)
                return current;

            // Completed streams slow + windows small = increase growth (need faster ramp)
            if (streamsSlow && windowsSmall && !dropping && !congested)
                return Math.min(_max, current + _step);

            // Large window at congestion + healthy network + no drops = increase growth
            if (!Double.isNaN(lifetimeWindowSize) && lifetimeWindowSize > 20 && networkHealthy && !dropping && !congested)
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
            super("i2p.streaming.slowStartGrowthRateFactor", SUB_STREAMING,
                  "SS growth rate (higher = faster)",
                  1, 16, 1, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.slowStartGrowthRateFactor", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.slowStartGrowthRateFactor", 1);
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
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double dupSize = getAdditionalStat(_context, "stream.con.sendDuplicateSize");
            double lifetimeRTT = getAdditionalStat(_context, "stream.con.lifetimeRTT");
            double lifetimeWindowSize = getAdditionalStat(_context, "stream.con.lifetimeSendWindowSize");
            double chokeSize = getAdditionalStat(_context, "stream.chokeSizeBegin");

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
            if (dropping || congested)
                return Math.max(recoveryFloor, current - _step);

            // Network unhealthy = slow ramp
            if (!networkHealthy)
                return Math.max(recoveryFloor, current - _step);

            // Choking = decrease ramp (too aggressive)
            if (choking)
                return Math.max(recoveryFloor, current - _step);

            // Dead zone: hold within 50% of default unless signal is strong
            if (current >= recoveryFloor && current <= _defaultValue * 2 && !dropping && !congested && networkHealthy)
                return current;

            // Completed streams slow + windows small = increase ramp (need faster ramp)
            if (streamsSlow && windowsSmall && !dropping && !congested)
                return Math.min(_max, current + _step);

            // Below default: increase if healthy
            if (current < _defaultValue && networkHealthy && !dropping && !congested)
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
            super("i2p.streaming.maxRtt", SUB_STREAMING,
                  "RTT estimate cap (ms)",
                  1000, 120000, 5000, "stream.con.initialRTT.out", _context);
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
            super("i2p.streaming.initialResendDelay", SUB_STREAMING,
                  "First retransmit delay (ms)",
                  50, 10000, 50, "stream.con.initialRTT.out", _context);
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
            super("i2p.streaming.immediateAckDelay", SUB_STREAMING,
                  "Dup ACK delay (ms)",
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

    // =====================================================================
    // NetDB params
    // =====================================================================

    /**
     * NetDB search limit (max peers per iterative search).
     * Scales with network latency.
     */
    private class NetDBSearchLimitParam extends BaseParam {

        NetDBSearchLimitParam() {
            super("netdb.searchLimit", SUB_NETDB,
                  "Max peers per NetDB search",
                  1, 256, 2, "transport.sendProcessingTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("netdb.searchLimit", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("netdb.searchLimit", 24);
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
            // Cross-refs: netDb.lookupsMatched (match rate), jobLag (CPU)
            double matchRate = getAdditionalStat(_context, "netDb.lookupsMatched");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean networkSlow = observed > 300;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean lowMatch = !Double.isNaN(matchRate) && matchRate < 0.5;

            // Slow network + low match rate = increase search limit (peers need more probes)
            if (networkSlow && lowMatch && !systemBusy)
                return Math.min(_max, current + _step);

            // Fast network + high match rate = decrease limit (searches succeed with fewer peers)
            if (!networkSlow && !Double.isNaN(matchRate) && matchRate > 0.9)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * NetDB max concurrent searches.
     * Scales with network latency.
     */
    private class NetDBMaxConcurrentParam extends BaseParam {

        NetDBMaxConcurrentParam() {
            super("netdb.maxConcurrent", SUB_NETDB,
                  "Max concurrent NetDB searches",
                  1, 128, 1, "transport.sendProcessingTime", _context);
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
            // Cross-refs: netDb.lookupsMatched (match rate), jobLag (CPU)
            double matchRate = getAdditionalStat(_context, "netDb.lookupsMatched");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean networkSlow = observed > 300;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean lowMatch = !Double.isNaN(matchRate) && matchRate < 0.5;

            // Slow network + low match + CPU headroom = increase concurrency
            if (networkSlow && lowMatch && !systemBusy)
                return Math.min(_max, current + 1);

            // Fast network + high match = decrease concurrency (not needed)
            if (!networkSlow && !Double.isNaN(matchRate) && matchRate > 0.9)
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
            super("netdb.singleSearchTime", SUB_NETDB,
                  "Per-peer NetDB search timeout (ms)",
                  500, 60000, 500, "transport.sendProcessingTime", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("netdb.singleSearchTime", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("netdb.singleSearchTime", 6000);
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
            // Cross-refs: netDb.lookupsMatched (match rate), jobLag (CPU)
            double matchRate = getAdditionalStat(_context, "netDb.lookupsMatched");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean networkSlow = observed > 300;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean lowMatch = !Double.isNaN(matchRate) && matchRate < 0.5;

            // Slow network + low match = increase timeout (peers need more time)
            if (networkSlow && lowMatch && !systemBusy)
                return Math.min(_max, current + _step);

            // Fast network + high match = decrease timeout (searches complete quickly)
            if (!networkSlow && !Double.isNaN(matchRate) && matchRate > 0.9)
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
            super("i2np.udp.maxConcurrentEstablish", SUB_TRANSPORT,
                  "Max concurrent SSU session establishment",
                  8, 8192, 32, "udp.outboundEstablishTime", _context);
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
            boolean failing = !Double.isNaN(sendFailed) && sendFailed > 5;

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
            super("profileOrganizer.maxProfiles", SUB_PEER,
                  "Max peer profiles in RAM",
                  200, 8000, 200, "peer.activeProfileCount", _context);
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
            super("profileOrganizer.minFastPeers", SUB_PEER,
                  "Min fast-tier peers",
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
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
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
            super("profileOrganizer.maxFastPeers", SUB_PEER,
                  "Max fast-tier peers",
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
            double fastPeers = getAdditionalStat(_context, "peer.fastPeerCount");
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
            super("profileOrganizer.minHighCapacityPeers", SUB_PEER,
                  "Min high-capacity peers",
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
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
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
            super("profileOrganizer.maxHighCapacityPeers", SUB_PEER,
                  "Max high-capacity peers",
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
            super("i2p.tunnel.build.requestTimeout", SUB_ROUTER,
                  "Build request reply timeout (ms)",
                  5000, 15000, 1000, "tunnel.buildClientExpire", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.tunnel.build.requestTimeout", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return BuildRequestor.getRequestTimeout(_context);
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
            // observed = tunnel.buildClientExpire (count of timed-out builds in last minute)
            // Primary signal: direct count of builds that expired waiting for a reply.
            // If builds are timing out, we need MORE time (not less).
            // Cross-refs: concurrentBuilds (storm detection), dropLoadBacklog (pending build queue),
            //             buildSuccessRate (overall health), testSuccessTime (actual tunnel latency)
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
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
            super("i2p.tunnel.build.firstHopTimeout", SUB_ROUTER,
                  "Build first-hop delivery timeout (ms)",
                  5000, 10000, 1000, "tunnel.buildFailFirstHop", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.tunnel.build.firstHopTimeout", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return BuildRequestor.getFirstHopTimeout(_context);
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
            // observed = tunnel.buildFailFirstHop (count of first-hop delivery failures)
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
     * {@code jobQueue.jobLag}.
     *
     * <p>Increases when builds succeed and capacity is available.
     * Decreases when success drops, builds are slow/expiring, or backlog
     * is high. Uses 2x step for aggressive decrease during build storms.
     *
     * @since 0.9.70+
     */
    private class MaxConcurrentBuildsParam extends BaseParam {

        MaxConcurrentBuildsParam() {
            super("tunnel.build.maxConcurrent", SUB_TUNNEL,
                  "Max concurrent builds",
                  8, 256, 4, "tunnel.buildSuccessRate", _context);
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
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = tunnel.buildSuccessRate (0-100 scale)
            // Cross-refs: concurrentBuilds (current usage), buildClientExpire (timeouts),
            //             dropLoadBacklog (pending builds), testSuccessTime (latency)
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double buildExpires = getAdditionalStat(_context, "tunnel.buildClientExpire");
            double backlog = getAdditionalStat(_context, "tunnel.dropLoadBacklog");
            double testTime = getAdditionalStat(_context, "tunnel.testSuccessTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean successHigh = !Double.isNaN(observed) && observed > 90;
            boolean successLow = !Double.isNaN(observed) && observed < 70;
            boolean buildsExpiring = !Double.isNaN(buildExpires) && buildExpires > 10;
            boolean buildsBackedUp = !Double.isNaN(backlog) && backlog > 20;
            boolean buildsSlow = !Double.isNaN(testTime) && testTime > 10000;
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean usingCapacity = !Double.isNaN(concurrentBuilds) && concurrentBuilds > current * 0.7;

            // Startup / high demand: builds backed up + success ok = increase
            if (buildsBackedUp && successHigh && !cpuPressure)
                return Math.min(_max, current + _step * 2);

            // Builds expiring + success ok = increase (peers can handle more)
            if (buildsExpiring && successHigh && !cpuPressure)
                return Math.min(_max, current + _step);

            // Using most capacity + no issues = increase cautiously
            if (usingCapacity && successHigh && !buildsExpiring && !cpuPressure)
                return Math.min(_max, current + _step);

            // Success dropping + builds slow = decrease (too much network noise)
            if (successLow && buildsSlow)
                return Math.max(_min, current - _step * 2);

            // Low success + CPU pressure = decrease
            if (successLow && cpuPressure)
                return Math.max(_min, current - _step);

            // Using less than 30% capacity + high success + no backlog = decrease (steady state)
            if (!Double.isNaN(concurrentBuilds) && concurrentBuilds < current * 0.3
                && successHigh && !buildsBackedUp && !buildsExpiring)
                return Math.max(_min, current - _step);

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
            super("ntcp.reader.threads", SUB_BUFFERS,
                  "NTCP reader threads",
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
            // observed = ntcp.readQueueSize (pending connections waiting for a reader)
            // Cross-refs: ntcp.readError, jobLag (CPU), transport.receiveMessageTime,
            //             ntcp.sendTime (write path pressure)
            double readErrors = getAdditionalStat(_context, "ntcp.readError");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double recvTime = getAdditionalStat(_context, "transport.receiveMessageTime");
            double sendTime = getAdditionalStat(_context, "ntcp.sendTime");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean backlog = !Double.isNaN(observed) && observed > 0;
            boolean readSlow = !Double.isNaN(recvTime) && recvTime > 50;
            boolean errorsHigh = !Double.isNaN(readErrors) && readErrors > 10;
            boolean sendSlow = !Double.isNaN(sendTime) && sendTime > 50;

            // Backlog + no CPU pressure = add threads (more peers = more connections)
            if (backlog && !cpuPressure)
                return Math.min(_max, current + 1);

            // Slow reads + no CPU = add threads
            if (readSlow && !cpuPressure)
                return Math.min(_max, current + 1);

            // Errors + backlog = add threads urgently
            if (errorsHigh && backlog && !cpuPressure)
                return Math.min(_max, current + 1);

            // Idle + no work = shrink (threads sitting doing nothing)
            if (!backlog && !readSlow && !errorsHigh && !sendSlow && current > _min)
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
            super("ntcp.writer.threads", SUB_BUFFERS,
                  "NTCP writer threads",
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
            // observed = ntcp.sendTime (ms, message send latency)
            // Cross-refs: ntcp.sendPool utilization, ntcp.writeBufs.size, ntcp.writeQueueFull,
            //             ntcp.sendBacklogTime, jobLag (CPU), ntcp.readQueueSize, ntcp.sendFinisher.queueSize
            double sendPoolUtil = getAdditionalStat(_context, "ntcp.sendPool utilization");
            double writeBufs = getAdditionalStat(_context, "ntcp.writeBufs.size");
            double writeQueueFull = getAdditionalStat(_context, "ntcp.writeQueueFull");
            double backlogTime = getAdditionalStat(_context, "ntcp.sendBacklogTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double readQueue = getAdditionalStat(_context, "ntcp.readQueueSize");
            double finisherQueue = getAdditionalStat(_context, "ntcp.sendFinisher.queueSize");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean sendSlow = observed > 50;
            boolean queueFull = !Double.isNaN(writeQueueFull) && writeQueueFull > 0;
            boolean backlogged = !Double.isNaN(backlogTime) && backlogTime > 20;
            boolean poolBusy = !Double.isNaN(sendPoolUtil) && sendPoolUtil > 50;
            boolean bufsHigh = !Double.isNaN(writeBufs) && writeBufs > current * 16;
            boolean readerBacklogged = !Double.isNaN(readQueue) && readQueue > 5;
            boolean finisherBacklogged = !Double.isNaN(finisherQueue) && finisherQueue > 50;

            // Send slow or backlogged or pool busy or downstream + no CPU = add threads
            if ((sendSlow || backlogged || queueFull || poolBusy || bufsHigh
                 || readerBacklogged || finisherBacklogged) && !cpuPressure)
                return Math.min(_max, current + 1);

            // Idle + no work = shrink (threads sitting doing nothing)
            if (!sendSlow && !queueFull && !backlogged && !poolBusy && !bufsHigh
                && !readerBacklogged && !finisherBacklogged && current > _min)
                return Math.max(_min, current - 1);

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
            super("ntcp.failsafe.iterationFreq", SUB_BUFFERS,
                  "NTCP pumper failsafe interval (ms)",
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
            super("ntcp.sendFinisher.threads", SUB_TRANSPORT,
                  "NTCP send finisher threads",
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

            // High send pool utilization or finisher backlog = increase threads
            if (highUtilization || finisherBacklog || highWriteBufs)
                return Math.min(_max, current + 1);

            // Idle + no work = shrink (threads doing nothing useful)
            if (!highUtilization && !finisherBacklog && !highWriteBufs && current > _min)
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
            super("udp.peer.concurrentMaxMessages", SUB_BUFFERS,
                  "Max concurrent peer messages",
                  64, 1024, 16, "udp.allowConcurrentActive", _context);
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
            //             udp.rejectConcurrentActive (rejection events)
            double rtt = getAdditionalStat(_context, "udp.sendConfirmTime");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double rejections = getAdditionalStat(_context, "udp.rejectConcurrentActive");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean highUsage = !Double.isNaN(observed) && observed > current * 0.7;
            boolean nearCapacity = !Double.isNaN(observed) && observed > current * 0.9;
            boolean lowRTT = !Double.isNaN(rtt) && rtt < 500;
            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > 50000;
            boolean hasRejections = !Double.isNaN(rejections) && rejections > 0;

            // Near capacity + low RTT + headroom = increase aggressively
            if (nearCapacity && lowRTT && !cpuPressure)
                return Math.min(_max, current + _step * 2);

            // High usage + low RTT + headroom = grow to handle load
            if (highUsage && lowRTT && !cpuPressure && current < _max)
                return Math.min(_max, current + _step);

            // Low RTT + heavy transit + no CPU pressure = grow to handle load
            if (lowRTT && heavyTransit && !cpuPressure && current < _max)
                return Math.min(_max, current + _step);

            // Rejections + CPU pressure = increase cautiously (need throughput)
            if (hasRejections && cpuPressure)
                return Math.min(_max, current + _step);

            // Low usage + high RTT + no transit = decrease to reduce pressure
            if ((!Double.isNaN(observed) && observed < current * 0.3) && !heavyTransit && !lowRTT && current > _min)
                return Math.max(_min, current - _step);

            // High RTT + no rejections = decrease to reduce pressure
            if (!Double.isNaN(rtt) && rtt > 2000 && !hasRejections && current > _min)
                return Math.max(_min, current - _step);

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
            super("ntcp.sendPool.capacity", SUB_BUFFERS,
                  "NTCP send pool capacity",
                  64, 8192, 32, "ntcp.sendPool utilization", _context);
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
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean criticalUtilization = !Double.isNaN(observed) && observed > 80;
            boolean highUtilization = !Double.isNaN(observed) && observed > 60;
            boolean finisherBacklog = !Double.isNaN(finisherQ) && finisherQ > 200;

            // Critical saturation: pool IS the bottleneck, grow regardless of latency.
            // Only CPU pressure stops us — latency is a symptom of the pool being too small.
            if (criticalUtilization && !cpuPressure)
                return Math.min(_max, current + _step * 2);

            // High utilization or finisher backlog + no CPU = grow pool
            if ((highUtilization || finisherBacklog) && !cpuPressure)
                return Math.min(_max, current + _step * 2);

            // Moderate utilization + low latency + low RTT = grow pool proactively
            boolean lowLatency = !Double.isNaN(sendTime) && sendTime < 100;
            boolean lowRTT = !Double.isNaN(rtt) && rtt < 200;
            if (!Double.isNaN(observed) && observed > 40 && !cpuPressure && lowLatency && lowRTT)
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
            super("i2cp.internalQueueSize", SUB_BUFFERS,
                  "I2CP internal queue size",
                  128, 2048, 32, "udp.sendConfirmTime", _context);
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
            // Cross-refs: client.writerQueueFull (overflow events), jobLag (CPU), sendProcessingTime
            double overflows = getAdditionalStat(_context, "client.writerQueueFull");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double sendTime = getAdditionalStat(_context, "transport.sendProcessingTime");
            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 100;
            boolean hasOverflows = !Double.isNaN(overflows) && overflows > 0;
            boolean highRTT = !Double.isNaN(observed) && observed > 1000;
            boolean lowLatency = !Double.isNaN(sendTime) && sendTime < 50;

            // Overflow events + headroom = increase queue (prevent client drops)
            if (hasOverflows && !cpuPressure)
                return Math.min(_max, current + _step);

            // High RTT + no CPU pressure = increase queue (clients back up on slow links)
            if (highRTT && !cpuPressure)
                return Math.min(_max, current + _step);

            // Low latency + no overflows = decrease queue (reduce buffering)
            if (lowLatency && !hasOverflows && !cpuPressure && current > _min)
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
            super("udp.establish.maxQueuedOutbound", SUB_BUFFERS,
                  "Max queued outbound connections",
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
            double badIP = getAdditionalStat(_context, "udp.establishBadIP");
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
            super("ntcp.maxWriteBufs", SUB_BUFFERS,
                  "NTCP max write buffers per connection",
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
            super("tunnel.testJob.maxQueued", SUB_TUNNEL,
                  "Max concurrent test jobs",
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
            super("tunnel.testJob.minTestDelay", SUB_TUNNEL,
                  "Min delay between tests (ms)",
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
            double testSuccessTime = getAdditionalStat(_context, "tunnel.testSuccessTime");
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
            super("tunnel.testJob.maxTestDelay", SUB_TUNNEL,
                  "Max delay between tests (ms)",
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

    static class SystemHealth {
        private final RouterContext _ctx;
        private double _score = 1.0;

        SystemHealth(RouterContext ctx) {
            _ctx = ctx;
            compute();
        }

        double getScore() { return _score; }

        private void compute() {
            double jobLagScore = scoreJobLag();
            double buildScore = scoreBuildSuccess();
            double failureScore = scoreMessageFailures();
            double buildStormScore = scoreBuildStorms();
            double transitScore = scoreTransitLoad();
            double latencyScore = scoreLatency();

            // Weighted geometric mean — low scores in any factor drag the whole score down
            _score = Math.pow(jobLagScore, 0.20)
                   * Math.pow(buildScore, 0.15)
                   * Math.pow(failureScore, 0.15)
                   * Math.pow(buildStormScore, 0.10)
                   * Math.pow(transitScore, 0.10)
                   * Math.pow(latencyScore, 0.30);
        }

        /**
         * Job queue lag: 0ms = 1.0, >50ms = degraded, >200ms = 0.0
         */
        private double scoreJobLag() {
            RateStat rs = _ctx.statManager().getRate("jobQueue.jobLag");
            if (rs == null) return 1.0;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return 1.0;
            double avg = rate.getAverageValue();
            if (avg <= 0) return 1.0;
            // 0ms→1.0, 50ms→0.75, 200ms→0.0
            return clamp(1.0 - (avg / 200.0));
        }

        /**
         * Build success rate: >80% = 1.0, <30% = 0.0
         */
        private double scoreBuildSuccess() {
            RateStat rs = _ctx.statManager().getRate("tunnel.buildSuccessRate");
            if (rs == null) return 1.0;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return 1.0;
            double avg = rate.getAverageValue();
            if (avg < 0) return 1.0;
            // 0%→0.0, 30%→0.0, 80%→1.0
            return clamp((avg - 0.3) / 0.5);
        }

        /**
         * Message send failures: 0 = 1.0, >5000ms = degraded, >30000ms = 0.0
         */
        private double scoreMessageFailures() {
            RateStat rs = _ctx.statManager().getRate("transport.sendMessageFailureLifetime");
            if (rs == null) return 1.0;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return 1.0;
            double avg = rate.getAverageValue();
            if (avg <= 0) return 1.0;
            // 0ms→1.0, 5s→0.8, 30s→0.0
            return clamp(1.0 - ((avg - 5000) / 25000.0));
        }

        /**
         * Build storms: 0 concurrent = 1.0, >10 = degraded, >30 = 0.0
         */
        private double scoreBuildStorms() {
            RateStat rs = _ctx.statManager().getRate("tunnel.concurrentBuilds");
            if (rs == null) return 1.0;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return 1.0;
            double avg = rate.getAverageValue();
            if (avg <= 0) return 1.0;
            // 0→1.0, 10→0.7, 30→0.0
            return clamp(1.0 - ((avg - 10) / 20.0));
        }

        /**
         * Transit load: participating bandwidth vs configured max.
         * Low usage = 1.0, at capacity = 0.0
         */
        private double scoreTransitLoad() {
            RateStat rs = _ctx.statManager().getRate("tunnel.participating InBps");
            if (rs == null) return 1.0;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return 1.0;
            double bps = rate.getAverageValue();
            // read configured outbound bandwidth (KB/s → B/s)
            int maxKBps = _ctx.getProperty("i2np.bandwidth.outboundKBytesPerSecond", 128);
            long maxBps = maxKBps * 1024L;
            if (maxBps <= 0) return 1.0;
            double usageRatio = bps / (double) maxBps;
            // 0%→1.0, 70%→0.5, 100%→0.0
            return clamp(1.0 - (usageRatio / 0.7));
        }

        /**
         * Message send latency (transport.sendProcessingTime).
         * <100ms = 1.0, >500ms = degraded, >5000ms = 0.0
         */
        private double scoreLatency() {
            RateStat rs = _ctx.statManager().getRate("transport.sendProcessingTime");
            if (rs == null) return 1.0;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return 1.0;
            double avg = rate.getAverageValue();
            if (avg <= 100) return 1.0;
            // 100ms→1.0, 500ms→0.75, 5000ms→0.0
            return clamp(1.0 - ((avg - 100) / 4900.0));
        }

        private static double clamp(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }
    }
}
