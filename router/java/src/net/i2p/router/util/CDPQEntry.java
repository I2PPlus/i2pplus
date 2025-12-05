package net.i2p.router.util;

/**
 * Interface for CoDel priority queue entries with extended tracking.
 * <p>
 * Extends CDQEntry to add priority-specific functionality
 * for priority-based CoDel queue implementations.
 * Provides methods for accessing priority levels and
 * sequence numbers required for priority-aware queue operations.
 * <p>
 * Used by CoDelPriorityBlockingQueue to implement
 * priority-based packet dropping with per-priority
 * statistics tracking and enhanced delay management.
 *
 * @since 0.9.3
 */
public interface CDPQEntry extends CDQEntry, PQEntry {

}
