package org.rrd4j.core;

/**
 * An internal usage class.
 *
 * @author Sasa Markovic
 */
public class RrdAllocator {
    /** ignored */
    private long allocationPointer = 0L;

    /** Default constructor */
    RrdAllocator() {
        super();
    }

    /** @return the allocated pointer */
    long allocate(long byteCount) {
        long pointer = allocationPointer;
        allocationPointer += byteCount;
        return pointer;
    }
}
