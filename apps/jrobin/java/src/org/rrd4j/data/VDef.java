package org.rrd4j.data;

import java.util.Arrays;

/**
 * RRD VDEF (Variable Definition) implementation.<br>
 * Creates computed data sources by applying aggregation functions to existing data sources.
 */
class VDef extends Source implements NonRrdSource {
    private final String defName;
    private final Variable var;

    /**
     * Create a computed data source backed by an aggregation over another source.
     *
     * @param name the name this source is published under
     * @param defName the name of the source this variable aggregates
     * @param aggr the aggregation to apply to that source
     */
    VDef(String name, String defName, Variable aggr) {
        super(name);
        this.defName = defName;
        this.var = aggr;
    }

    /**
     * Read the def name this source is published under.
     * @return the def name
     */
    String getDefName() {
        return defName;
    }

    /**
     * Aggregate the named source over the requested time range into this variable.
     *
     * @param tStart start time for calculation
     * @param tEnd end time for calculation
     * @param dataProcessor DataProcessor object used to look up the named source
     */
    public void calculate(long tStart, long tEnd, DataProcessor dataProcessor) {
        String defName = getDefName();
        Source source = dataProcessor.getSource(defName);
        var.calculate(source, tStart, tEnd);
    }

    /**
     * Value from the wrapped variable.
     * @return the value the wrapped Variable currently reports
     */
    public Variable.Value getValue() {
        return var.getValue();
    }

    @Override
    double[] getValues() {
        int count = getTimestamps().length;
        double[] values = new double[count];
        Arrays.fill(values, var.getValue().value);
        return values;
    }
}
