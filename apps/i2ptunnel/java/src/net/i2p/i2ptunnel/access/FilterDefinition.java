package net.i2p.i2ptunnel.access;

/**
 * Access filter definition.
 * <p>
 * POJO containing parsed representation from filter definition file.
 *
 * @since 0.9.40
 */
class FilterDefinition {

    /** ignored */
    private final Threshold defaultThreshold;
    /** ignored */
    private final FilterDefinitionElement[] elements;
    /** ignored */
    private final Recorder[] recorders;
    /** ignored */
    private final int purgeSeconds;

    /**
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

    /** @return the default threshold */
    Threshold getDefaultThreshold() {
        return defaultThreshold;
    }

    /** @return the elements */
    FilterDefinitionElement[] getElements() {
        return elements;
    }

    /** @return the recorders */
    Recorder[] getRecorders() {
        return recorders;
    }

    /** @return the purge seconds */
    int getPurgeSeconds() {
        return purgeSeconds;
    }
}
