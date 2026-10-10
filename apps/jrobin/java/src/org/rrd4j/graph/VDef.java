package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;
import org.rrd4j.data.Variable;

/**
 * Represents a variable definition (VDEF) in RRD graphs. A VDEF defines a data source that computes
 * values using variables like MIN, MAX, AVERAGE, etc.
 */
class VDef extends Source {
    private final String defName;
    private final Variable var;

    /**
     * Creates a VDEF data source bound to the given name and computation
     *
     * @param name the name this data source is addressed by in the graph
     * @param defName the VDEF expression name identifying the computation to apply
     * @param var the variable the computation reduces to, such as MIN, MAX or AVERAGE
     */
    VDef(String name, String defName, Variable var) {
        super(name);
        this.defName = defName;
        this.var = var;
    }

    /**
     * Asks the processor to evaluate this VDEF for the given data source
     *
     * @param dproc the processor to pass the data source request to
     */
    void requestData(DataProcessor dproc) {
        dproc.datasource(name, defName, var);
    }
}
