package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;
import org.rrd4j.data.IPlottable;

/**
 * Represents a plottable data definition (PDEF) in RRD graphs. A PDEF defines a data source using a
 * plottable object that can generate values programmatically.
 */
class PDef extends Source {
    private final IPlottable plottable;

    /**
     * Creates a plottable data definition under the given data source name.
     * @param name the data source name this definition is registered as
     * @param plottable the plottable that generates the data source values
     */
    PDef(String name, IPlottable plottable) {
        super(name);
        this.plottable = plottable;
    }
    /**
     * Data processor to register the datasource with.
     * @param dproc data processor to register the datasource with
     */

    void requestData(DataProcessor dproc) {
        dproc.datasource(name, plottable);
    }
}
