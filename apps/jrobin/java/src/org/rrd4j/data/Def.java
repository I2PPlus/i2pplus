package org.rrd4j.data;

import java.net.URI;
import org.rrd4j.ConsolFun;
import org.rrd4j.core.FetchData;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.core.RrdDb;

/**
 * RRD DEF (Data Source Definition) implementation.<br>
 * Represents a data source defined in an RRD file with specific name, consolidation function, and
 * backend configuration.
 */
class Def extends Source {
    private final URI rrdUri;
    private final String dsName;
    private final RrdBackendFactory backend;
    private final ConsolFun consolFun;
    private FetchData fetchData;

    Def(String name, FetchData fetchData) {
        this(name, name, fetchData);
    }

    Def(String name, String dsName, FetchData fetchData) {
        this(
                name,
                fetchData.getRequest().getParentDb().getCanonicalUri(),
                dsName,
                fetchData.getRequest().getConsolFun(),
                fetchData.getRequest().getParentDb().getRrdBackend().getFactory());
        this.fetchData = fetchData;
    }

    Def(String name, URI rrdUri, String dsName, ConsolFun consolFunc, RrdBackendFactory backend) {
        super(name);
        this.rrdUri = backend.getCanonicalUri(rrdUri);
        this.dsName = dsName;
        this.consolFun = consolFunc;
        this.backend = backend;
    }

    /** Canonical uri. */
    URI getCanonicalUri() {
        return rrdUri;
    }

    /** Ds name. */
    String getDsName() {
        return dsName;
    }

    /** Consol fun. */
    ConsolFun getConsolFun() {
        return consolFun;
    }

    /** Backend. */
    RrdBackendFactory getBackend() {
        return backend;
    }

    /** Is compatible with */
    boolean isCompatibleWith(Def def) {
        return getCanonicalUri().equals(def.getCanonicalUri())
                && getConsolFun() == def.consolFun
                && ((backend == null && def.backend == null)
                        || (backend != null && def.backend != null && backend.equals(def.backend)));
    }

    /** Rrd db. */
    RrdDb getRrdDb() {
        return fetchData.getRequest().getParentDb();
    }

    /** Fetch data. */
    void setFetchData(FetchData fetchData) {
        this.fetchData = fetchData;
    }

    /** Rrd timestamps. */
    long[] getRrdTimestamps() {
        return fetchData.getTimestamps();
    }

    /** Rrd values. */
    double[] getRrdValues() {
        return fetchData.getValues(dsName);
    }

    /** Archive end time. */
    long getArchiveEndTime() {
        return fetchData.getArcEndTime();
    }

    /** Fetch step. */
    long getFetchStep() {
        return fetchData.getStep();
    }

    @Override
    @Deprecated
    Aggregates getAggregates(long tStart, long tEnd) {
        long[] t = getRrdTimestamps();
        double[] v = getRrdValues();
        return new Aggregator(t, v).getAggregates(tStart, tEnd);
    }

    @Override
    @Deprecated
    double getPercentile(long tStart, long tEnd, double percentile) {
        long[] t = getRrdTimestamps();
        double[] v = getRrdValues();
        return new Aggregator(t, v).getPercentile(tStart, tEnd, percentile);
    }

    /** Is loaded */
    boolean isLoaded() {
        return fetchData != null;
    }
}
