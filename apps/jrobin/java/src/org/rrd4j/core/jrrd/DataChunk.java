package org.rrd4j.core.jrrd;

import java.util.Map;
import org.rrd4j.data.IPlottable;
import org.rrd4j.data.LinearInterpolator;

/**
 * Models a chunk of result data from an RRDatabase.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
public class DataChunk {

    private static final String NEWLINE = System.getProperty("line.separator");

    /** Start time in seconds since epoch */
    private final long startTime;

    /** Row number offset relative to current row. Can be negative */
    final int startOffset;

    /** Row number offset relative to current row */
    final int endOffset;

    /** Step in seconds */
    private final long step;

    /** Number of datasources must be equal to number of datasources in file */
    final int dsCount;

    /** The data values */
    final double[][] data;
    private final int rows;

    /** Map datasource name to datasource index */
    private final Map<String, Integer> nameindex;

    /**
     * Creates an empty chunk of fetched data, allocating the value grid that
     * {@link Archive#loadData} then fills in with one row per archived time
     * step and one column per datasource.
     *
     * @param nameindex datasource name to column index, used to locate a named
     *        datasource within the value grid
     * @param startTime timestamp in seconds since the epoch of the chunk's first
     *        row, rounded down to a whole number of steps
     * @param startOffset row number of the first row, relative to the archive's
     *        current row; negative when the requested range starts before the
     *        archive's oldest row
     * @param endOffset row number one past the chunk's last row, relative to the
     *        archive's current row; negative for a range ending before it
     * @param step seconds between consecutive rows
     * @param dsCount number of datasources, which must match the datasource
     *        count recorded in the RRD file
     * @param rows number of rows to allocate, one per step spanning the range
     */
    DataChunk(
            Map<String, Integer> nameindex,
            long startTime,
            int startOffset,
            int endOffset,
            long step,
            int dsCount,
            int rows) {
        this.nameindex = nameindex;
        this.startTime = startTime;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.step = step;
        this.dsCount = dsCount;
        this.rows = rows;
        data = new double[rows][dsCount];
    }

    /**
     * Returns a summary of the contents of this data chunk. The first column is the time (RRD
     * format) and the following columns are the data source values.
     *
     * @return a summary of the contents of this data chunk.
     */
    public String toString() {

        StringBuilder sb = new StringBuilder();
        long time = startTime;

        for (int row = 0; row < rows; row++, time += step) {
            sb.append(time);
            sb.append(": ");

            for (int ds = 0; ds < dsCount; ds++) {
                sb.append(data[row][ds]);
                sb.append(" ");
            }

            sb.append(NEWLINE);
        }

        return sb.toString();
    }

    /**
     * getStart.
     *
     * @return the row number offset of this chunk's first row, relative to the current row
     */
    public int getStart() {
        return startOffset;
    }

    /**
     * getEnd.
     *
     * @return the row number offset just past this chunk's last row, relative to the current row
     */
    public int getEnd() {
        return endOffset;
    }

    /**
     * getStep.
     *
     * @return the number of seconds between consecutive rows in this chunk
     */
    public long getStep() {
        return step;
    }

    /**
     * getDsCount.
     *
     * @return the number of datasources held per row in this chunk
     */
    public int getDsCount() {
        return dsCount;
    }

    /**
     * Getter for the field <code>data</code>.
     *
     * @return the data
     */
    public double[][] getData() {
        return data;
    }

    /**
     * Getter for the time stamps values.
     *
     * @return array of time stamps in seconds
     */
    public long[] getTimestamps() {
        long[] date = new long[rows];
        long time = startTime;
        for (int row = 0; row < rows; row++, time += step) {
            date[row] = time;
        }
        return date;
    }

    /**
     * Extract a datasource from the datachunck given is name as a Plottable
     *
     * @param name the datasource name
     * @return a plottable for the datasource
     */
    public IPlottable toPlottable(String name) {
        Integer dsId = nameindex.get(name);
        if (dsId == null) throw new RuntimeException("datasource not not found: " + name);
        long[] date = new long[rows];
        double[] results = new double[rows];
        long time = startTime;
        for (int row = 0; row < rows; row++, time += step) {
            date[row] = time;
            results[row] = data[row][dsId];
        }
        return new LinearInterpolator(date, results);
    }
}
