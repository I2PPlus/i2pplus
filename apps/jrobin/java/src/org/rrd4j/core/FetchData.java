package org.rrd4j.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.rrd4j.ConsolFun;
import org.rrd4j.data.DataProcessor;

/**
 * Class used to represent data fetched from the RRD. Object of this class is created when the
 * method {@link org.rrd4j.core.FetchRequest#fetchData fetchData()} is called on a {@link
 * org.rrd4j.core.FetchRequest FetchRequest} object.
 *
 * <p>Data returned from the RRD is, simply, just one big table filled with timestamps and
 * corresponding datasource values. Use {@link #getRowCount getRowCount()} method to count the
 * number of returned timestamps (table rows).
 *
 * <p>The first table column is filled with timestamps. Time intervals between consecutive
 * timestamps are guaranteed to be equal. Use {@link #getTimestamps getTimestamps()} method to get
 * an array of timestamps returned.
 *
 * <p>Remaining columns are filled with datasource values for the whole timestamp range, on a
 * column-per-datasource basis. Use {@link #getColumnCount getColumnCount()} to find the number of
 * datasources and {@link #getValues(int) getValues(i)} method to obtain all values for the i-th
 * datasource. Returned datasource values correspond to the values returned with {@link
 * #getTimestamps() getTimestamps()} method.
 *
 * <p>This data structure holds the fetched information.
 *
 */
@SuppressWarnings("deprecation")
public class FetchData {
    /** The RPN compiler source name, anything funny will do. */
    private static final String RPN_SOURCE_NAME = "WHERE THE SPEECHLES UNITE IN A SILENT ACCORD";

    /** The request that produced this data. */
    private final FetchRequest request;
    /** The names of the data sources. */
    private String[] dsNames;
    /** The timestamps of the fetched rows. */
    private long[] timestamps;
    /** The fetched values, by row and source. */
    private double[][] values;

    /** The archive that matched the request. */
    private final Archive matchingArchive;
    /** The step of the matching archive. */
    private final long arcStep;
    /** The end time of the matching archive. */
    private final long arcEndTime;

    /**
     * Bind the request to the archive that will satisfy it.
     *
     * @param matchingArchive the archive selected for this request
     * @param request the fetch request, whose DS filter names the columns to read
     * @throws IOException if the archive's data sources cannot be listed
     */
    FetchData(Archive matchingArchive, FetchRequest request) throws IOException {
        this.matchingArchive = matchingArchive;
        this.arcStep = matchingArchive.getArcStep();
        this.arcEndTime = matchingArchive.getEndTime();
        this.dsNames = request.getFilter();
        if (this.dsNames == null) {
            this.dsNames = matchingArchive.getParentDb().getDsNames();
        }
        this.request = request;
    }

    /**
     * Sets the timestamps for the fetched data.
     *
     * @param timestamps array of timestamps
     */
    void setTimestamps(long[] timestamps) {
        this.timestamps = timestamps;
    }

    /**
     * Sets the values for the fetched data.
     *
     * @param values 2D array of values
     */
    void setValues(double[][] values) {
        this.values = values;
    }

    /**
     * Returns the number of rows fetched from the corresponding RRD. Each row represents datasource
     * values for the specific timestamp.
     *
     * @return Number of rows.
     */
    public int getRowCount() {
        return timestamps.length;
    }

    /**
     * Returns the number of columns fetched from the corresponding RRD. This number is always equal
     * to the number of datasources defined in the RRD. Each column represents values of a single
     * datasource.
     *
     * @return Number of columns (datasources).
     */
    public int getColumnCount() {
        return dsNames.length;
    }

    /**
     * Returns an array of timestamps covering the whole range specified in the {@link FetchRequest
     * FetchReguest} object.
     *
     * @return Array of equidistant timestamps.
     */
    public long[] getTimestamps() {
        return timestamps;
    }

    /**
     * Returns the step with which this data was fetched.
     *
     * @return Step as long.
     */
    public long getStep() {
        return timestamps[1] - timestamps[0];
    }

    /**
     * Returns all archived values for a single datasource. Returned values correspond to timestamps
     * returned with {@link #getTimestamps getTimestamps()} method.
     *
     * @param dsIndex Datasource index, from 0 to {@link #getColumnCount} - 1
     * @return Array of single datasource values.
     */
    public double[] getValues(int dsIndex) {
        return values[dsIndex];
    }

