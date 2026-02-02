package net.i2p.router;

/**
 * Test status for tunnel testing display
 * @since 0.9.68+
 */
public enum TunnelTestStatus {
    UNTESTED,    // No test has been run yet
    TESTING,     // Test is currently in progress
    GOOD,        // Recent successful test
    FAILING,     // One consecutive failure
    FAILED       // Two consecutive failures, marked for removal
}
