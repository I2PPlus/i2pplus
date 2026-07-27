package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;
import org.rrd4j.data.Variable;

/**
 * Represents a variable definition (VDEF) in RRD graphs. A VDEF defines a data source that computes
 * values using variables like MIN, MAX, AVERAGE, etc.
 */
class VDef extends Source {
    /** Def name */
    private final String defName;
    /** Var */
    private final Variable var;

    /** Constructor */
    VDef(String name, String defName, Variable var) {
        super(name);
        this.defName = defName;
        this.var = var;
    }
    /** Request data */
    void requestData(DataProcessor dproc) {
        dproc.datasource(name, defName, var);
    }
}