    /**
     * Returns all archived values for all datasources. Returned values correspond to timestamps
     * returned with {@link #getTimestamps getTimestamps()} method.
     *
     * @return Two-dimensional aray of all datasource values.
     */
    public double[][] getValues() {
        return values;
    }

    /**
     * Returns all archived values for a single datasource. Returned values correspond to timestamps
     * returned with {@link #getTimestamps getTimestamps()} method.
     *
     * @param dsName Datasource name, as it appears in the RRD
     * @return Array of single datasource values.
     */
    public double[] getValues(String dsName) {
        for (int dsIndex = 0; dsIndex < getColumnCount(); dsIndex++) {
            if (dsName.equals(dsNames[dsIndex])) {
                return getValues(dsIndex);
            }
        }
        throw new IllegalArgumentException("Datasource [" + dsName + "] not found");
    }

    /**
     * Returns a set of values created by applying RPN expression to the fetched data. For example,
     * if you have two datasources named <code>x</code> and <code>y</code> in this FetchData and you
     * want to calculate values for <code>(x+y)/2</code> use something like:
     *
     * <p><code>getRpnValues("x,y,+,2,/");</code>
     *
     * @param rpnExpression RRDTool-like RPN expression
     * @return Calculated values, one per timestamp this fetch returned
     * @throws java.lang.IllegalArgumentException Thrown if invalid RPN expression is supplied
     */
    public double[] getRpnValues(String rpnExpression) {
        DataProcessor dataProcessor = createDataProcessor(rpnExpression);
        return dataProcessor.getValues(RPN_SOURCE_NAME);
    }

    /**
     * Returns {@link FetchRequest FetchRequest} object used to create this FetchData object.
     *
     * @return Fetch request object.
     */
    public FetchRequest getRequest() {
        return request;
    }

    /**
     * Returns the first timestamp in this FetchData object.
     *
     * @return The smallest timestamp.
     */
    public long getFirstTimestamp() {
        return timestamps[0];
    }

    /**
     * Returns the last timestamp in this FecthData object.
     *
     * @return The biggest timestamp.
     */
    public long getLastTimestamp() {
        return timestamps[timestamps.length - 1];
    }

    /**
     * Returns Archive object which is determined to be the best match for the timestamps specified
     * in the fetch request. All datasource values are obtained from round robin archives belonging
     * to this archive.
     *
     * @return Matching archive, the one the datasource values were read from
     */
    public Archive getMatchingArchive() {
        return matchingArchive;
    }

    /**
     * Returns array of datasource names found in the corresponding RRD. If the request was filtered
     * (data was fetched only for selected datasources), only datasources selected for fetching are
     * returned.
     *
     * @return Array of datasource names.
     */
    public String[] getDsNames() {
        return dsNames;
    }

    /**
     * Retrieve the table index number of a datasource by name. Names are case sensitive.
     *
     * @param dsName Name of the datasource for which to find the index.
     * @return Index number of the datasources in the value table.
     */
    public int getDsIndex(String dsName) {
        // Let's assume the table of dsNames is always small, so it is not necessary to use a
        // hashmap for lookups
        for (int i = 0; i < dsNames.length; i++) {
            if (dsNames[i].equals(dsName)) {
                return i;
            }
        }
        return -1; // Datasource not found !
    }

    /**
     * Dumps the content of the whole FetchData object. Useful for debugging.
     *
     * @return a {@link java.lang.String} containing the contents of this object, for debugging.
     */
    public String dump() {
        StringBuilder buffer = new StringBuilder();
        for (int row = 0; row < getRowCount(); row++) {
            buffer.append(timestamps[row]);
            buffer.append(":  ");
            for (int dsIndex = 0; dsIndex < getColumnCount(); dsIndex++) {
                buffer.append(Util.formatDouble(values[dsIndex][row], true));
                buffer.append("  ");
            }
            buffer.append("\n");
        }
        return buffer.toString();
    }

