package net.i2p.router.web;

import java.util.LinkedList;

/**
 * The console CSRF nonces issued to one HTTP session, and the policy for how
 * long each stays good.
 *
 * <p>A nonce is single-use: {@link #validate} removes it, so a page must issue
 * one nonce per nonce-guarded control and never share a nonce between two of
 * them. The queue is bounded, and the bound is what decides whether a control
 * still works after the page has been sitting open. The console sidebar
 * re-renders every {@code routerconsole.summaryRefresh} seconds, 3 by default,
 * and each render issues a nonce for the restart form and, when they are shown,
 * the reseed and update forms — 20 to 60 nonces a minute. A queue
 * sized for a handful of form submits therefore evicts the nonce a page issued
 * at render time within a couple of minutes, and a control whose nonce has
 * left the queue does nothing at all: {@code LogsHelper.clearThrough()} and
 * {@code ConfigRestartBean.renderStatus()} both return without a word when the
 * nonce fails to validate. Reading a log page for longer than that was enough
 * to break its Clear logs link.
 *
 * <p>So the age bound is what a page's own controls are sized against, and the
 * size bound only caps memory: at the default refresh rate the size ceiling is
 * reached only after the age bound has already expired those same nonces, so a
 * control stays good for {@link #MAX_AGE_MS} and the sidebar churn cannot push
 * it out early.
 *
 * <p>Not thread-safe on its own. Callers serialize access by synchronizing on
 * the {@code HttpSession} the queue is stored in, as
 * {@link CSSHelper#getNonce(javax.servlet.http.HttpSession)} and
 * {@link CSSHelper#validateNonce(javax.servlet.http.HttpSession, String, boolean)}
 * do.
 *
 * @since 0.9.72+
 */
class ConsoleNonceQueue {

    /**
     * How long an issued nonce stays valid, in ms. Sized for a console page
     * that is left open while it is being read — the log pages are the ones a
     * user stares at — and it is the window every nonce-guarded control on a
     * rendered page has before it stops working.
     */
    static final long MAX_AGE_MS = 30 * 60 * 1000L;

    /**
     * Ceiling on how many issued nonces are remembered, so a session that
     * polls the sidebar for hours cannot grow the queue without bound. It is
     * deliberately above what the age window fills at the worst churn — the
     * sidebar re-renders every 3s and issues up to 3 nonces per render, 60 a
     * minute, 1800 across {@link #MAX_AGE_MS} — so the age bound is the one
     * that governs and a page's own nonce is never evicted to make room for
     * sidebar churn.
     */
    static final int MAX_SIZE = 2000;

    /** Issued nonces, oldest first. */
    private final LinkedList<String> _nonces;

    /** Issue time of each nonce in {@link #_nonces}, same order. */
    private final LinkedList<Long> _issuedMs;

    /**
     * An empty queue. The session attribute is created on the first nonce the
     * console issues to that session.
     */
    ConsoleNonceQueue() {
        _nonces = new LinkedList<>();
        _issuedMs = new LinkedList<>();
    }

    /**
     * Record a newly issued nonce so it validates until it is used, it ages
     * out, or the queue is forced down to {@link #MAX_SIZE}.
     *
     * @param nonce the nonce handed to a form or link
     * @param nowMs the current time in ms, passed in so the age policy is
     *              testable without sleeping
     */
    void add(String nonce, long nowMs) {
        prune(nowMs);
        _nonces.addLast(nonce);
        _issuedMs.addLast(nowMs);
        // Drop the oldest to fit the ceiling: the newest nonces are the ones
        // the forms in front of the user were just built from.
        while (_nonces.size() > MAX_SIZE) {
            _nonces.pollFirst();
            _issuedMs.pollFirst();
        }
    }

    /**
     * Whether a nonce is one this queue issued and that has not been used yet.
     *
     * @param nonce the nonce submitted by a form or link; null never validates
     * @param nowMs the current time in ms, passed in so the age policy is
     *              testable without sleeping
     * @param preserve true to leave the nonce in place, for a read-only early
     *                 check in a JSP that a later handler will validate again
     * @return true if the nonce is accepted
     */
    boolean validate(String nonce, long nowMs, boolean preserve) {
        prune(nowMs);
        if (nonce == null) {return false;}
        int i = _nonces.lastIndexOf(nonce);
        if (i < 0) {return false;}
        if (!preserve) {
            _nonces.remove(i);
            _issuedMs.remove(i);
        }
        return true;
    }

    /**
     * Drop the nonces that have outlived {@link #MAX_AGE_MS}, oldest first.
     *
     * @param nowMs the current time in ms
     * @return how many nonces were dropped
     */
    int prune(long nowMs) {
        int dropped = 0;
        Long oldest = _issuedMs.peekFirst();
        while (oldest != null && nowMs - oldest > MAX_AGE_MS) {
            _nonces.pollFirst();
            _issuedMs.pollFirst();
            dropped++;
            oldest = _issuedMs.peekFirst();
        }
        return dropped;
    }

    /**
     * How many nonces the queue is holding, for the debug log at the
     * validation site.
     *
     * @return the count, 0 if none
     */
    int size() {
        return _nonces.size();
    }
}
