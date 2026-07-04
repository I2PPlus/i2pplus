package net.i2p.router;

import java.util.ArrayList;
import java.util.List;
import net.i2p.router.transport.udp.PeerState;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.transport.udp.SimpleBandwidthEstimator;
import net.i2p.router.transport.udp.EstablishmentManager;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.router.tunnel.pool.BuildHandler;
import net.i2p.router.tunnel.pool.BuildExecutor;
import net.i2p.router.tunnel.pool.BuildRequestor;
import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.networkdb.kademlia.IterativeSearchJob;
import net.i2p.router.peermanager.ProfileOrganizer;
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
 * Uses AIMD-style feedback loops bounded within safe ranges.
 * New tunable parameters can be added by implementing TunableParam.
 *
 * @since 0.9.70+
 */
public class Tuner implements SimpleTimer.TimedEvent {

    private final RouterContext _context;
    private final Log _log;
    private final List<TunableParam> _params;

    static final long STAT_PERIOD = 60 * 1000L;
    /** Max history samples for sparklines (30 samples @ 30s = 15min) */
    static final int MAX_HISTORY = 30;

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
     * @since 0.9.70+
     */
    static double getMemoryPressure() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        if (max <= 0) return 0.0;
        long used = rt.totalMemory() - rt.freeMemory();
        return Math.min(1.0, Math.max(0.0, (double) used / max));
    }

    public Tuner(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(Tuner.class);
        _params = new ArrayList<TunableParam>(24);

        // Transport params
        _params.add(new AckFrequencyParam());
        _params.add(new DataMessageTimeoutParam());
        _params.add(new ObEstablishTimeParam());
        _params.add(new IbEstablishTimeParam());

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
        _params.add(new NTCPThreadsParam());
        _params.add(new NTCPQueueCapacityParam());
        _params.add(new UDPHandlerThreadsParam());
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

        // NetDB params
        _params.add(new NetDBSearchLimitParam());
        _params.add(new NetDBMaxConcurrentParam());
        _params.add(new NetDBSingleSearchTimeParam());

        // Transport params
        _params.add(new MaxConcurrentEstablishParam());

        // Peer management params
        _params.add(new MaxProfilesParam());
        _params.add(new MinFastPeersParam());

        // Build timeout params
        _params.add(new BuildRequestTimeoutParam());
        _params.add(new BuildFirstHopTimeoutParam());
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
                    bp.setHealth(health);
                }
                param.update();
            } catch (Exception e) {
                if (_log.shouldWarn())
                    _log.warn("Tuner: error updating " + param.getName(), e);
            }
        }
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
     * Base class for tunable params with history tracking.
     * Reads min/max/step from router properties (live, no restart needed).
     */
    abstract static class BaseParam implements TunableParam {
        protected final String _name;
        protected final String _description;
        protected final String _subsystem;
        protected final String _propPrefix;
        protected int _min;
        protected int _max;
        protected int _step;
        protected final String _statName;
        protected final int _initialValue;
        protected volatile int _override;
        protected volatile boolean _autoTuning;
        protected SystemHealth _health;
        protected final int[] _valueHistory;
        protected final double[] _statHistory;
        protected int _historyCount;
        protected final Log _log;

        protected BaseParam(String name, String description, String subsystem,
                            int defaultMin, int defaultMax,
                            int defaultStep, String statName, RouterContext ctx) {
            _name = name;
            _description = description;
            _subsystem = subsystem;
            _propPrefix = PROP_PREFIX + name + ".";
            _statName = statName;
            _min = ctx.getProperty(_propPrefix + "min", defaultMin);
            _max = ctx.getProperty(_propPrefix + "max", defaultMax);
            _step = ctx.getProperty(_propPrefix + "step", defaultStep);
            _initialValue = getRuntimeValue();
            _override = -1;
            _autoTuning = true;
            _log = ctx.logManager().getLog(Tuner.class);
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
        public boolean isAutoTuning() { return _autoTuning; }

        /** Re-read min/max/step from router properties — live update, no restart */
        public void refreshRanges(RouterContext ctx) {
            _min = ctx.getProperty(_propPrefix + "min", _min);
            _max = ctx.getProperty(_propPrefix + "max", _max);
            _step = ctx.getProperty(_propPrefix + "step", _step);
        }

        public void setOverride(int value) {
            _override = value;
            _autoTuning = (value < 0);
            if (value >= 0) {
                int prev = getRuntimeValue();
                if (_log.shouldInfo())
                    _log.info(_name + " override set from " + prev + " to " + value + " (startup: " + _initialValue + ")");
                applyValue(value);
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
                                     _initialValue, _min, _max, _step, _autoTuning,
                                     _statName, obsVal, vh, sh);
        }

        protected abstract void applyValue(int value);
        protected abstract int getRuntimeValue();
        protected abstract double getObservedStat(RouterContext ctx);
        protected abstract int computeTarget(double observed);

        /**
         * Fetch an additional stat value for cross-reference decisions.
         * Returns NaN if not available.
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
         * Useful for less time-sensitive decisions where we want to
         * confirm a trend rather than react to a short-term spike.
         * Returns NaN if not available.
         */
        protected double getAdditionalStatHourly(RouterContext ctx, String statName) {
            RateStat rs = ctx.statManager().getRate(statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(RateConstants.ONE_HOUR);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
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

        public void update() {
            double observed = getObservedStat(null);
            recordHistory(observed);
            if (!_autoTuning)
                return;
            if (Double.isNaN(observed))
                return;
            // Health-based gating: skip tuning if system is severely degraded
            if (_health != null && _health.getScore() < 0.2) {
                return;
            }
            int target = computeTarget(observed);
            // Health-based dampening: reduce step size when degraded
            if (_health != null && _health.getScore() < 0.6) {
                int current = getRuntimeValue();
                int rawDelta = target - current;
                // scale delta by health: at health=0.2 → 20% of delta, at health=0.6 → 100%
                int dampened = current + (int)(rawDelta * ((_health.getScore() - 0.2) / 0.4));
                // clamp to at least 1 step toward target
                if (dampened == current && target != current)
                    dampened = current + (rawDelta > 0 ? Math.min(_step, rawDelta) : Math.max(-_step, rawDelta));
                target = dampened;
            }
            int current = getRuntimeValue();
            if (target != current) {
                if (_log.shouldInfo())
                    _log.info(_name + " changing from " + current + " to " + target + " (startup: " + _initialValue + ")");
                applyValue(target);
            }
        }

        /**
         * Move from current toward target, but at most one step per cycle.
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
     * The streaming module depends on router, not vice versa, so we use reflection.
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
                    // streaming not loaded
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
     * Tunes ACK_FREQUENCY based on retransmission rate.
     */
    private class AckFrequencyParam extends BaseParam {
        private static final double LOW_THRESHOLD = 1.05;
        private static final double HIGH_THRESHOLD = 1.15;

        AckFrequencyParam() {
            super("ACK_FREQUENCY", SUB_TRANSPORT,
                  "Packets between ACKs (lower = more ACKs, less loss)",
                  50, 500, 10, "udp.sendConfirmVolley", _context);
        }

        protected void applyValue(int value) {
            PeerState.setAckFrequency(value);
        }

        protected int getRuntimeValue() {
            return PeerState.getAckFrequency();
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
            // observed = udp.sendConfirmVolley (packets per confirm, ~1.0 = healthy)
            // Cross-refs: sendConfirmTime (RTT), sendMessageFailureLifetime (congestion), jobLag (CPU)
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            // Dead zone: volley between 1.05-1.15 is fine regardless
            if (observed >= 1.05 && observed <= 1.15)
                return current;

            // High volley (ACKs not getting through): decrease frequency (more ACKs)
            // But only if network isn't slow AND system isn't overloaded
            if (observed > HIGH_THRESHOLD) {
                if (systemBusy) return current;
                return Math.max(_min, current / 2);
            }

            // Low volley (ACKs flowing fine): increase frequency (fewer ACKs, less overhead)
            // But only if network is healthy AND no congestion
            if (observed < LOW_THRESHOLD) {
                if (networkSlow || congested || systemBusy) return current;
                return Math.min(_max, current + _step);
            }
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
                  1000, 15000, 500, "udp.sendConfirmTime", _context);
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
            // observed = udp.sendConfirmTime (ms, how long a send+confirm takes)
            // Cross-refs: sendFailed (establish failures), sendMessageFailureLifetime (congestion),
            //             jobLag (CPU), participating InBps (transit load)
            double sendFailed = getAdditionalStat(_context, "udp.sendFailed");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");

            boolean hasSendFailures = !Double.isNaN(sendFailed) && sendFailed > 0;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
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
            // Cross-refs: sendFailed (establish failures), sendConfirmTime (general RTT)
            double sendFailed = getAdditionalStat(_context, "udp.sendFailed");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean hasEstablishFailures = !Double.isNaN(sendFailed) && sendFailed > 0;
            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;

            // Target: 3x observed, but floor at 3000ms
            int target = Math.max(3000, (int) (observed * 3));

            // Dead zone: if current is already within 50% of target and no failures, hold
            if (current >= target * 0.5 && current <= target * 1.5 && !hasEstablishFailures)
                return current;

            // Never decrease when there are establish failures or network is slow
            if (target < current && (hasEstablishFailures || networkSlow || congested))
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
                  3000, 30000, 500, "udp.inboundEstablishTime", _context);
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
            double sendFailed = getAdditionalStat(_context, "udp.sendFailed");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");

            boolean hasEstablishFailures = !Double.isNaN(sendFailed) && sendFailed > 0;
            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;

            int target = Math.max(3000, (int) (observed * 3));

            if (current >= target * 0.5 && current <= target * 1.5 && !hasEstablishFailures)
                return current;

            if (target < current && (hasEstablishFailures || networkSlow || congested))
                return current;

            return clamp(current, target, _step);
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
                  10, 100, 5, "tunnel.participating InBps", _context);
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
            // Cross-refs: gateway overflow drops, job lag
            double obDrops = getAdditionalStat(_context, "tunnel.dropGatewayOverflowOB");
            double ibDrops = getAdditionalStat(_context, "tunnel.dropGatewayOverflowIB");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean hasDrops = (!Double.isNaN(obDrops) && obDrops > 0) ||
                               (!Double.isNaN(ibDrops) && ibDrops > 0);
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            // Low transit + spare capacity = decrease requeue (faster retry, lower latency)
            if (observed < 10000 && !hasDrops && !systemBusy)
                return Math.max(_min, current - _step);

            // Heavy transit + drops = increase requeue (back off, clear backlog)
            // But if CPU is overloaded, hold steady
            if (observed > 50000 && hasDrops) {
                if (systemBusy) return current;
                return Math.min(_max, current + _step);
            }

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
                  10, 100, 5, "tunnel.participating OutBps", _context);
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
                  1, 20, 1, "ntcp.pumperLoopsPerSecond", _context);
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
     * Tunes PumpedTunnelGateway MAX_OB_MSGS_PER_PUMP based on gateway overflow drops.
     * Higher batch = more throughput per pump, but higher latency per message.
     * Target: increase batch size when no overflow, decrease when dropping.
     */
    private class ObMsgsPerPumpParam extends BaseParam {

        ObMsgsPerPumpParam() {
            super("MAX_OB_MSGS_PER_PUMP", SUB_TUNNEL,
                  "Outbound gateway batch size",
                  32, 256, 16, "tunnel.dropGatewayOverflowOB", _context);
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
            // observed = tunnel.dropGatewayOverflowOB (drops per second)
            // No drops: we can increase batch for throughput
            // Drops: decrease batch for lower latency
            // High system load: don't increase batch (avoid adding CPU pressure)
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            if (observed > 5)
                return Math.max(_min, current - _step);
            else if (observed == 0 && !highLoad)
                return Math.min(_max, current + _step);
            return current;
        }
    }

    /**
     * Tunes PumpedTunnelGateway MAX_IB_MSGS_PER_PUMP based on gateway overflow drops.
     * Higher batch = more throughput per pump, but higher latency per message.
     * Target: increase batch size when no overflow, decrease when dropping.
     */
    private class IbMsgsPerPumpParam extends BaseParam {

        IbMsgsPerPumpParam() {
            super("MAX_IB_MSGS_PER_PUMP", SUB_TUNNEL,
                  "Inbound gateway batch size",
                  16, 256, 8, "tunnel.dropGatewayOverflowIB", _context);
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
            // observed = tunnel.dropGatewayOverflowIB (drops per second)
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > 50000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            if (observed > 5) {
                if (heavyTransit && systemBusy) return current;
                return Math.max(_min, current - _step);
            }

            if (observed == 0 && !heavyTransit && !systemBusy && !highLoad)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    // ==================== Streaming Params ====================

    /**
     * Tunes streaming INITIAL_WINDOW_SIZE based on observed RTT.
     * Higher initial window = faster ramp-up for new connections.
     * Target: increase when RTT is low (fast path), decrease when RTT is high (congested).
     */
    private class InitialWindowSizeParam extends BaseParam {

        InitialWindowSizeParam() {
            super("INITIAL_WINDOW_SIZE", SUB_STREAMING,
                  "Streaming initial congestion window",
                  4, 32, 4, "stream.con.initialRTT.in", _context);
        }

        protected void applyValue(int value) {
            StreamingReflector.invokeSetInt("setInitialWindowSize", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingReflector.invokeGetInt("getInitialWindowSize");
            return v >= 0 ? v : 16;
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
            // observed = stream.con.initialRTT.in (inbound RTT, ms)
            // Cross-refs: sendMessageFailureLifetime (congestion), buildSuccessRate (network health)
            // Hourly trend: confirm RTT trend isn't just a short-term spike
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double hourlyRTT = getAdditionalStatHourly(_context, _statName);

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;

            // High RTT (slow pipe) + no congestion + healthy network = increase window
            // Large window fills the pipe despite latency; small window starves throughput
            // Only decrease on congestion, not on high RTT alone
            if (observed > 3000 && !congested && networkHealthy)
                return Math.min(_max, current + _step);

            // Low RTT (fast pipe) + no congestion = decrease window
            // Fast networks need smaller windows to avoid over-buffering
            if (observed < 1000 && !congested)
                return Math.max(_min, current - _step);

            // Active congestion = decrease window regardless of RTT
            if (congested)
                return Math.max(_min, current - _step);

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
                  2000, 10000, 500, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            StreamingReflector.invokeSetInt("setInitialRTO", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingReflector.invokeGetInt("getInitialRTO");
            return v >= 0 ? v : 6000;
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
            // observed = stream.con.initialRTT.out (outbound RTT in ms)
            // Cross-refs: sendMessageFailureLifetime (congestion), buildSuccessRate (network health)
            // Hourly trend: confirm RTT trend isn't just a short-term spike
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double hourlyRTT = getAdditionalStatHourly(_context, _statName);

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            // Confirm sustained high RTT
            boolean sustainedHighRTT = !Double.isNaN(hourlyRTT) && hourlyRTT > 3000;

            // Target: 2x RTT as baseline (standard TCP-like behavior)
            int target = Math.max(2000, Math.min(10000, (int) (observed * 2)));

            // Dead zone: if current is within 50% of target and network is healthy, hold
            if (current >= target * 0.5 && current <= target * 1.5 && networkHealthy && !congested)
                return current;

            // Never decrease RTO during congestion (need more tolerance)
            if (target < current && congested)
                return current;

            // Never decrease RTO when network health is poor or sustained high RTT
            if (target < current && (!networkHealthy || sustainedHighRTT))
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
                  5, 50, 5, "stream.sendsBeforeAck", _context);
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
            // Cross-refs: sendMessageSize (message size), participating OutBps (bandwidth)
            // Hourly trend: confirm sends-before-ACK trend isn't just a short-term spike
            double msgSize = getAdditionalStat(_context, "stream.con.sendMessageSize");
            double transitBps = getAdditionalStat(_context, "tunnel.participating OutBps");
            double hourlySends = getAdditionalStatHourly(_context, _statName);

            boolean largeMessages = !Double.isNaN(msgSize) && msgSize > 1000;
            boolean heavyBandwidth = !Double.isNaN(transitBps) && transitBps > 50000;
            // Confirm sustained high sends
            boolean sustainedHighSends = !Double.isNaN(hourlySends) && hourlySends > 3;

            // High sends + small messages + low bandwidth = increase delay (more piggybacking)
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > 5 && !largeMessages && !heavyBandwidth && sustainedHighSends)
                return Math.min(_max, current + _step);

            // Low sends OR large messages OR high bandwidth = decrease delay (more responsive)
            if (observed < 2 || largeMessages || heavyBandwidth)
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
            // Cross-refs: sendsBeforeAck, jobLag
            // Hourly trend: confirm message size trend isn't just a short-term spike
            double sendsBeforeAck = getAdditionalStat(_context, "stream.sendsBeforeAck");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlySize = getAdditionalStatHourly(_context, _statName);

            boolean manySends = !Double.isNaN(sendsBeforeAck) && sendsBeforeAck > 3;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            // Confirm sustained large messages
            boolean sustainedLargeMessages = !Double.isNaN(hourlySize) && hourlySize > 800;

            // Large messages + many sends = decrease delay (optimize latency, traffic flowing)
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > 1000 && manySends && sustainedLargeMessages)
                return Math.max(_min, current - _step);

            // Small messages + few sends + CPU headroom = increase delay (batch small writes)
            if (observed < 200 && !manySends && !systemBusy)
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
                  16, 128, 4, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.maxSlowStartWindow", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.maxSlowStartWindow", 32);
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
            // observed = stream.con.initialRTT.out (outbound RTT, ms)
            // Cross-refs: sendMessageFailureLifetime (congestion), sendConfirmTime (network RTT)
            // Hourly trend: confirm RTT trend isn't just a short-term spike
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double hourlyRTT = getAdditionalStatHourly(_context, _statName);

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean fastNetwork = !Double.isNaN(confirmTime) && confirmTime < 300;
            // Confirm sustained low RTT
            boolean sustainedLowRTT = !Double.isNaN(hourlyRTT) && hourlyRTT < 2000;

            // High RTT or congestion = decrease window (don't overwhelm slow network)
            if (observed > 5000 || congested)
                return Math.max(_min, current - _step);

            // Low RTT + fast network + no congestion = increase window (aggressive ramp-up)
            // Require sustained trend to avoid over-reacting to brief dips
            if (observed < 2000 && fastNetwork && !congested && sustainedLowRTT)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    // ==================== I2CP Params ====================

    /**
     * Tunes I2CP CLIENT_WRITER_QUEUE_SIZE based on queue overflow drops.
     * Higher queue = more buffer for slow clients, lower = less memory per client.
     * Target: increase when drops are high, decrease when queue is underutilized.
     */
    private class WriterQueueSizeParam extends BaseParam {

        WriterQueueSizeParam() {
            super("CLIENT_WRITER_QUEUE_SIZE", SUB_I2CP,
                  "I2CP write queue size",
                  32, 1024, 32, "client.writerQueueFull", _context);
        }

        protected void applyValue(int value) {
            ClientManagerFacadeImpl.setWriterQueueSize(value);
        }

        protected int getRuntimeValue() {
            return ClientManagerFacadeImpl.getWriterQueueSize();
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
            // observed = client.writerQueueFull (queue overflow events/sec)
            // Cross-refs: jobLag (CPU pressure)
            // Hourly trend: confirm queue overflow trend isn't just a short-term spike
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyOverflows = getAdditionalStatHourly(_context, _statName);
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            // Confirm sustained overflow pressure
            boolean sustainedOverflows = !Double.isNaN(hourlyOverflows) && hourlyOverflows > 2;

            // Drops = increase queue (buffer for slow clients)
            // But if CPU is overloaded or system load is high, hold (larger queues = more memory pressure)
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > 5 && sustainedOverflows) {
                if (systemBusy || highLoad) return current;
                return Math.min(_max, current + _step);
            }

            // No drops + CPU headroom + system not loaded = decrease queue (save memory)
            if (observed == 0 && !systemBusy && !highLoad)
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
                java.util.Collections.singletonMap("router.codelTarget", String.valueOf(value)), null);
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
            // observed = codel.UDP-Sender.delay (avg queue delay, ms)
            // Cross-refs: codel.UDP-Sender.drop (drop rate), jobLag (CPU)
            // Hourly trend: confirm delay trend isn't just a short-term spike
            double drops = getAdditionalStat(_context, "codel.UDP-Sender.drop");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyDelay = getAdditionalStatHourly(_context, _statName);
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean hasDrops = !Double.isNaN(drops) && drops > 5;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            // Confirm sustained high delay
            boolean sustainedHighDelay = !Double.isNaN(hourlyDelay) && hourlyDelay > current;

            // High delay + drops = bufferbloat, lower target for more aggressive dropping
            // Also be more aggressive under high system load
            // Require sustained trend to avoid over-reacting to brief spikes
            if ((observed > current * 2 && hasDrops && sustainedHighDelay) || highLoad)
                return Math.max(_min, current - _step);

            // Low delay + system not loaded = raise target (less aggressive, things are fine)
            if (observed < current / 2 && !hasDrops && !systemBusy && !highLoad)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Tunes CoDel interval based on network RTT characteristics.
     * Shorter interval = faster reaction to congestion.
     * Longer interval = smoother behavior.
     * Uses codel.UDP-Sender.drop as the observed stat.
     */
    private class CodelIntervalParam extends BaseParam {

        CodelIntervalParam() {
            super("CODEL_INTERVAL",
                  "CoDel measurement window",
                  SUB_CODEL, 20, 200, 10, "codel.UDP-Sender.drop", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig(
                java.util.Collections.singletonMap("router.codelInterval", String.valueOf(value)), null);
        }

        protected int getRuntimeValue() {
            return _context.getProperty("router.codelInterval", 50);
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
            // observed = codel.UDP-Sender.drop (drops per second)
            // Cross-refs: codel.UDP-Sender.delay (queue delay)
            // Hourly trend: confirm drop trend isn't just a short-term spike
            double delay = getAdditionalStat(_context, "codel.UDP-Sender.delay");
            double hourlyDrops = getAdditionalStatHourly(_context, _statName);
            boolean hasDelay = !Double.isNaN(delay) && delay > 10;
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            // Confirm sustained high drops
            boolean sustainedHighDrops = !Double.isNaN(hourlyDrops) && hourlyDrops > 5;

            // High drops + delay = shorten interval for faster reaction
            // Also shorten under high system load for more aggressive congestion response
            // Require sustained trend to avoid over-reacting to brief spikes
            if ((observed > 10 && hasDelay && sustainedHighDrops) || highLoad)
                return Math.max(_min, current - _step);

            // No drops + no delay + system not loaded = lengthen interval (smoother behavior)
            if (observed == 0 && !hasDelay && !highLoad)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    // ==================== Westwood Params ====================

    /**
     * Tunes Westwood+ EWMA decay factor based on bandwidth stability.
     * Lower factor = faster adaptation to bandwidth changes.
     * Higher factor = smoother estimates, less jitter.
     * Uses udp.sendConfirmTime as the observed stat (proxy for RTT stability).
     */
    private class WestwoodDecayFactorParam extends BaseParam {

        WestwoodDecayFactorParam() {
            super("WESTWOOD_DECAY_FACTOR",
                  "Westwood EWMA smoothing",
                  SUB_WESTWOOD, 2, 16, 1, "udp.sendConfirmTime", _context);
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
            // observed = udp.sendConfirmTime (ms, proxy for RTT variability)
            // Cross-refs: sendMessageFailureLifetime (congestion), jobLag (CPU)
            // Hourly trend: confirm RTT variability trend isn't just a short-term spike
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyRTT = getAdditionalStatHourly(_context, _statName);

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            // Confirm sustained high RTT variability
            boolean sustainedHighRTT = !Double.isNaN(hourlyRTT) && hourlyRTT > 400;

            // High send time variability + no congestion = lower factor (faster adaptation)
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > 500 && !congested && !systemBusy && sustainedHighRTT)
                return Math.max(_min, current - _step);

            // Low variability = raise factor (smoother EWMA estimates)
            if (observed < 100 && !congested)
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
     */
    private class XDHPreCalcMinParam extends BaseParam {

        XDHPreCalcMinParam() {
            super("crypto.x25519.precalcMin", SUB_BUFFERS,
                  "Min precomputed X25519 key pairs",
                  2, 128, 2, "crypto.XDHEmpty", _context);
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
            // observed = crypto.XDHEmpty (queue empty events/sec)
            // Cross-refs: jobLag (CPU pressure), memory pressure, system load
            // Hourly trend: confirm empty events trend isn't just a short-term spike
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyEmpties = getAdditionalStatHourly(_context, _statName);
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            // Confirm sustained empty events
            boolean sustainedEmpties = !Double.isNaN(hourlyEmpties) && hourlyEmpties > 0;

            double memPressure = getMemoryPressure();

            // Memory pressure or high system load: scale down aggressively
            if (memPressure > 0.85 || highLoad) {
                int target = Math.max(_min, (int)(current * 0.5));
                return Math.max(_min, target);
            }
            if (memPressure > 0.75) {
                int target = Math.max(_min, (int)(current * 0.75));
                return Math.max(_min, target);
            }

            // Queue empty events + CPU headroom = increase pool (need more pre-computed keys)
            // Scale up more aggressively when memory is plentiful and system load is low
            if (observed > 0 && !systemBusy && sustainedEmpties && !highLoad) {
                int step = (memPressure < 0.5 && sysLoad < 40) ? _step * 2 : _step;
                return Math.min(_max, current + step);
            }

            // No empty events + memory headroom + low load = grow pool for future requests
            if (observed == 0 && !systemBusy && memPressure < 0.5 && sysLoad < 50)
                return Math.min(_max, current + 1);

            // No empty events + memory tight or high load = shrink pool
            if (observed == 0 && !systemBusy && (memPressure > 0.6 || highLoad))
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * EDH (Elligator2) key precalculation minimum pool size.
     */
    private class EDHPreCalcMinParam extends BaseParam {

        EDHPreCalcMinParam() {
            super("crypto.edh.precalcMin", SUB_BUFFERS,
                  "Min precomputed EDH key pairs",
                  2, 128, 2, "crypto.EDHEmpty", _context);
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
            // observed = crypto.EDHEmpty (queue empty events/sec)
            // Cross-refs: jobLag (CPU pressure), memory pressure, system load
            // Hourly trend: confirm empty events trend isn't just a short-term spike
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyEmpties = getAdditionalStatHourly(_context, _statName);
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            boolean sustainedEmpties = !Double.isNaN(hourlyEmpties) && hourlyEmpties > 0;

            double memPressure = getMemoryPressure();

            // Memory pressure or high system load: scale down aggressively
            if (memPressure > 0.85 || highLoad) {
                int target = Math.max(_min, (int)(current * 0.5));
                return Math.max(_min, target);
            }
            if (memPressure > 0.75) {
                int target = Math.max(_min, (int)(current * 0.75));
                return Math.max(_min, target);
            }

            // Queue empty events + CPU headroom = increase pool
            if (observed > 0 && !systemBusy && sustainedEmpties && !highLoad) {
                int step = (memPressure < 0.5 && sysLoad < 40) ? _step * 2 : _step;
                return Math.min(_max, current + step);
            }

            // No empty events + memory headroom + low load = grow pool
            if (observed == 0 && !systemBusy && memPressure < 0.5 && sysLoad < 50)
                return Math.min(_max, current + 1);

            // No empty events + memory tight or high load = shrink pool
            if (observed == 0 && !systemBusy && (memPressure > 0.6 || highLoad))
                return Math.max(_min, current - 1);

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
                  1, 32, 1, "crypto.MLKEMEmpty", _context);
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
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;
            boolean sustainedEmpties = !Double.isNaN(hourlyEmpties) && hourlyEmpties > 0;

            double memPressure = getMemoryPressure();

            // Memory pressure or high system load: scale down aggressively
            if (memPressure > 0.85 || highLoad) {
                int target = Math.max(_min, (int)(current * 0.5));
                return Math.max(_min, target);
            }
            if (memPressure > 0.75) {
                int target = Math.max(_min, (int)(current * 0.75));
                return Math.max(_min, target);
            }

            // Queue empty events + CPU headroom = increase pool
            if (observed > 0 && !systemBusy && sustainedEmpties && !highLoad) {
                int step = (memPressure < 0.5 && sysLoad < 40) ? _step * 2 : _step;
                return Math.min(_max, current + step);
            }

            // No empty events + memory headroom + low load = grow pool
            if (observed == 0 && !systemBusy && memPressure < 0.5 && sysLoad < 50)
                return Math.min(_max, current + 1);

            // No empty events + memory tight or high load = shrink pool
            if (observed == 0 && !systemBusy && (memPressure > 0.6 || highLoad))
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * NTCP send finisher thread count.
     */
    private class NTCPThreadsParam extends BaseParam {

        NTCPThreadsParam() {
            super("ntcp.sendFinisher.maxThreads", SUB_BUFFERS,
                  "Max NTCP send finisher threads",
                  1, 8, 1, "ntcp.writeQueueFull", _context);
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
            // observed = ntcp.writeQueueFull (queue overflow events/sec)
            // Primary: ntcp.sendTime (ms) — message lifetime in finisher pipeline
            // Cross-refs: ntcp.sendQueueSize (backlog), jobLag (CPU)
            double sendTime = getAdditionalStat(_context, "ntcp.sendTime");
            double sendQueueSize = getAdditionalStat(_context, "ntcp.sendQueueSize");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean cpuPressure = !Double.isNaN(jobLag) && jobLag > 50;

            // Finisher bottleneck: high sendTime + growing queue
            boolean finisherBottleneck = !Double.isNaN(sendTime) && sendTime > 200
                                      && !Double.isNaN(sendQueueSize) && sendQueueSize > 10;

            // Queue overflow emergency (existing signal)
            boolean queueOverflow = observed > 0;

            // Increase: bottleneck or overflow, but no CPU pressure and system not loaded
            if ((finisherBottleneck || queueOverflow) && !cpuPressure && !highLoad)
                return Math.min(_max, current + 1);

            // Decrease: low sendTime, small queue, no overflow, no CPU pressure
            // Also decrease when system is heavily loaded (reduce thread count)
            if ((!Double.isNaN(sendTime) && sendTime < 50
                && !Double.isNaN(sendQueueSize) && sendQueueSize < 5
                && observed == 0 && !cpuPressure) || highLoad)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * NTCP send finisher queue capacity.
     */
    private class NTCPQueueCapacityParam extends BaseParam {

        NTCPQueueCapacityParam() {
            super("ntcp.sendFinisher.queueCapacity", SUB_BUFFERS,
                  "NTCP send queue capacity",
                  256, 16384, 256, "ntcp.writeQueueFull", _context);
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
            // observed = ntcp.writeQueueFull (queue overflow events/sec)
            // Cross-refs: jobLag (CPU), transit OutBps (bandwidth load)
            // Hourly trend: confirm bandwidth trend isn't just a short-term spike
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double transitBps = getAdditionalStat(_context, "tunnel.participating OutBps");
            double hourlyBps = getAdditionalStatHourly(_context, "tunnel.participating OutBps");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > 50000;
            // Confirm sustained high bandwidth
            boolean sustainedHeavyTransit = !Double.isNaN(hourlyBps) && hourlyBps > 40000;

            // Queue full + CPU headroom + system not loaded = increase capacity
            if (observed > 0 && !systemBusy && !highLoad)
                return Math.min(_max, current + _step);

            // No queue full + low load or system loaded = decrease capacity (save memory)
            // Also decrease when system is heavily loaded to reduce memory pressure
            // Require sustained low load to avoid under-provisioning during brief dips
            if ((observed == 0 && !heavyTransit && !systemBusy && !sustainedHeavyTransit) || highLoad)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * UDP packet handler thread count.
     */
    private class UDPHandlerThreadsParam extends BaseParam {

        UDPHandlerThreadsParam() {
            super("udp.packetHandler.maxThreads", SUB_BUFFERS,
                  "Max UDP packet handler threads",
                  4, 16, 1, "udp.pushTime", _context);
        }

        protected void applyValue(int value) {
            UDPTransport.setPacketHandlerMaxThreads(value);
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
            // Cross-refs: transit InBps (bandwidth load), jobLag (CPU)
            // Hourly trend: confirm bandwidth trend isn't just a short-term spike
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyBps = getAdditionalStatHourly(_context, "tunnel.participating InBps");
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean heavyTransit = !Double.isNaN(transitBps) && transitBps > 50000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            // Confirm sustained high bandwidth
            boolean sustainedHeavyTransit = !Double.isNaN(hourlyBps) && hourlyBps > 40000;

            // High push time + heavy load + CPU headroom + system not loaded = increase threads
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > 50 && heavyTransit && !systemBusy && !highLoad && sustainedHeavyTransit)
                return Math.min(_max, current + 1);

            // Low push time + low load or system loaded = decrease threads (save CPU context switches)
            if ((observed < 2 && !heavyTransit && !systemBusy) || highLoad)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * Per-peer outbound message queue size.
     */
    private class PeerOutboundQueueParam extends BaseParam {

        PeerOutboundQueueParam() {
            super("router.peerOutboundQueueSize", SUB_BUFFERS,
                  "Max outbound messages per peer",
                  50, 500, 50, "udp.rejectConcurrentActive", _context);
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
            // observed = udp.rejectConcurrentActive (rejection events/sec)
            // Cross-refs: peer.activeProfileCount (peer count), jobLag (CPU)
            // Hourly trend: confirm rejection trend isn't just a short-term spike
            double peerCount = getAdditionalStat(_context, "peer.activeProfileCount");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyRejections = getAdditionalStatHourly(_context, _statName);
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            // Confirm sustained rejection pressure
            boolean sustainedRejections = !Double.isNaN(hourlyRejections) && hourlyRejections > 5;

            // Rejections + CPU headroom + system not loaded = increase queue
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > 10 && !systemBusy && !highLoad && sustainedRejections)
                return Math.min(_max, current + _step);

            // No rejections or system loaded = decrease queue (save memory)
            if ((observed == 0 && !systemBusy) || highLoad)
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
            RateStat rs = _context.statManager().getRate(_statName);
            if (rs == null) return Double.NaN;
            Rate rate = rs.getRate(STAT_PERIOD);
            if (rate == null || rate.getLastEventCount() == 0) return Double.NaN;
            return rate.getAverageValue();
        }

        protected int computeTarget(double observed) {
            int current = getRuntimeValue();
            // observed = participating InBps; factor = fraction of transit KEPT (higher = less drop)
            int maxBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1024;
            if (maxBps <= 0) return current;
            double usagePct = observed / maxBps;

            // Cross-refs: buildSuccessRate (network health), sendMessageFailureLifetime (congestion)
            // Hourly trend: confirm bandwidth usage trend isn't just a short-term spike
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double hourlyBps = getAdditionalStatHourly(_context, _statName);

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            // Confirm high usage is a sustained trend, not just a spike
            boolean sustainedHighUsage = !Double.isNaN(hourlyBps) && hourlyBps / maxBps > 0.6;

            // High bandwidth usage + network healthy = increase throttle (manage load proactively)
            // Require sustained trend to avoid over-reacting to brief spikes
            if (usagePct > 0.7 && networkHealthy && sustainedHighUsage)
                return Math.min(_max, current + _step);

            // Low bandwidth usage = decrease throttle (we have room, accept more transit)
            if (usagePct < 0.3 && !congested)
                return Math.max(_min, current - _step);

            // Congested but low usage = don't change (something else is wrong)
            return current;
        }
    }

    /**
     * Tunnel rejection curve steepness.
     * Higher exponent = sharper transition from accepting to rejecting.
     */
    private class ThrottleRejectExponentParam extends BaseParam {

        ThrottleRejectExponentParam() {
            super("router.throttleRejectExponent", SUB_ROUTER,
                  "Rejection curve steepness (higher = sharper cutoff)",
                  2, 30, 1, "tunnel.buildSuccessRate", _context);
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
                  10, 500, 10, "tunnel.participating InBps", _context);
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

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

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
            if (usagePct > 0.5 || congested || systemBusy)
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
                  10, 80, 5, "tunnel.participating InBps", _context);
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
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
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
                  6000, 20000, 500, "tunnel.participating InBps", _context);
        }

        protected void applyValue(int value) {
            RouterThrottleImpl.setDefaultMaxTunnels(value);
        }

        protected int getRuntimeValue() {
            return RouterThrottleImpl.getDefaultMaxTunnels();
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
            // observed = participating InBps; maxBps = outbound bandwidth limit
            int maxBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1024;
            if (maxBps <= 0) return current;
            double usagePct = observed / maxBps;

            // Cross-refs: buildSuccessRate, sendMessageFailureLifetime, jobLag
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;

            // Massive headroom (usage < 5%) + healthy = increase aggressively (2x step)
            if (usagePct < 0.05 && networkHealthy && !congested && !systemBusy)
                return Math.min(_max, current + _step * 2);

            // Moderate headroom (usage < 30%) + healthy = increase normally
            if (usagePct < 0.3 && networkHealthy && !congested && !systemBusy)
                return Math.min(_max, current + _step);

            // High usage OR network degraded OR congested = decrease capacity
            if (usagePct > 0.7 || !networkHealthy || congested)
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
                  256, 2048, 32, "jobQueue.jobLag", _context);
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
            // Cross-refs: buildSuccessRate, concurrentBuilds
            // Hourly trend: confirm CPU pressure trend isn't just a short-term spike
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double hourlyLag = getAdditionalStatHourly(_context, _statName);
            int sysLoad = SystemVersion.getSystemLoad();
            boolean highLoad = sysLoad > 80;

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;
            // Confirm sustained CPU pressure
            boolean sustainedHighLag = !Double.isNaN(hourlyLag) && hourlyLag > 500;

            // High lag + build storm = hold (don't add more queue pressure during storm)
            if (observed > 1000 && buildStorm) return current;

            // High lag + builds degraded + system not loaded = increase queue (buffer more retries)
            // Require sustained lag to avoid over-reacting to brief spikes
            if (observed > 1000 && !networkHealthy && sustainedHighLag && !highLoad)
                return Math.min(_max, current + _step);

            // Low lag + network healthy + system not loaded = decrease queue (not needed)
            if (observed < 200 && networkHealthy && !buildStorm && !highLoad)
                return Math.max(_min, current - _step);

            // High system load = decrease queue (reduce memory footprint)
            if (highLoad && current > _min)
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
                  5000, 60000, 5000, "tunnel.buildSuccessRate", _context);
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
            // Cross-refs: concurrentBuilds (storm), participating InBps (load)
            // Hourly trend: confirm build success trend isn't just a short-term dip
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double transitBps = getAdditionalStat(_context, "tunnel.participating InBps");
            double hourlySuccess = getAdditionalStatHourly(_context, _statName);

            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;
            boolean heavyLoad = !Double.isNaN(transitBps) && transitBps > 80000;
            // Confirm sustained good health before relaxing throttle
            boolean sustainedGoodHealth = !Double.isNaN(hourlySuccess) && hourlySuccess > 0.9;

            // Build storm = hold (don't trigger more rebuilds)
            if (buildStorm) return current;

            // Low success + light load = decrease throttle (rebuild faster to fix pools)
            if (observed < 0.7 && !heavyLoad)
                return Math.max(_min, current - _step);

            // High success + low load = increase throttle (no need to rebuild often)
            // Require sustained good health to avoid relaxing during brief good periods
            if (observed > 0.95 && !heavyLoad && sustainedGoodHealth)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    // =====================================================================
    // Streaming congestion params
    // =====================================================================

    /**
     * Max retransmission timeout.
     */
    private class MaxRTOParam extends BaseParam {

        MaxRTOParam() {
            super("i2p.streaming.maxRTO", SUB_STREAMING,
                  "Max retransmission timeout (ms)",
                  3000, 60000, 1000, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            StreamingConnectionReflector.invokeConnectionOptionsSet("setMaxRTO", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingConnectionReflector.invokeConnectionOptionsInt("getMaxRTOStatic");
            return v > 0 ? v : 30000;
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
            // observed = stream.con.initialRTT.out (RTT, ms)
            // Cross-refs: sendMessageFailureLifetime (congestion), sendConfirmTime (network latency)
            // Hourly trend: confirm RTT trend isn't just a short-term spike
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double hourlyRTT = getAdditionalStatHourly(_context, _statName);

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            // Confirm sustained high RTT
            boolean sustainedHighRTT = !Double.isNaN(hourlyRTT) && hourlyRTT > 4000;

            // High RTT + network slow = increase max RTO (need more headroom)
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > 5000 && networkSlow && !congested && sustainedHighRTT)
                return Math.min(_max, current + _step);

            // Low RTT + no congestion = decrease max RTO (tighten loss detection)
            if (observed < 2000 && !congested && !networkSlow)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Max delay between retransmissions.
     */
    private class MaxResendDelayParam extends BaseParam {

        MaxResendDelayParam() {
            super("i2p.streaming.maxResendDelay", SUB_STREAMING,
                  "Max delay between retransmissions (ms)",
                  2000, 60000, 1000, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            StreamingConnectionReflector.invokeConnectionOptionsSet("setMaxResendDelay", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingConnectionReflector.invokeConnectionOptionsInt("getMaxResendDelayStatic");
            return v > 0 ? v : 30000;
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
            // observed = stream.con.initialRTT.out (RTT, ms)
            // Cross-refs: sendMessageFailureLifetime (congestion), sendConfirmTime (network latency)
            // Hourly trend: confirm RTT trend isn't just a short-term spike
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double hourlyRTT = getAdditionalStatHourly(_context, _statName);

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            // Confirm sustained high RTT
            boolean sustainedHighRTT = !Double.isNaN(hourlyRTT) && hourlyRTT > 4000;

            // High RTT + network slow = increase resend delay
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > 5000 && networkSlow && !congested && sustainedHighRTT)
                return Math.min(_max, current + _step);

            // Low RTT + no congestion = decrease resend delay
            if (observed < 2000 && !congested && !networkSlow)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Max retransmissions before giving up.
     */
    private class MaxRetransmissionsParam extends BaseParam {

        MaxRetransmissionsParam() {
            super("i2p.streaming.maxRetransmissions", SUB_STREAMING,
                  "Max retransmissions before giving up",
                  8, 256, 8, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            StreamingConnectionReflector.invokeConnectionSet("setMaxRetransmissions", value);
        }

        protected int getRuntimeValue() {
            int v = StreamingConnectionReflector.invokeConnectionInt("getMaxRetransmissionsStatic");
            return v > 0 ? v : 64;
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
            // observed = stream.con.initialRTT.out (RTT, ms)
            // Cross-refs: sendMessageFailureLifetime (congestion), sendConfirmTime (network latency)
            // Hourly trend: confirm RTT trend isn't just a short-term spike
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double hourlyRTT = getAdditionalStatHourly(_context, _statName);

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            // Confirm sustained high RTT
            boolean sustainedHighRTT = !Double.isNaN(hourlyRTT) && hourlyRTT > 4000;

            // High RTT + slow network + no congestion = allow more retransmissions
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > 5000 && networkSlow && !congested && sustainedHighRTT)
                return Math.min(_max, current + _step);

            // Low RTT + fast network + no congestion = fewer retransmissions (tighten)
            if (observed < 2000 && !networkSlow && !congested)
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
                  50, 5000, 50, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.minResendDelay", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.minResendDelay", 500);
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
            // observed = stream.con.initialRTT.out (RTT, ms)
            // Cross-refs: sendMessageFailureLifetime (congestion), sendConfirmTime (network latency)
            // Hourly trend: confirm RTT trend isn't just a short-term spike
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double hourlyRTT = getAdditionalStatHourly(_context, _statName);

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            // Confirm sustained low RTT for aggressive loss detection
            boolean sustainedLowRTT = !Double.isNaN(hourlyRTT) && hourlyRTT < 2000;

            // Low RTT + fast network + no congestion = lower delay (faster loss detection)
            // Require sustained trend to avoid over-reacting to brief dips
            if (observed < 3000 && !networkSlow && !congested && sustainedLowRTT)
                return Math.max(_min, current - _step);

            // High RTT or congestion = raise delay (avoid spurious retransmits)
            if (observed > 8000 || congested)
                return Math.min(_max, current + _step);

            return current;
        }
    }

    /**
     * Congestion avoidance growth rate factor.
     * Higher = more aggressive window growth in steady state.
     */
    private class CongestionAvoidanceGrowthParam extends BaseParam {

        CongestionAvoidanceGrowthParam() {
            super("i2p.streaming.congestionAvoidanceGrowthRateFactor", SUB_STREAMING,
                  "CA growth rate (higher = faster)",
                  1, 8, 1, "stream.con.windowSizeAtCongestion", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.congestionAvoidanceGrowthRateFactor", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.congestionAvoidanceGrowthRateFactor", 1);
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
            // observed = stream.con.windowSizeAtCongestion (window size when dup sent)
            // Cross-refs: sendMessageFailureLifetime (congestion), buildSuccessRate (network health)
            // Hourly trend: confirm congestion trend isn't just a short-term spike
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double hourlyWindow = getAdditionalStatHourly(_context, _statName);

            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            // Confirm sustained large window at congestion (network can handle it)
            boolean sustainedLargeWindow = !Double.isNaN(hourlyWindow) && hourlyWindow > 20;

            // Network healthy + large windows at congestion + no congestion = increase growth
            // Require sustained trend to avoid over-reacting to brief good periods
            if (networkHealthy && !congested && sustainedLargeWindow)
                return Math.min(_max, current + _step);

            // Congested or network degraded = decrease growth (be conservative)
            if (congested || !networkHealthy)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Slow start growth rate factor.
     * Higher = more aggressive ramp-up during slow start.
     */
    private class SlowStartGrowthParam extends BaseParam {

        SlowStartGrowthParam() {
            super("i2p.streaming.slowStartGrowthRateFactor", SUB_STREAMING,
                  "SS growth rate (higher = faster)",
                  1, 8, 1, "stream.con.initialRTT.out", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2p.streaming.slowStartGrowthRateFactor", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            return _context.getProperty("i2p.streaming.slowStartGrowthRateFactor", 1);
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
            // observed = stream.con.initialRTT.out (RTT, ms)
            // Cross-refs: buildSuccessRate (network health), sendMessageFailureLifetime (congestion)
            // Hourly trend: confirm RTT trend isn't just a short-term spike
            double buildSuccess = getAdditionalStat(_context, "tunnel.buildSuccessRate");
            double failLifetime = getAdditionalStat(_context, "transport.sendMessageFailureLifetime");
            double hourlyRTT = getAdditionalStatHourly(_context, _statName);

            boolean networkHealthy = Double.isNaN(buildSuccess) || buildSuccess > 0.7;
            boolean congested = !Double.isNaN(failLifetime) && failLifetime > 15000;
            // Confirm sustained low RTT for aggressive ramp-up
            boolean sustainedLowRTT = !Double.isNaN(hourlyRTT) && hourlyRTT < 3000;

            // Low RTT + healthy network + no congestion = faster ramp-up
            // Require sustained trend to avoid over-reacting to brief dips
            if (observed < 5000 && networkHealthy && !congested && sustainedLowRTT)
                return Math.min(_max, current + _step);

            // High RTT or congested or network degraded = slower ramp
            if (observed > 8000 || congested || !networkHealthy)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    // =====================================================================
    // NetDB params
    // =====================================================================

    /**
     * NetDB search limit (max peers per iterative search).
     */
    private class NetDBSearchLimitParam extends BaseParam {

        NetDBSearchLimitParam() {
            super("netdb.searchLimit", SUB_NETDB,
                  "Max peers per NetDB search",
                  4, 64, 2, "netDb.lookupsMatched", _context);
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
            // observed = netDb.lookupsMatched (lookup match rate, 0.0-1.0)
            // Cross-refs: sendConfirmTime (network latency), jobLag (CPU)
            // Hourly trend: confirm match rate trend isn't just a short-term dip
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyMatchRate = getAdditionalStatHourly(_context, _statName);

            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            // Confirm sustained low match rate
            boolean sustainedLowMatch = !Double.isNaN(hourlyMatchRate) && hourlyMatchRate < 0.6;

            // Low match rate + slow network = increase search limit (peers are slow to respond)
            // Require sustained trend to avoid over-reacting to brief dips
            if (observed < 0.5 && networkSlow && !systemBusy && sustainedLowMatch)
                return Math.min(_max, current + _step);

            // High match rate = decrease limit (searches are successful with fewer peers)
            if (observed > 0.9 && !networkSlow)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * NetDB max concurrent searches.
     */
    private class NetDBMaxConcurrentParam extends BaseParam {

        NetDBMaxConcurrentParam() {
            super("netdb.maxConcurrent", SUB_NETDB,
                  "Max concurrent NetDB searches",
                  1, 64, 1, "netDb.lookupsMatched", _context);
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
            // observed = netDb.lookupsMatched (lookup match rate)
            // Cross-refs: sendConfirmTime (network latency), jobLag (CPU)
            // Hourly trend: confirm match rate trend isn't just a short-term dip
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyMatchRate = getAdditionalStatHourly(_context, _statName);

            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            // Confirm sustained low match rate
            boolean sustainedLowMatch = !Double.isNaN(hourlyMatchRate) && hourlyMatchRate < 0.6;

            // Low match + slow network + CPU headroom = increase concurrency
            // Require sustained trend to avoid over-reacting to brief dips
            if (observed < 0.5 && networkSlow && !systemBusy && sustainedLowMatch)
                return Math.min(_max, current + 1);

            // High match rate = decrease concurrency (not needed)
            if (observed > 0.9)
                return Math.max(_min, current - 1);

            return current;
        }
    }

    /**
     * NetDB single search time (per-peer timeout).
     */
    private class NetDBSingleSearchTimeParam extends BaseParam {

        NetDBSingleSearchTimeParam() {
            super("netdb.singleSearchTime", SUB_NETDB,
                  "Per-peer NetDB search timeout (ms)",
                  1000, 15000, 500, "netDb.lookupsMatched", _context);
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
            // observed = netDb.lookupsMatched (lookup match rate)
            // Cross-refs: sendConfirmTime (network latency), jobLag (CPU)
            // Hourly trend: confirm match rate trend isn't just a short-term dip
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyMatchRate = getAdditionalStatHourly(_context, _statName);

            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            // Confirm sustained low match rate
            boolean sustainedLowMatch = !Double.isNaN(hourlyMatchRate) && hourlyMatchRate < 0.6;

            // Low match + slow network = increase timeout (peers need more time to respond)
            // Require sustained trend to avoid over-reacting to brief dips
            if (observed < 0.5 && networkSlow && !systemBusy && sustainedLowMatch)
                return Math.min(_max, current + _step);

            // High match + fast network = decrease timeout (searches complete quickly)
            if (observed > 0.9 && !networkSlow)
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
                  64, 2048, 32, "udp.sendFailed", _context);
        }

        protected void applyValue(int value) {
            _context.router().saveConfig("i2np.udp.maxConcurrentEstablish", Integer.toString(value));
        }

        protected int getRuntimeValue() {
            // Read from the property, which EstablishmentManager reads at construction
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
            // observed = udp.sendFailed (session establishment failures/sec)
            // Cross-refs: outboundEstablishTime, jobLag
            // Hourly trend: confirm failure trend isn't just a short-term spike
            double establishTime = getAdditionalStat(_context, "udp.outboundEstablishTime");
            double jobLag = getAdditionalStat(_context, "jobQueue.jobLag");
            double hourlyFailures = getAdditionalStatHourly(_context, _statName);

            boolean slowEstablish = !Double.isNaN(establishTime) && establishTime > 5000;
            boolean systemBusy = !Double.isNaN(jobLag) && jobLag > 100;
            // Confirm sustained failure pressure
            boolean sustainedFailures = !Double.isNaN(hourlyFailures) && hourlyFailures > 5;

            // Failures + fast establish + CPU headroom = increase concurrency (need more parallel)
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > 10 && !slowEstablish && !systemBusy && sustainedFailures)
                return Math.min(_max, current + _step);

            // Failures + slow establish = hold (problem is latency, not concurrency)
            if (observed > 10 && slowEstablish)
                return current;

            // No failures + low load = decrease concurrency (save resources)
            if (observed == 0 && !systemBusy)
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
                  800, 8000, 200, "peer.activeProfileCount", _context);
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
            // observed = peer.fastPeerCount (fast peers available)
            // Cross-refs: activeProfileCount (total pool size)
            // Hourly trend: confirm fast peer count trend isn't just a short-term spike
            double activeProfiles = getAdditionalStat(_context, "peer.activeProfileCount");
            double hourlyFastPeers = getAdditionalStatHourly(_context, _statName);

            boolean largePool = !Double.isNaN(activeProfiles) && activeProfiles > 500;
            // Confirm sustained high fast peer count
            boolean sustainedHighFastPeers = !Double.isNaN(hourlyFastPeers) && hourlyFastPeers > current;

            // Many fast peers + large pool = can raise minimum (more quality options)
            // Require sustained trend to avoid over-reacting to brief spikes
            if (observed > current * 1.5 && largePool && sustainedHighFastPeers)
                return Math.min(_max, current + _step);

            // Few fast peers OR small pool = decrease minimum (can't maintain high minimum)
            if (observed < current * 0.6 || !largePool)
                return Math.max(_min, current - _step);

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
                  3000, 30000, 1000, "tunnel.buildSuccessRate", _context);
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
            // observed = tunnel.buildSuccessRate (0.0-1.0)
            // Cross-refs: concurrentBuilds (storm), sendConfirmTime (network latency)
            // Hourly trend: confirm build success trend isn't just a short-term dip
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double hourlySuccess = getAdditionalStatHourly(_context, _statName);

            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;
            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            // Confirm sustained low success rate
            boolean sustainedLowSuccess = !Double.isNaN(hourlySuccess) && hourlySuccess < 0.6;

            // Build storm = hold (don't increase timeout during storm, makes it worse)
            if (buildStorm) return current;

            // Low success + slow network = increase timeout (peers need more time)
            // Require sustained trend to avoid over-reacting to brief dips
            if (observed < 0.5 && networkSlow && sustainedLowSuccess)
                return Math.min(_max, current + _step);

            // High success + fast network = decrease timeout (tighten)
            if (observed > 0.9 && !networkSlow)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Build first-hop delivery timeout.
     */
    private class BuildFirstHopTimeoutParam extends BaseParam {

        BuildFirstHopTimeoutParam() {
            super("i2p.tunnel.build.firstHopTimeout", SUB_ROUTER,
                  "Build first-hop delivery timeout (ms)",
                  2000, 20000, 1000, "tunnel.buildSuccessRate", _context);
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
            // observed = tunnel.buildSuccessRate (0.0-1.0)
            // Cross-refs: concurrentBuilds (storm), sendConfirmTime (network latency)
            // Hourly trend: confirm build success trend isn't just a short-term dip
            double concurrentBuilds = getAdditionalStat(_context, "tunnel.concurrentBuilds");
            double confirmTime = getAdditionalStat(_context, "udp.sendConfirmTime");
            double hourlySuccess = getAdditionalStatHourly(_context, _statName);

            boolean buildStorm = !Double.isNaN(concurrentBuilds) && concurrentBuilds > 15;
            boolean networkSlow = !Double.isNaN(confirmTime) && confirmTime > 500;
            // Confirm sustained low success rate
            boolean sustainedLowSuccess = !Double.isNaN(hourlySuccess) && hourlySuccess < 0.6;

            if (buildStorm) return current;

            // Low success + slow network = increase timeout (peers need more time)
            // Require sustained trend to avoid over-reacting to brief dips
            if (observed < 0.5 && networkSlow && sustainedLowSuccess)
                return Math.min(_max, current + _step);

            if (observed > 0.9 && !networkSlow)
                return Math.max(_min, current - _step);

            return current;
        }
    }

    /**
     * Computes a composite system health score from multiple cross-cutting stats.
     * Score ranges from 0.0 (severely degraded) to 1.0 (perfect).
     *
     * Factors:
     *   - Job queue lag (high = system overloaded)
     *   - Tunnel build success rate (low = network degraded)
     *   - Message failure lifetime (high = congestion)
     *   - Concurrent builds (high = build storm)
     *   - Participating bandwidth (high = lots of transit load)
     *
     * @since 0.9.70+
     */
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

            // Weighted geometric mean — low scores in any factor drag the whole score down
            _score = Math.pow(jobLagScore, 0.30)
                   * Math.pow(buildScore, 0.25)
                   * Math.pow(failureScore, 0.20)
                   * Math.pow(buildStormScore, 0.15)
                   * Math.pow(transitScore, 0.10);
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

        private static double clamp(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }
    }
}
