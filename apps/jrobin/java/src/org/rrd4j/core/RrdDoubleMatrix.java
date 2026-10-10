package org.rrd4j.core;

import java.io.IOException;

/**
 * RRD primitive type for handling matrices of double values.
 *
 * <p>This class provides methods to store and retrieve two-dimensional matrices of double values in
 * RRD files. It organizes data in a row-column format and provides efficient access to individual
 * elements and ranges.
 *
 * @param <U> The type of RrdUpdater this primitive belongs to
 */
class RrdDoubleMatrix<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private static final String LENGTH = ", length=";
    private final int rows;
    private final int columns;

    /**
     * Allocate a row x column matrix of doubles in the updater's backend storage.
     *
     * @param updater the updater whose allocator and backend back this primitive
     * @param row the number of rows in the matrix
     * @param column the number of columns in the matrix
     * @param shouldInitialize if true, fill every slot with NaN before returning
     * @throws IOException if the backend storage cannot be written
     */
    RrdDoubleMatrix(RrdUpdater<U> updater, int row, int column, boolean shouldInitialize)
            throws IOException {
        super(updater, RrdPrimitive.RRD_DOUBLE, row * column, false);
        this.rows = row;
        this.columns = column;
        if (shouldInitialize) writeDouble(0, Double.NaN, rows * columns);
    }

    /**
     * Stores one value at the given matrix position.
     *
     * @param column the zero-based column index
     * @param index the zero-based row index
     * @param value the double to store at that position
     * @throws IOException if the backend storage cannot be written
     */
    void set(int column, int index, double value) throws IOException {
        writeDouble(columns * index + column, value);
    }

    /**
     * Stores one value in each of the next count rows of a column.
     *
     * @param column the zero-based column index
     * @param index the zero-based row index at which to start writing
     * @param value the double to store at each of the count positions
     * @param count how many consecutive rows to write; index plus count may not exceed the row count
     * @throws IOException if the backend storage cannot be written
     */
    void set(int column, int index, double value, int count) throws IOException {
        // rollovers not allowed!
        assert index + count <= rows
                : "Invalid robin index supplied: index="
                        + index
                        + ", count="
                        + count
                        + LENGTH
                        + rows;
        for (int i = columns * index + column, c = 0; c < count; i += columns, c++)
            writeDouble(i, value);
    }

    /**
     * Stores a column of values, one per consecutive row.
     *
     * @param column the zero-based column index
     * @param index the zero-based row index at which to start writing
     * @param newValues the doubles to store, in increasing row order
     * @throws IOException if the backend storage cannot be written
     */
    public void set(int column, int index, double[] newValues) throws IOException {
        int count = newValues.length;
        // rollovers not allowed!
        assert index + count <= rows
                : "Invalid robin index supplied: index="
                        + index
                        + ", count="
                        + count
                        + LENGTH
                        + rows;
        for (int i = columns * index + column, c = 0; c < count; i += columns, c++)
            writeDouble(i, newValues[c]);
    }

    /**
     * Reads one value from the given matrix position.
     *
     * @param column the zero-based column index
     * @param index the zero-based row index, which must be less than the row count
     * @return the double stored at that position
     * @throws IOException if the backend storage cannot be read
     */
    double get(int column, int index) throws IOException {
        assert index < rows : "Invalid index supplied: " + index + LENGTH + rows;
        return readDouble(columns * index + column);
    }

    /**
     * Reads a run of values from one column, one per consecutive row.
     *
     * @param column the zero-based column index
     * @param index the zero-based row index at which to start reading
     * @param count how many consecutive rows to read; index plus count may not exceed the row count
     * @return the count values read, in increasing row order
     * @throws IOException if the backend storage cannot be read
     */
    double[] get(int column, int index, int count) throws IOException {
        assert index + count <= rows
                : "Invalid index/count supplied: " + index + "/" + count + " (length=" + rows + ")";
        double[] values = new double[count];
        for (int i = columns * index + column, c = 0; c < count; i += columns, c++) {
            values[c] = readDouble(i);
        }
        return values;
    }

    /**
     * Return how many columns the matrix holds.
     * @return number of columns
     */
    public int getColumns() {
        return columns;
    }

    /**
     * Return how many rows the matrix holds.
     * @return number of rows
     */
    public int getRows() {
        return rows;
    }
}
