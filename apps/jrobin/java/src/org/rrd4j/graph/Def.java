package org.rrd4j.graph;

import java.net.URI;
import org.rrd4j.ConsolFun;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.data.DataProcessor;

/**
 * Represents a data definition (DEF) in RRD graphs. A DEF defines a data source by specifying an
 * RRD file, data source name, and consolidation function. This is the primary way to import data
 * from RRD files into graphs.
 */
class Def extends Source {

    private final URI rrdUri;
    private final String dsName;
    private final RrdBackendFactory backend;

    private final ConsolFun consolFun;

    Def(String name, URI rrdUri, String dsName, ConsolFun consolFun, RrdBackendFactory backend) {
        super(name);
        this.rrdUri = rrdUri;
        this.dsName = dsName;
        this.consolFun = consolFun;
        this.backend = backend;
    }

    void requestData(DataProcessor dproc) {
        dproc.datasource(name, rrdUri, dsName, consolFun, backend);
    }
}
