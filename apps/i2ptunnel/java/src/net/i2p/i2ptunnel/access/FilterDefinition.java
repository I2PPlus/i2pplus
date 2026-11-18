package net.i2p.i2ptunnel.access;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Access filter definition.
 * <p>
 * POJO containing parsed representation from filter definition file.
 *
 * @since 0.9.40
 */
class FilterDefinition {

    /** The finding logged once for a rule the counters cannot hold. */
    private static final String UNBREACHABLE =
            "Access rule can never be met -> History too short";

    /** Reports a definition whose rules cannot be enforced as written. */
    private static final Log _log =
            I2PAppContext.getGlobalContext().logManager()
                    .getLog(FilterDefinition.class);

    /** Limit applied when an element names none. */
    private final Threshold defaultThreshold;
    /** The rules of this definition. */
    private final FilterDefinitionElement[] elements;
    /** Counters kept for the definition. */
    private final Recorder[] recorders;
    /** How long a record is kept, in seconds. */
    private final int purgeSeconds;
    /** The widest recorder rule of this definition, in attempts per window. */
    private final int maxRecorderConnections;

    /**
     * Create a definition and derive the purge interval from the longest
     * threshold window among the default, the elements and the recorders.
     *
     * @param defaultThreshold threshold to apply to unknown remote destinations
     * @param elements the elements defined in the filter definition, if any
     * @param recorders the recorders defined in the filter definition, if any
     */
    FilterDefinition(Threshold defaultThreshold,
                        FilterDefinitionElement[] elements,
                        Recorder[] recorders) {
        this.defaultThreshold = defaultThreshold;
        this.elements = elements;
        this.recorders = recorders;

        int maxSeconds = defaultThreshold.getSeconds();
        int maxAttempts = defaultThreshold.getConnections();
        int maxRecorder = 0;
        for (FilterDefinitionElement element : elements) {
            Threshold threshold = element.getThreshold();
            maxSeconds = Math.max(maxSeconds, threshold.getSeconds());
            maxAttempts = Math.max(maxAttempts, threshold.getConnections());
        }
        for (Recorder recorder : recorders) {
            Threshold threshold = recorder.getThreshold();
            maxSeconds = Math.max(maxSeconds, threshold.getSeconds());
            maxRecorder = Math.max(maxRecorder, threshold.getConnections());
        }

        this.purgeSeconds = maxSeconds;
        this.maxRecorderConnections = maxRecorder;
        warnIfUnbreachable(Math.max(maxAttempts, maxRecorder));
    }

    /**
     * Say once, when a definition is read, that a rule of it counts more attempts
     * than a counter retains. Such a rule is never breached, and the person who
     * wrote the line has no other way to find that out. A rule of
     * {@link Threshold#ALLOW} is meant never to breach, so it is not reported.
     *
     * @param widest the largest attempt count among this definition's rules
     */
    private static void warnIfUnbreachable(int widest) {
        if (widest <= AccessCounter.MAX_RETAINED || widest == Integer.MAX_VALUE)
            return;
        _log.warn(UNBREACHABLE + "\n* Attempts: " + widest +
                  "\n* Retained: " + AccessCounter.MAX_RETAINED);
    }

    /**
     * The threshold applied to a destination matched by no element.
     *
     * @return the default threshold
     */
    Threshold getDefaultThreshold() {
        return defaultThreshold;
    }

    /**
     * The widest recorder rule of this definition, in attempts per window. The
     * attempt history kept for a destination has to answer that rule as well as the
     * admission rule that owns it, or the recorder never breaches.
     *
     * @return the largest connection count of any recorder, 0 if there are none
     * @since 0.9.72+
     */
    int getMaxRecorderConnections() {
        return maxRecorderConnections;
    }

    /**
     * The per-destination thresholds parsed from the definition file.
     *
     * @return the array as parsed, not a copy
     */
    FilterDefinitionElement[] getElements() {
        return elements;
    }

    /**
     * The loggers invoked when a destination's threshold is reached.
     *
     * @return the recorder array as parsed, not a copy
     */
    Recorder[] getRecorders() {
        return recorders;
    }

    /**
     * How often to purge access records, in seconds; the widest threshold
     * window in the definition.
     *
     * @return the purge seconds
     */
    int getPurgeSeconds() {
        return purgeSeconds;
    }
}
