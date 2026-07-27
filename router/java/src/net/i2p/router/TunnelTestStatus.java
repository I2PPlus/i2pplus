package net.i2p.router;

/**
 * Test status for tunnel testing display
 *
 * @since 0.9.68+
 */
public enum TunnelTestStatus {
    /** No test has been run yet */
    UNTESTED,
    /** Test is currently in progress */
    TESTING,
    /** Recent successful test */
    GOOD,
    /** One or two consecutive failures */
    FAILING,
    /** Three consecutive failures, marked for removal */
    FAILED,
    /** Scheduled for early expiry due to slow tunnel */
    TOO_SLOW,
    /** Scheduled for early expiry due to pool over budget */
    OVER_BUDGET
}
