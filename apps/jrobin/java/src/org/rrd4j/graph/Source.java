package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;

/**
 * Abstract base class for data sources in RRD graphs. Represents a named data source that can
 * provide values for graphing. All data sources must implement requestData() to supply data to the
 * processor.
 */
abstract class Source {
    /** The name of this data source */
    final String name;

    /**
     * Creates a new data source with the specified name.
     *
     * @param name the name of this data source
     */
    Source(String name) {
        this.name = name;
    }

    /**
     * Requests data from this source and provides it to the data processor.
     *
     * @param dproc the data processor to receive the data
     */
    abstract void requestData(DataProcessor dproc);
}
