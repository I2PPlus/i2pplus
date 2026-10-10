package org.rrd4j.data;

/**
 * RRD PDEF (Plottable Data Source Definition) implementation.<br>
 * Creates data sources from plottable objects that can provide values based on timestamps.
 */
class PDef extends Source implements NonRrdSource {
    private final IPlottable plottable;

    /**
     * Create a plottable backed by the named data source.
     *
     * @param name the name of the data source
     * @param plottable2 the plottable object to provide values
     */
    PDef(String name, IPlottable plottable2) {
        super(name);
        this.plottable = plottable2;
    }

    /**
     * Read one value per timestamp from the plottable and store them.
     *
     * @param tStart first timestamp of the interval, inclusive
     * @param tEnd last timestamp of the interval, exclusive
     * @param dataProcessor the processor receiving the computed values
     */
    public void calculate(long tStart, long tEnd, DataProcessor dataProcessor) {
        long[] times = getTimestamps();
        double[] vals = new double[times.length];
        for (int i = 0; i < times.length; i++) {
            vals[i] = plottable.getValue(times[i]);
        }
        setValues(vals);
    }
}
