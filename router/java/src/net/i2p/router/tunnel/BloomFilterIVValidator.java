package net.i2p.router.tunnel;

import java.io.File;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.Tuner;
import net.i2p.router.tasks.OOMListener;
import net.i2p.router.util.DecayingBloomFilter;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SystemVersion;

/**
 * Manage the IV validation for all of the router's tunnels by way of a big
 * decaying bloom filter.
 */
public class BloomFilterIVValidator implements IVValidator {
    private final RouterContext _context;
    private final Log _log;
    private volatile DecayingBloomFilter _filter;
    /**
     * Manages the filter's size. Kept behind a field so the router can hold
     * the filter for the low-memory case and still retune it later.
     */
    private volatile IVFilterSizer _sizer;

    /**
     * After 2*halflife, an entry is completely forgotten from the bloom filter.
     * To avoid the issue of overlap within different tunnels, this is set
     * higher than it needs to be.
     */
    private static final int HALFLIFE_MS = 10*60*1000;
    /**
     * Property the Tuner drives the filter exponent through. The starting
     * value is a property rather than a constant so an operator can pin it
     * before the first tuning cycle.
     */
    public static final String PROP_FILTER_M = "i2p.tunnel.ivFilterM";
    /**
     * Default exponent when nothing is configured: 2^23 bits per buffer, a
     * 2 MB pair, which holds roughly 600k entries at a 1.5E-3 false positive
     * rate. That covers a 1 MBps share with a wide margin.
     */
    public static final int DEFAULT_FILTER_M = 23;
    /** Floor for the exponent: 2^20 is a 0.5 MB pair. */
    public static final int MIN_FILTER_M = 20;
    /**
     * Fraction of max heap the filter may claim. The rest is left for
     * transport buffers, the netdb, and the collector.
     */
    static final double FILTER_HEAP_FRACTION = 0.10d;
    /** Hard floor on the filter's byte budget, so a tiny heap still gets one. */
    static final long MIN_FILTER_BUDGET = 1L << 20;
    /**
     * Share bandwidth at or above which the fixed-memory filter is always worth
     * using. Below it the set is only kept when memory is genuinely tight.
     */
    private static final int MIN_SHARE_KBPS_TO_USE_BLOOM = 64;
    private static final long MIN_MEM_TO_USE_BLOOM = 64*1024*1024L;
    /** For testing. */
    private static final String PROP_FORCE = "router.forceDecayingBloomFilter";
    /** For testing. */
    private static final String PROP_DISABLE = "router.disableDecayingBloomFilter";

    /**
     * Construct the validator, choosing a starting filter size from the
     * configured share and the heap, then leaving the size to be retuned from
     * measured load.
     *
     * <p>The initial choice deliberately favours the fixed-memory filter over
     * the set: a pair of 2^23 bit arrays is 2 MB whatever the traffic, whereas
     * the set costs about 72 bytes per entry and is unbounded until its cap.
     * So the set is used only when both the share is small and the heap is
     * small, and it is replaced by a filter as soon as the budget allows.
     *
     * @param ctx the router context
     * @param KBps share bandwidth
     */
    public BloomFilterIVValidator(RouterContext ctx, int KBps) {
        _context = ctx;
        _log = ctx.logManager().getLog(BloomFilterIVValidator.class);
        if (_context.getBooleanProperty(PROP_DISABLE)) {
            _filter = null;
        } else {
            long budget = filterBudgetBytes();
            boolean forced = _context.getBooleanProperty(PROP_FORCE);
            if (!forced && KBps < MIN_SHARE_KBPS_TO_USE_BLOOM
                     && SystemVersion.getMaxMemory() < MIN_MEM_TO_USE_BLOOM) {
                if (KBps >= MIN_SHARE_KBPS_TO_USE_BLOOM)
                    warn(SystemVersion.getMaxMemory(), KBps, MIN_MEM_TO_USE_BLOOM,
                         MIN_SHARE_KBPS_TO_USE_BLOOM);
                _filter = new DecayingHashSet(ctx, HALFLIFE_MS, 16, "TunnelIVV");
                // hold the set to the same budget the filter would have used
                int cap = (int) Math.max(1024, budget / DecayingHashSet.BYTES_PER_ENTRY);
                ((DecayingHashSet) _filter).setMaxEntries(cap);
                _sizer = new IVFilterSizer(this, ctx, KBps);
            } else {
                int m = DecayingBloomFilter.mForBudget(budget);
                if (m < MIN_FILTER_M)
                    m = MIN_FILTER_M;
                int configured = ctx.getProperty(PROP_FILTER_M, 0);
                if (configured > 0)
                    m = configured;
                _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV", m);
                _sizer = new IVFilterSizer(this, ctx, KBps);
            }
        }
        ctx.statManager().createRateStat("tunnel.duplicateIV", "Note that a duplicate IV was received", "Tunnels",
                                         new long[] { 60*1000, 60*60*1000L });
    }

