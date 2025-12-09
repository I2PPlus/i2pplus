package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;

/**
 * Represents a computed data source (CDEF) in RRD graphs. A CDEF evaluates a Reverse Polish
 * Notation (RPN) expression to compute values from other data sources. Enables mathematical
 * operations and data transformations within the graphing system.
 */
class CDef extends Source {
    private final String rpnExpression;

    CDef(String name, String rpnExpression) {
        super(name);
        this.rpnExpression = rpnExpression;
    }

    void requestData(DataProcessor dproc) {
        dproc.datasource(name, rpnExpression);
    }
}
