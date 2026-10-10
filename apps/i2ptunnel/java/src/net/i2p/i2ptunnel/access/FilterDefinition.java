package net.i2p.i2ptunnel.access;

/**
 * Access filter definition.
 * <p>
 * POJO containing parsed representation from filter definition file.
 *
 * @since 0.9.40
 */
class FilterDefinition {

    private final Threshold defaultThreshold;
    private final FilterDefinitionElement[] elements;
    private final Recorder[] recorders;
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
     * @return the elements
     */
    FilterDefinitionElement[] getElements() {
        return elements;
    }

    /**
     * The loggers invoked when a destination's threshold is reached.
     *
     * @return the recorders
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
