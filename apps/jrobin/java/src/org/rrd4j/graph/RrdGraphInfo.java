package org.rrd4j.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Class to represent successfully created Rrd4j graph. Objects of this class are created by method
 * {@link org.rrd4j.graph.RrdGraph#getRrdGraphInfo()}.
 */
public class RrdGraphInfo {
    /** Path of the backing RRD file, or '-' when the graph was never written to disk. */
    String filename;
    /** Rendered image size in pixels, as requested from the graph definition. */
    int width, height;
    /** Supplies the rendered image bytes; throws IllegalStateException when unavailable. */
    Supplier<byte[]> bytesSource;
    /** Supplies the rendered image size in bytes; throws IllegalStateException when unavailable. */
    Supplier<Integer> bytesCount;
    /** The image information comment carried through from the graph definition. */
    String imgInfo;
    private final List<String> printLines = new ArrayList<>();
    private RrdGraphMeta meta;

    /** Prevent instantiation */
    RrdGraphInfo() {
        // cannot instantiate this class
    }

    /**
     * Attaches the plot geometry and series for this render.
     *
     * @param meta the metadata, never null
     */
    void setMeta(RrdGraphMeta meta) {
        this.meta = meta;
    }

    /**
     * The plot geometry and series, for a client that wants to invert a cursor
     * position into a time and a value. Absent when the graph was not rendered
     * through this package.
     *
     * @return the metadata, or null when none was recorded
     * @since 0.9.71+
     */
    public RrdGraphMeta getMeta() {
        return meta;
    }
    /**
     * Record a formatted PRINT line, in the order added.
     * @param printLine the already formatted line to record in the order added
     */

    void addPrintLine(String printLine) {
        printLines.add(printLine);
    }

    /**
     * Returns filename of the graph
     *
     * @return filename of the graph. '-' denotes in-memory graph (no file created)
     */
    public String getFilename() {
        return filename;
    }

    /**
     * Returns total graph width
     *
     * @return total graph width
     */
    public int getWidth() {
        return width;
    }

    /**
     * Returns total graph height
     *
     * @return total graph height
     */
    public int getHeight() {
        return height;
    }

    /**
     * Returns the rendered image bytes.
     *
     * @return the graph image bytes, in the format the graph definition selected
     * @throws IllegalStateException if the images bytes are unavailable or can't be read
     */
    public byte[] getBytes() {
        return bytesSource.get();
    }

    /**
     * Returns PRINT lines requested by {@link org.rrd4j.graph.RrdGraphDef#print(String,
     * org.rrd4j.ConsolFun, String)} method.
     *
     * @return An array of formatted PRINT lines
     */
    public String[] getPrintLines() {
        return printLines.toArray(new String[0]);
    }

    /**
     * Returns image information requested by {@link
     * org.rrd4j.graph.RrdGraphDef#setImageInfo(String)} method
     *
     * @return the formatted image information comment, or null when the graph
     *         definition set no imageInfo template
     */
    public String getImgInfo() {
        return imgInfo;
    }

    /**
     * Returns the number of bytes in the graph file
     *
     * @return Length of the graph file
     * @throws IllegalStateException if the images bytes are unavailable
     */
    public int getByteCount() {
        return bytesCount.get();
    }

    /**
     * Dumps complete graph information. Useful for debugging purposes.
     *
     * @return String containing complete graph information
     */
    public String dump() {
        StringBuilder b = new StringBuilder();
        b.append("filename = \"").append(getFilename()).append("\"\n");
        b.append("width = ")
                .append(getWidth())
                .append(", height = ")
                .append(getHeight())
                .append("\n");
        b.append("byteCount = ").append(getByteCount()).append("\n");
        b.append("imginfo = \"").append(getImgInfo()).append("\"\n");
        String[] plines = getPrintLines();
        if (plines.length == 0) {
            b.append("No print lines found\n");
        } else {
            for (int i = 0; i < plines.length; i++) {
                b.append("print[").append(i).append("] = \"").append(plines[i]).append("\"\n");
            }
        }
        return b.toString();
    }
}
