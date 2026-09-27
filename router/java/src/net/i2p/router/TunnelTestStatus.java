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
    OVER_BUDGET;

    /**
     *  Whether this status marks the tunnel dead: three or more consecutive
     *  test failures, i.e. {@code MAX_CONSECUTIVE_TEST_FAILURES}.  A dead
     *  tunnel is not capacity, is not offered as an endpoint, and is not
     *  rehabilitated by a traffic stamp — only a real passing test or a
     *  rebuild returns it to {@link #GOOD}.
     *
     *  @return true only for {@link #FAILED}
     *  @since 0.9.71+
     */
    public boolean isDead() {return this == FAILED;}

    /**
     *  Whether this status marks a transient failure: one or two consecutive
     *  test failures.  A failing tunnel is degraded but may recover, so it is
     *  rehabilitated to {@link #GOOD} by either a passing test or a traffic
     *  stamp within {@code TRAFFIC_PROOF_MS}.
     *
     *  @return true only for {@link #FAILING}
     *  @since 0.9.71+
     */
    public boolean isFailing() {return this == FAILING;}

    /**
     *  Whether a tunnel in this status must be withheld from healthy capacity
     *  counts and from endpoint selection.
     *
     *  <p>Defined here rather than at each call site because the counting and
     *  selection paths previously disagreed: the scan gates excluded both
     *  {@link #FAILED} and {@link #FAILING} while the usable-count helpers
     *  excluded only {@link #FAILING}, so a pool holding nothing but dead
     *  tunnels still reported full capacity and suppressed its own
     *  pre-build.  Callers that need a narrower rule should ask
     *  {@link #isDead()} or {@link #isFailing()} directly.
     *
     * @return true for {@link #FAILED} and {@link #FAILING}
     * @since 0.9.71+
     */
    public boolean isUnusable() {return isDead() || isFailing();}
}
