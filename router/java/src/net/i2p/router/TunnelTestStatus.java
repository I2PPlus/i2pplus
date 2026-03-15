package net.i2p.router;

/**
 * Test status for tunnel testing display
 * @since 0.9.68+
 */
public enum TunnelTestStatus {
    UNTESTED,    // No test has been run yet
    TESTING,     // Test is currently in progress
    GOOD,        // Recent successful test
    FAILING,     // One or two consecutive failures
    FAILED       // Three consecutive failures, marked for removal
}
