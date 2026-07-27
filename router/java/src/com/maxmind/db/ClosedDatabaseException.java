package com.maxmind.db;

import java.io.IOException;

/**
 * Signals that the underlying database has been closed.
 */
public class ClosedDatabaseException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a new ClosedDatabaseException.
     */
    ClosedDatabaseException() {
        super("The MaxMind DB has been closed.");
    }
}
