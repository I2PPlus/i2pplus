package net.i2p.i2ptunnel.access;

/**
 * Access filter definition.
 * <p>
 * POJO containing parsed representation from filter definition file.
 *
 * @since 0.9.40
 */
class FilterDefinition {

    /** Limit applied when an element names none. */
    private final Threshold defaultThreshold;
    /** The rules of this definition. */
    private final FilterDefinitionElement[] elements;
    /** Counters kept for the definition. */
    private final Recorder[] recorders;
    /** How long a record is kept, in seconds. */
    private final int purgeSeconds;

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
        for (FilterDefinitionElement element : elements)
            maxSeconds = Math.max(maxSeconds, element.getThreshold().getSeconds());
        for (Recorder recorder : recorders)
            maxSeconds = Math.max(maxSeconds, recorder.getThreshold().getSeconds());

        this.purgeSeconds = maxSeconds;
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
