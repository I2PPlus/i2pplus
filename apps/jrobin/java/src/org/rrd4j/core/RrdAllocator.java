package org.rrd4j.core;

/**
 * An internal usage class.
 *
 * @author Sasa Markovic
 */
public class RrdAllocator {
    private long allocationPointer = 0L;

    RrdAllocator() {
        super();
    }

    /** @return the next available byte offset in the allocation space */
    long allocate(long byteCount) {
        long pointer = allocationPointer;
        allocationPointer += byteCount;
        return pointer;
    }
}
