package net.i2p.router.peermanager;

/**
 * A latency baseline for one transport, built from the round trip times that
 * transport has actually measured.
 * <p>Purpose: an absolute millisecond threshold cannot judge two transports at
 * once. A threshold tuned for NTCP2 is either unreachable for SSU2, so every
 * SSU2 peer looks slow, or so loose for NTCP2 that it admits links that cause
 * build timeouts. The penalty is structural rather than a property of the peer,
 * so the peer is excluded for carrying the protocol it happens to speak.
 * Per-protocol baselines remove that: each transport is judged against what it
 * can achieve on its own.
 * <p>The baseline is the mean of the {@link #SAMPLES_FOR_BASELINE} <em>fastest</em>
 * retained samples, not of all of them. This is the same estimator TCP uses to
 * derive its floor for retransmission timeout, and it is chosen here for one
 * property: it tracks what the transport has recently <em>achieved</em>. A mean
 * over every sample would follow the population upward as the network degrades,
 * so the bar would relax precisely when quality matters most, and a peer
 * becoming slow would be scored against a baseline that had already drifted to
 * accommodate it. Keying on the floor keeps the bar steady while the tail is
 * allowed to move.
 * <p>The trade-off is deliberate and worth stating: the floor only ever reflects
 * the best links seen recently, so if an entire path to one protocol degrades,
 * the baseline stays optimistic and its peers score worse. That is the intended
 * reading - degradation should show up - but it means the baseline is not
 * relative to current conditions.
 * <p>Instances are not thread-safe. The intended lifecycle is one instance per
 * transport, built once per sampling cycle from that cycle's measurements, then
 * read for the duration of the cycle.
 *
 * @since 0.9.71+
 */
public class ProtocolRttBaseline {

    /**
     * Samples required before a baseline is published at all. Below this the
     * estimator reports unknown and callers fall back to their absolute rules,
     * so a cold or thinly-populated transport behaves exactly as it did before
     * calibration existed.
     */
    public static final int SAMPLES_FOR_BASELINE = 50;

    /**
     * Upper bound on retained samples. Only the recent past is wanted, so the
     * buffer is a ring rather than a growing list: a long-lived router would
     * otherwise accumulate every sample it has ever seen and the floor would
     * stop moving.
     */
    public static final int MAX_SAMPLES = 256;

    /**
     * How many of the fastest retained samples the baseline averages. Equal to
     * {@link #SAMPLES_FOR_BASELINE}, which is what makes the estimator defined:
     * a baseline is the mean of a fixed count, so it does not drift as samples
     * accumulate.
     */
    public static final int SAMPLES_AVERAGED = 50;

    private final int[] _samples;
    private int _count;
    private int _next;

    /**
     * Create an empty baseline.
     */
    public ProtocolRttBaseline() {
        this(MAX_SAMPLES);
    }

    /**
     * Create an empty baseline with a specific ring size.
     *
     * @param capacity how many samples to retain; must be at least
     *                 {@link #SAMPLES_AVERAGED} for a baseline to be possible
     */
    public ProtocolRttBaseline(int capacity) {
        if (capacity < SAMPLES_AVERAGED) {
            throw new IllegalArgumentException("capacity " + capacity +
                                               " below " + SAMPLES_AVERAGED);
        }
        _samples = new int[capacity];
    }

    /**
     * Add a measured round trip time. Non-positive and overflow values are
     * ignored, so a caller cannot poison the population with a measurement that
     * means "unknown" or one that cannot be an int.
     *
     * @param rttMs measured round trip time in ms
     */
    public void add(int rttMs) {
        if (rttMs <= 0) {return;}
        _samples[_next] = rttMs;
        _next = (_next + 1) % _samples.length;
        if (_count < _samples.length) {_count++;}
    }

    /**
     * Number of retained samples.
     *
     * @return the sample count, between 0 and the ring capacity
     */
    public int getSampleCount() {return _count;}

    /**
     * Whether enough samples exist for a baseline to mean anything.
     *
     * @return true if at least {@link #SAMPLES_FOR_BASELINE} samples are held
     */
    public boolean isKnown() {return _count >= SAMPLES_FOR_BASELINE;}

    /**
     * The calibrated floor for this transport, or -1 when unknown.
     * <p>Returns -1 rather than a guess below {@link #SAMPLES_FOR_BASELINE}
     * samples. Callers must treat that as "no calibrated opinion available" and
     * fall back to their absolute rules - never as "this peer is fine".
     *
     * @return the mean of the fastest samples in ms, or -1 if unknown
     */
    public long getBaselineMs() {
        if (!isKnown()) {return -1;}
        return baselineMs(_samples, _count);
    }

    /**
     * The calibrated floor, clamped so it can never fall below
     * {@code floorMs / multiple}.
     * <p>The clamp is the guard against a freakishly low population tightening
     * the bar below what an absolute rule would have used. A single 1 ms sample
     * among fifty would otherwise put the baseline at a few milliseconds and
     * make the multipleshreshold far stricter than the absolute floor it
     * replaces. With the clamp the calibrated bar is always at least as
     * permissive as the absolute one, so introducing calibration can only widen
     * the set of qualifying peers, never narrow it.
     *
     * @param floorMs    the absolute low-latency floor in ms
     * @param multiple   the multiple of the baseline a peer must stay under
     * @return the clamped baseline in ms, or -1 if unknown
     */
    public long getClampedBaselineMs(long floorMs, int multiple) {
        long raw = getBaselineMs();
        if (raw < 0) {return -1;}
        if (floorMs <= 0 || multiple <= 0) {return raw;}
        long lowest = floorMs / multiple;
        return raw < lowest ? lowest : raw;
    }

    /**
     * Mean of the fastest samples. Pure over an array plus a count, so the
     * selection rule is testable without constructing a baseline.
     *
     * @param samples the retained samples
     * @param count   how many entries of {@code samples} are valid
     * @return the mean of the fastest {@link #SAMPLES_AVERAGED} entries, or -1
     *         when fewer than that are valid
     */
    static long baselineMs(int[] samples, int count) {
        if (samples == null || count < SAMPLES_AVERAGED) {return -1;}
        int n = Math.min(count, samples.length);
        int[] sorted = new int[n];
        System.arraycopy(samples, 0, sorted, 0, n);
        java.util.Arrays.sort(sorted);
        long total = 0;
        for (int i = 0; i < SAMPLES_AVERAGED; i++) {
            total += sorted[i];
        }
        return total / SAMPLES_AVERAGED;
    }

    /**
     * Whether a measurement qualifies against a calibrated baseline.
     * <p>Never true when the baseline is unknown, so a caller cannot treat a
     * missing baseline as permission.
     *
     * @param rttMs     the measured round trip time in ms
     * @param baselineMs the calibrated baseline in ms, or -1 when unknown
     * @param multiple  how many times the baseline a peer may sit under
     * @return true if the measurement is within the calibrated bar
     */
    static boolean withinBaseline(int rttMs, long baselineMs, int multiple) {
        if (baselineMs <= 0 || multiple <= 0 || rttMs <= 0) {return false;}
        return rttMs <= baselineMs * multiple;
    }
}