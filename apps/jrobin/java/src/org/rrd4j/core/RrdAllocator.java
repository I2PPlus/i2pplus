package org.rrd4j.core;

/**
 * An internal usage class.
 *
 */
public class RrdAllocator {
    /** Offset of the next free byte in the file. */
    private long allocationPointer = 0L;

    /**
     * Creates an allocator whose byte offset starts at zero.
     */
    RrdAllocator() {
        super();
    }

    /**
     * Next available byte offset in the allocation space.
     * @param byteCount how many bytes to reserve; must not be negative
     * @return the start offset of the reserved space
     */
    long allocate(long byteCount) {
        long pointer = allocationPointer;
        allocationPointer += byteCount;
        return pointer;
    }
}