    /**
     * Memory this filter may claim, as a fraction of the heap, with a floor so
     * a small heap still gets a usable filter.
     *
     * @return the budget in bytes, never below the 1 MB floor
     */
    public static long filterBudgetBytes() {
        long budget = (long) (SystemVersion.getMaxMemory() * FILTER_HEAP_FRACTION);
        return Math.max(MIN_FILTER_BUDGET, budget);
    }

    /**
     * The filter's size exponent, or 0 when the set is in use.
     *
     * @return the exponent, or 0 for the set or when disabled
     * @since 0.9.71+
     */
    public int getFilterM() {
        DecayingBloomFilter f = _filter;
        return (f instanceof DecayingBloomFilter) ? ((DecayingBloomFilter) f).getM() : 0;
    }

    /**
     * Entries seen in the most recently completed decay window, which is what
     * the size has to be chosen against. Zero until the first decay.
     *
     * @return entries in the last window
     * @since 0.9.71+
     */
    public int getLastWindowCount() {
        DecayingBloomFilter f = _filter;
        return (f instanceof DecayingBloomFilter) ? ((DecayingBloomFilter) f).getLastWindowCount() : 0;
    }

    /**
     * The live false positive rate, or 0 for the set, which is exact.
     *
     * @return the current false positive rate
     * @since 0.9.71+
     */
    public double getMeasuredFalsePositiveRate() {
        DecayingBloomFilter f = _filter;
        return (f instanceof DecayingBloomFilter) ? f.getFalsePositiveRate() : 0d;
    }

    /**
     * Replace the filter with one of a different size, or with the set. A
     * resize discards the buffered IVs, so for a short window a repeated IV
     * could go unnoticed; the caller is expected to only do this on a decay
     * boundary and at most one step at a time.
     *
     * @param m the new exponent, 0 to switch to the memory-proportional set
     * @param KBps the configured share in KBps, not consulted here; the set's
     * cap comes from the heap budget
     * @since 0.9.71+
     */
    public void reconfigure(int m, int KBps) {
        DecayingBloomFilter old = _filter;
        if (old == null)
            return;
        DecayingBloomFilter next;
        if (m <= 0) {
            DecayingHashSet set = new DecayingHashSet(_context, HALFLIFE_MS, 16, "TunnelIVV");
            int cap = (int) Math.max(1024, filterBudgetBytes() / DecayingHashSet.BYTES_PER_ENTRY);
            set.setMaxEntries(cap);
            _filter = set;
            if (_log.shouldDebug())
                _log.debug("IV filter switched to the decaying hash set");
            return;
        }
        if (m == ((DecayingBloomFilter) old).getM())
            return;
        int clamped = Math.max(MIN_FILTER_M, Math.min(DecayingBloomFilter.MAX_M, m));
        if (clamped > DecayingBloomFilter.mForBudget(filterBudgetBytes()))
            clamped = DecayingBloomFilter.mForBudget(filterBudgetBytes());
        if (clamped < MIN_FILTER_M)
            clamped = MIN_FILTER_M;
        next = new DecayingBloomFilter(_context, HALFLIFE_MS, 16, "TunnelIVV", clamped);
        old.stopDecaying();
        _filter = next;
        if (_log.shouldDebug())
            _log.debug("IV filter resized to m=" + clamped + " ("
                       + (next.getMemoryBytes() / 1024) + " KB)");
    }

    /**
     * The retuning policy for this validator, or null when the filter is off.
     *
     * @return the sizer, or null
     * @since 0.9.71+
     */
    public IVFilterSizer getSizer() { return _sizer; }

    /**
     * Chooses the filter exponent from measured load and the heap budget.
     *
     * <p>Deliberately never shrinks on a low throughput or share-bandwidth
     * reading. Both are guesses about load rather than evidence that the
     * filter is too big, and cutting capacity on them is how a router ends up
     * limiting bandwidth it could have carried. The only reasons to shrink are
     * memory pressure and a false positive rate that is genuinely too high.
     */
    public static class IVFilterSizer {
        /** False positive rate to size for, well inside what callers tolerate. */
        public static final double TARGET_FPR = 1.5E-3d;
        private final BloomFilterIVValidator _validator;
        private final RouterContext _ctx;
        private int _KBps;

        /**
         * Bind the sizer to the validator whose filter it will resize, keeping
         * the share bandwidth to hand back on a resize.
         *
         * @param validator the validator this policy retunes
         * @param ctx router context, held for the sizer but not read by it
         * @param KBps configured share bandwidth in KBps, retained for
         * reconfigure()
         */
        public IVFilterSizer(BloomFilterIVValidator validator, RouterContext ctx, int KBps) {
            _validator = validator;
            _ctx = ctx;
            _KBps = KBps;
        }

