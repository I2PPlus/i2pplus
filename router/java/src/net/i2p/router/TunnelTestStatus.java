package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

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
