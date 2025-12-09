package org.rrd4j.graph;

/**
 * Iterator for processing paths in RRD graph rendering. Handles segmentation of data arrays into
 * continuous paths, skipping NaN values.
 */
class PathIterator {
    /** Array of y-coordinate values to iterate through */
    private final double[] y;

    /** Current position in the array */
    private int pos = 0;

    /**
     * Creates a path iterator for the specified y-coordinate array.
     *
     * @param y array of y-coordinate values
     */
    PathIterator(double[] y) {
        this.y = y;
    }

    /**
     * Gets the next continuous path segment from the data. Skips NaN values and returns start/end
     * indices of valid segments.
     *
     * @return array with [start, end] indices, or null if no more paths
     */
    int[] getNextPath() {
        while (pos < y.length) {
            if (Double.isNaN(y[pos])) {
                pos++;
            } else {
                int endPos = pos + 1;
                while (endPos < y.length && !Double.isNaN(y[endPos])) {
                    endPos++;
                }
                int[] result = {pos, endPos};
                pos = endPos;
                if (result[1] - result[0] >= 2) {
                    return result;
                }
            }
        }
        return null;
    }
}