    /**
     * Returns string representing fetched data in a RRDTool-like form.
     *
     * @return Fetched data as a string in a rrdfetch-like output form.
     */
    public String toString() {
        // print header row
        StringBuilder buff = new StringBuilder();
        buff.append(padWithBlanks("", 10)).append(" ");
        for (String dsName : dsNames) {
            buff.append(padWithBlanks(dsName, 18));
        }
        buff.append("\n \n");
        for (int i = 0; i < timestamps.length; i++) {
            buff.append(padWithBlanks(Long.toString(timestamps[i]), 10));
            buff.append(":");
            for (int j = 0; j < dsNames.length; j++) {
                double value = values[j][i];
                String valueStr = Double.isNaN(value) ? "nan" : Util.formatDouble(value);
                buff.append(padWithBlanks(valueStr, 18));
            }
            buff.append("\n");
        }
        return buff.toString();
    }

    /**
     * Pads the text with blanks to the width.
     *
     * @param input the text to pad
     * @param width the target width
     * @return the padded text
     */
    private static String padWithBlanks(String input, int width) {
        StringBuilder buff = new StringBuilder();
        int diff = width - input.length();
        while (diff-- > 0) {
            buff.append(' ');
        }
        buff.append(input);
        return buff.toString();
    }

    /**
    /**
     * Dumps fetch data to output stream in XML format. A flush is issued at the end of the xml
     * generation.
     *
     * @param outputStream Output stream to dump fetch data to
     */
    public void exportXml(OutputStream outputStream) {
        // No auto flush for XmlWriter, it will be flushed once, when export is finished
        try (XmlWriter writer = new XmlWriter(outputStream, false)) {
            exportXml(writer);
        }
    }

    /**
     * Write this fetch result as a fetch_data element tree.
     *
     * @param writer the XML writer that receives the fetch_data element tree; it is
                            flushed but never closed, so the caller may append to it
     */
    public void exportXml(XmlWriter writer) {
        writer.startTag("fetch_data");
        writer.startTag("request");
        writer.writeTag("file", request.getParentDb().getPath());
        writer.writeComment(request.getFetchStart());
        writer.writeTag("start", request.getFetchStart());
        writer.writeComment(request.getFetchEnd());
        writer.writeTag("end", request.getFetchEnd());
        writer.writeTag("resolution", request.getResolution());
        writer.writeTag("cf", request.getConsolFun());
        writer.closeTag(); // request
        writer.startTag("datasources");
        for (String dsName : dsNames) {
            writer.writeTag("name", dsName);
        }
        writer.closeTag(); // datasources
        writer.startTag("data");
        for (int i = 0; i < timestamps.length; i++) {
            writer.startTag("row");
            writer.writeComment(timestamps[i]);
            writer.writeTag("timestamp", timestamps[i]);
            writer.startTag("values");
            for (int j = 0; j < dsNames.length; j++) {
                writer.writeTag("v", values[j][i]);
            }
            writer.closeTag(); // values
            writer.closeTag(); // row
        }
        writer.closeTag(); // data
        writer.closeTag(); // fetch_data
        writer.flush();
    }

    /**
     * Dumps fetch data to file in XML format.
     *
     * @param filepath Path to destination file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public void exportXml(String filepath) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(Paths.get(filepath))) {
            exportXml(outputStream);
        }
    }

    /**
     * Dumps fetch data in XML format.
     *
     * @return String containing XML formatted fetch data
     */
    public String exportXml() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        exportXml(outputStream);
        // Always use UTF-8 charset to avoid encoding issues
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Returns the step of the corresponding RRA archive
     *
     * @return Archive step in seconds
     */
    public long getArcStep() {
        return arcStep;
    }

    /**
     * Returns the timestamp of the last populated slot in the corresponding RRA archive
     *
     * @return Timestamp in seconds
     */
    public long getArcEndTime() {
        return arcEndTime;
    }

    /**
     * Builds the processor for an RPN expression.
     *
     * @param rpnExpression the expression to compile
     * @return the processor for the expression
     */
    private DataProcessor createDataProcessor(String rpnExpression) {
        DataProcessor dataProcessor =
                new DataProcessor(request.getFetchStart(), request.getFetchEnd());
        for (String dsName : dsNames) {
            dataProcessor.addDatasource(dsName, this);
        }
        if (rpnExpression != null) {
            dataProcessor.addDatasource(RPN_SOURCE_NAME, rpnExpression);
        }
        try {
            dataProcessor.processData();
        } catch (IOException ioe) {
            // highly unlikely, since all datasources have already calculated values
            throw new RuntimeException("Impossible error: " + ioe);
        }
        return dataProcessor;
    }
}
