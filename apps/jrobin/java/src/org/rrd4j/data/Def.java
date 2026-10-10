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

    /**
     * Creates a DEF that reads the datasource of the same name, since the single name argument is
     * used for both the DEF name and the datasource name within the RRD.
     *
     * @param name DEF name, also used as the name of the datasource to read
     * @param fetchData the fetched values backing this DEF, consulted by the accessors below
     */
    Def(String name, FetchData fetchData) {
        this(name, name, fetchData);
    }

    /**
     * Creates a DEF for a named datasource of the RRD the fetch was run against, taking the file
     * URI, the consolidation function and the backend from that fetch's request rather than from
     * the caller.
     *
     * @param name DEF name, identifying this source within the graph's data set
     * @param dsName name of the datasource to read within the RRD, which need not match name
     * @param fetchData the fetched values backing this DEF, consulted by the accessors below
     */
    Def(String name, String dsName, FetchData fetchData) {
        this(
                name,
                fetchData.getRequest().getParentDb().getCanonicalUri(),
                dsName,
                fetchData.getRequest().getConsolFun(),
                fetchData.getRequest().getParentDb().getRrdBackend().getFactory());
        this.fetchData = fetchData;
    }

    /**
     * Creates a DEF naming its RRD, datasource, consolidation function and backend explicitly, for
     * callers that are not deriving them from a fetch. The URI is resolved to the backend's
     * canonical form so two DEFs addressing the same store compare equal.
     *
     * @param name DEF name, identifying this source within the graph's data set
     * @param rrdUri location of the RRD holding the datasource, in any form the backend accepts
     * @param dsName name of the datasource to read within that RRD
     * @param consolFunc consolidation function (CF) the archive values were produced with
     * @param backend factory that opens the RRD and canonicalises rrdUri
     */
    Def(String name, URI rrdUri, String dsName, ConsolFun consolFunc, RrdBackendFactory backend) {
        super(name);
        this.rrdUri = backend.getCanonicalUri(rrdUri);
        this.dsName = dsName;
        this.consolFun = consolFunc;
        this.backend = backend;
    }

    /**
     * Canonical uri.
     *
     * @return the RRD location in the backend's canonical form
     */
    URI getCanonicalUri() {
        return rrdUri;
    }

    /**
     * Ds name.
     *
     * @return the name of the datasource read within the RRD
     */
    String getDsName() {
        return dsName;
    }

    /**
     * Consol fun.
     *
     * @return the consolidation function the archive values were produced with
     */
    ConsolFun getConsolFun() {
        return consolFun;
    }

    /**
     * Data backend.
     *
     * @return the factory that opens the RRD for this DEF
     */
    RrdBackendFactory getBackend() {
        return backend;
    }

    /**
     * Is compatible with
     *
     * @param def the other DEF to compare against
     * @return true if both name the same RRD, CF and backend, so their values can be joined
     */
    boolean isCompatibleWith(Def def) {
        return getCanonicalUri().equals(def.getCanonicalUri())
                && getConsolFun() == def.consolFun
                && ((backend == null && def.backend == null)
                        || (backend != null && def.backend != null && backend.equals(def.backend)));
    }

    /**
     * Rrd db.
     *
     * @return the RRD database the enclosing fetch ran against
     */
    RrdDb getRrdDb() {
        return fetchData.getRequest().getParentDb();
    }

    /**
     * Fetch data.
     *
     * @param fetchData the fetched values backing this DEF, consulted by the accessors below
     */
    void setFetchData(FetchData fetchData) {
        this.fetchData = fetchData;
    }

    /**
     * Rrd timestamps.
     *
     * @return the equidistant timestamps of the fetched rows, in seconds
     */
    long[] getRrdTimestamps() {
        return fetchData.getTimestamps();
    }

    /**
     * Rrd values.
     *
     * @return the archived values for this DEF's datasource, aligned with getRrdTimestamps()
     */
    double[] getRrdValues() {
        return fetchData.getValues(dsName);
    }

    /**
     * Archive end time.
     *
     * @return the timestamp in seconds of the last populated slot in the archive
     */
    long getArchiveEndTime() {
        return fetchData.getArcEndTime();
    }

    /**
     * Fetch step.
     *
     * @return the seconds between consecutive timestamps in the fetched rows
     */
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

    /**
     * Is loaded
     *
     * @return true if fetch data has been attached, so the value accessors will not fail
     */
    boolean isLoaded() {
        return fetchData != null;
    }
}
