package org.rrd4j.graph;

import org.rrd4j.core.FetchData;
import org.rrd4j.data.DataProcessor;

/** Definition for a data source in a graph. @author Mathias Bogaert */
class TDef extends Source {
    /** Fetch data */
    private final FetchData fetchData;
    /** Ds name */
    private final String dsName;

    TDef(String name, String dsName, FetchData fetchData) {
        super(name);
        this.dsName = dsName;
        this.fetchData = fetchData;
    }
    /**
     * @param dproc data processor to register the datasource with
     */

    @Override
    void requestData(DataProcessor dproc) {
        dproc.datasource(name, dsName, fetchData);
    }
}
