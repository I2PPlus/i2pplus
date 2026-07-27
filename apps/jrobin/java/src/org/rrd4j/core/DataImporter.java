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

    // header
    /**
     * getVersion.
     */
    public abstract String getVersion() throws IOException;

    /**
     * getLastUpdateTime.
     */
    public abstract long getLastUpdateTime() throws IOException;

    /**
     * getStep.
     */
    public abstract long getStep() throws IOException;

    /**
     * getDsCount.
     */
    public abstract int getDsCount() throws IOException;

    /**
     * getArcCount.
     */
    public abstract int getArcCount() throws IOException;

    // datasource
    /**
     * getDsName.
     */
    public abstract String getDsName(int dsIndex) throws IOException;

    /**
     * getDsType.
     */
    public abstract DsType getDsType(int dsIndex) throws IOException;

    /**
     * getHeartbeat.
     */
    public abstract long getHeartbeat(int dsIndex) throws IOException;

    /**
     * getMinValue.
     */
    public abstract double getMinValue(int dsIndex) throws IOException;

    /**
     * getMaxValue.
     */
    public abstract double getMaxValue(int dsIndex) throws IOException;

    // datasource state
    /**
     * getLastValue.
     */
    public abstract double getLastValue(int dsIndex) throws IOException;

    /**
     * getAccumValue.
     */
    public abstract double getAccumValue(int dsIndex) throws IOException;

    /**
     * getNanSeconds.
     */
    public abstract long getNanSeconds(int dsIndex) throws IOException;

    // archive
    /**
     * getConsolFun.
     */
    public abstract ConsolFun getConsolFun(int arcIndex) throws IOException;

    /**
     * getXff.
     */
    public abstract double getXff(int arcIndex) throws IOException;

    /**
     * getSteps.
     */
    public abstract int getSteps(int arcIndex) throws IOException;

    /**
     * getRows.
     */
    public abstract int getRows(int arcIndex) throws IOException;

    // archive state
    /**
     * getStateAccumValue.
     */
    public abstract double getStateAccumValue(int arcIndex, int dsIndex) throws IOException;

    /**
     * getStateNanSteps.
     */
    public abstract int getStateNanSteps(int arcIndex, int dsIndex) throws IOException;

    /**
     * getValues.
     */
    public abstract double[] getValues(int arcIndex, int dsIndex) throws IOException;

    /**
     * getEstimatedSize.
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

    /** ignored */
    void release() throws IOException {
        // NOP
    }

    /**
     * close.
     */
    @Override
    public void close() throws IOException {
        release();
    }
}
