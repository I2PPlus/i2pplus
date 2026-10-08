package org.rrd4j.core;

import java.io.Closeable;
import java.io.IOException;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;

/**
 * An abstract class to import data from external source.
 *
 * @author Fabrice Bacchella
 * @since 3.5
 */
public abstract class DataImporter implements Closeable {

    /** Creates an importer; every subclass reads its source in its own constructor */
    public DataImporter() {}

    // header
    /**
     * Reads the RRD file format version the export was written with.
     *
     * @return the version string, such as "008"
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract String getVersion() throws IOException;

    /**
     * Reads the moment the RRD was last updated.
     *
     * @return a unix timestamp in seconds, aligned to the step boundary
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract long getLastUpdateTime() throws IOException;

    /**
     * Reads the base interval between two primary data points.
     *
     * @return the step size in seconds
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract long getStep() throws IOException;

    /**
     * Counts the data sources declared by the RRD.
     *
     * @return the number of data sources
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract int getDsCount() throws IOException;

    /**
     * Counts the archives (RRAs) declared by the RRD.
     *
     * @return the number of archives
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract int getArcCount() throws IOException;

    // datasource
    /**
     * Reads the name of a data source.
     *
     * @param dsIndex zero-based index of the data source
     * @return the data source name
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract String getDsName(int dsIndex) throws IOException;

    /**
     * Reads the type of a data source.
     *
     * @param dsIndex zero-based index of the data source
     * @return the type driving how consecutive samples are combined
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract DsType getDsType(int dsIndex) throws IOException;

    /**
     * Reads the heartbeat of a data source.
     *
     * @param dsIndex zero-based index of the data source
     * @return the maximum gap in seconds between two updates before the data source is declared
     *     dead
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract long getHeartbeat(int dsIndex) throws IOException;

    /**
     * Reads the lower bound a data source accepts.
     *
     * @param dsIndex zero-based index of the data source
     * @return the minimum value, or NaN when unbounded
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract double getMinValue(int dsIndex) throws IOException;

    /**
     * Reads the upper bound a data source accepts.
     *
     * @param dsIndex zero-based index of the data source
     * @return the maximum value, or NaN when unbounded
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract double getMaxValue(int dsIndex) throws IOException;

    // datasource state
    /**
     * Reads the last value recorded for a data source.
     *
     * @param dsIndex zero-based index of the data source
     * @return the most recent value, unknown until the next update lands
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract double getLastValue(int dsIndex) throws IOException;

    /**
     * Reads the partial total a data source is carrying into the current step.
     *
     * @param dsIndex zero-based index of the data source
     * @return the value accumulated so far for the step being built
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract double getAccumValue(int dsIndex) throws IOException;

    /**
     * Reads when a data source last saw a usable sample.
     *
     * @param dsIndex zero-based index of the data source
     * @return a unix timestamp in seconds of the last valid sample, or 0 if it never had one
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract long getNanSeconds(int dsIndex) throws IOException;

    // archive
    /**
     * Reads the consolidation function of an archive.
     *
     * @param arcIndex zero-based index of the archive
     * @return the function folding primary data points into one row
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract ConsolFun getConsolFun(int arcIndex) throws IOException;

    /**
     * Reads the xfiles factor of an archive.
     *
     * @param arcIndex zero-based index of the archive
     * @return the share of a step that an unknown value occupies, from 0 to 1
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract double getXff(int arcIndex) throws IOException;

    /**
     * Reads how many primary data points one archive row covers.
     *
     * @param arcIndex zero-based index of the archive
     * @return the number of steps consolidated into a single row
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract int getSteps(int arcIndex) throws IOException;

    /**
     * Counts the rows an archive currently holds.
     *
     * @param arcIndex zero-based index of the archive
     * @return the number of archive rows stored, which bounds {@link #getValues} in length
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract int getRows(int arcIndex) throws IOException;

    // archive state
    /**
     * Reads the value an archive is accumulating for one data source.
     *
     * @param arcIndex zero-based index of the archive
     * @param dsIndex zero-based index of the data source
     * @return the accumulated value held in the archive state, waiting for the next step
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract double getStateAccumValue(int arcIndex, int dsIndex) throws IOException;

    /**
     * Counts the unknown primary data points an archive is holding.
     *
     * @param arcIndex zero-based index of the archive
     * @param dsIndex zero-based index of the data source
     * @return the number of unknown steps inside the archive state, waiting for the next step
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract int getStateNanSteps(int arcIndex, int dsIndex) throws IOException;

    /**
     * Reads one data source's series from an archive.
     *
     * @param arcIndex zero-based index of the archive
     * @param dsIndex zero-based index of the data source
     * @return the archive's stored values, oldest first, one per {@link #getRows} row
     * @throws java.io.IOException if the export cannot be read
     */
    public abstract double[] getValues(int arcIndex, int dsIndex) throws IOException;

    /**
     * Estimates the byte length needed to hold everything this importer exposes.
     *
     * @return the file size in bytes to reserve before writing the RRD
     * @throws java.io.IOException if the export cannot be read
     */
    protected long getEstimatedSize() throws IOException {
        int dsCount = getDsCount();
        int arcCount = getArcCount();
        int rowCount = 0;
        for (int i = 0; i < arcCount; i++) {
            rowCount += getRows(i);
        }
        String[] dsNames = new String[getDsCount()];
        for (int i = 0; i < dsNames.length; i++) {
            dsNames[i] = getDsName(i);
        }
        return RrdDef.calculateSize(dsCount, arcCount, rowCount, dsNames);
    }

    /**
     * Frees whatever the importer holds open; the default importer needs nothing.
     *
     * @throws java.io.IOException if the underlying source cannot be closed
     */
    void release() throws IOException {
        // NOP
    }

    /**
     * Releases the import source once the RRD has been written.
     *
     * @throws java.io.IOException if the underlying source cannot be closed
     */
    @Override
    public void close() throws IOException {
        release();
    }
}