        /**
         * The validator the sizing decisions are applied through.
         *
         * @return the validator this policy retunes
         */
        public BloomFilterIVValidator getValidator() { return _validator; }
        /**
         * Share bandwidth retained for the next resize.
         *
         * @return configured share bandwidth in KBps
         */
        public int getKBps() { return _KBps; }
        /**
         * Replace the share bandwidth that the next resize is handed.
         *
         * @param KBps configured share bandwidth in KBps
         */
        public void setKBps(int KBps) { _KBps = KBps; }

        /**
         * Decide the exponent for one cycle.
         *
         * <p>Static and free of router state so it can be tested directly.
         *
         * @param currentM the exponent in force, 0 when the set is in use
         * @param windowEntries entries seen in the last completed decay window
         * @param fpr the live false positive rate of the current filter
         * @param budgetBytes memory the filter may claim
         * @param heapPressure 0.0 (idle) to 1.0 (heap full)
         * @return the exponent to use
         * @since 0.9.71+
         */
        public static int computeTargetM(int currentM, int windowEntries, double fpr,
                                  long budgetBytes, double heapPressure) {
            int ceiling = DecayingBloomFilter.mForBudget(budgetBytes);
            if (ceiling < MIN_FILTER_M)
                return currentM;               // no room to change anything
            // Under memory pressure, and only then, give capacity back.
            if (heapPressure >= 0.75d && currentM > MIN_FILTER_M)
                return currentM - 1;
            if (windowEntries <= 0)
                return currentM;               // no measurement yet, hold
            // A rate this far over target means the filter is genuinely too
            // small for the traffic; anything milder is not worth a resize,
            // which would cost a window of duplicate detection.
            boolean overRate = fpr > TARGET_FPR * 4;
            int required = DecayingBloomFilter.mForEntries(windowEntries, TARGET_FPR);
            if ((overRate || required > currentM) && currentM < ceiling)
                return Math.min(ceiling, currentM + 1);
            // Only give capacity back when the rate is not already too high. An
            // elevated rate contradicts a low entry count, and the safe reading
            // of that disagreement is that the filter is undersized, so the
            // shrink must not fire.
            if (!overRate && required < currentM - 1 && currentM > MIN_FILTER_M)
                return currentM - 1;
            return currentM;
        }

        /** One tuning cycle: read the measurement, decide, and apply. */
        public void update() {
            int m = _validator.getFilterM();
            if (m == 0)
                return;                        // set in use, nothing to size
            int target = computeTargetM(m, _validator.getLastWindowCount(),
                                        _validator.getMeasuredFalsePositiveRate(),
                                        filterBudgetBytes(), Tuner.getMemoryPressure());
            if (target != m)
                _validator.reconfigure(target, _KBps);
        }
    }

    @Override
    public boolean receiveIV(byte[] ivData, int ivOffset, byte[] payload, int payloadOffset) {
        if (_filter == null)  // testing only
            return true;
        byte[] buf = SimpleByteCache.acquire(HopProcessor.IV_LENGTH);
        DataHelper.xor(ivData, ivOffset, payload, payloadOffset, buf, 0, HopProcessor.IV_LENGTH);
        boolean dup = _filter.add(buf);
        SimpleByteCache.release(buf);
        if (dup) _context.statManager().addRateData("tunnel.duplicateIV", 1);
        return !dup; // return true if it is OK, false if it isn't
    }

    /** Stop the decaying filter. */
    public void destroy() {
        if (_filter != null)
            _filter.stopDecaying();
    }

    /** @since 0.9.20 */
    private void warn(long maxMemory, int KBps, long recMaxMem, int threshKBps) {
        if (SystemVersion.isAndroid())
            return;
        String path = OOMListener.getWrapperConfigPath(_context);
        String msg =
            "Configured for " + DataHelper.formatSize(KBps *1024L) +
            "Bps share bandwidth but only " +
            DataHelper.formatSize(maxMemory) + "B available memory.";
        if (_context.hasWrapper()) {
            msg += "\nRecommend increasing wrapper.java.maxmemory in " +
                   path;
        } else if (!SystemVersion.isWindows()) {
            msg += "\nRecommend increasing MAXMEMOPT in " +
                   _context.getBaseDir() + File.separatorChar + "runplain.sh or /usr/bin/i2prouter-nowrapper";
        } else {
            msg += "\nRecommend running the restartable version of I2P, and increasing wrapper.java.maxmemory in " +
                   path;
        }
        // getMaxMemory() returns significantly lower than wrapper config, so add 10%
        msg += " to at least " + (recMaxMem * 11 / 10 / (1024*1024)) + " (MB)" +
               " if the actual share bandwidth exceeds " +
               DataHelper.formatSize(threshKBps * 1024L) + "Bps.";
        _context.logManager().getLog(BloomFilterIVValidator.class).logAlways(Log.WARN, msg);
    }
}
