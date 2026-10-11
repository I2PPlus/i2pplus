package org.rrd4j.core;

import java.io.IOException;

/**
 * Class to represent archive values for a single datasource. Robin class is the heart of the
 * so-called "round robin database" concept. Basically, each Robin object is a fixed length array of
 * double values. Each double value represents consolidated, archived value for the specific
 * timestamp. When the underlying array of double values gets completely filled, new values will
 * replace the oldest ones.
 *
 * <p>Robin object does not hold values in memory - such object could be quite large. Instead of it,
 * Robin reads them from the backend I/O only when necessary.
 *
 * @author Sasa Markovic
 */
public interface Robin extends RrdUpdater<Robin> {

    /**
     * Fetches all archived values.
     *
     * @return Array of double archive values, starting from the oldest one.
     * @throws java.io.IOException Thrown in case of I/O specific error.
     */
    double[] getValues() throws IOException;

    /**
     * Updates archived values in bulk.
     *
     * @param newValues Array of double values to be stored in the archive
     * @throws java.io.IOException Thrown in case of I/O error
     * @throws java.lang.IllegalArgumentException Thrown if the length of the input array is
     *                                            different from the length of this archive
     */
    void setValues(double... newValues) throws IOException;

    /**
     * (Re)sets all values in this archive to the same value.
     *
     * @param newValue New value
     * @throws java.io.IOException Thrown in case of I/O error
     */
    void setValues(double newValue) throws IOException;

    /**
     * Returns the i-th value from the Robin archive.
     *
     * @param index Value index
     * @return Value stored in the i-th position (the oldest value has zero index)
     * @throws java.io.IOException Thrown in case of I/O specific error.
     */
    double getValue(int index) throws IOException;

    /**
     * Sets the i-th value in the Robin archive.
     *
     * @param index index in the archive (the oldest value has zero index)
     * @param value value to be stored
     * @throws java.io.IOException Thrown in case of I/O specific error.
     */
    void setValue(int index, double value) throws IOException;

    /**
     * Returns the Archive object to which this Robin object belongs.
     *
     * @return Parent Archive object
     */
    Archive getParent();

    /**
     * Returns the size of the underlying array of archived values.
     *
     * @return Number of stored values
     */
    int getSize();

    /**
     * <p>Copies object's internal state to another Robin object.
     */
    void copyStateTo(Robin other) throws IOException;

    /**
     * Filters values stored in this archive based on the given boundary. Archived values found to
     * be outside of <code>[minValue, maxValue]</code> interval (inclusive) will be silently
     * replaced with <code>NaN</code>.
     *
     * @param minValue inclusive lower bound; values below it become NaN
     * @param maxValue inclusive upper bound; values above it become NaN
     * @throws java.io.IOException Thrown in case of I/O error
     */
    void filterValues(double minValue, double maxValue) throws IOException;

    /**
     * Returns the underlying storage (backend) object which actually performs all I/O operations.
     *
     * @return I/O backend object
     */
    RrdBackend getRrdBackend();

    /**
     * Required to implement RrdUpdater interface. You should never call this method directly.
     *
     * @return Allocator object
     */
    RrdAllocator getRrdAllocator();

    /**
     * Writes a full row of values into storage.
     *
     * @param newValues an array of double.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    void update(double[] newValues) throws IOException;

    /**
     * Renders the current contents as text.
     *
     * @return a {@link java.lang.String} object.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    String dump() throws IOException;

    /**
     * Appends a single value at the current row pointer.
     *
     * @param newValue a double.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    void store(double newValue) throws IOException;

    /**
     * Appends one value repeatedly, for bulk updates.
     *
     * @param newValue a double.
     * @param bulkCount a int.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    void bulkStore(double newValue, int bulkCount) throws IOException;

    /**
     * Reads a run of values starting at a row index.
     *
     * @param index a int.
     * @param count a int.
     * @return an array of double.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    double[] getValues(int index, int count) throws IOException;
}
