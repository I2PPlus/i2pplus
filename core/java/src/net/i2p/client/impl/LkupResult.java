package net.i2p.client.impl;

import net.i2p.client.LookupResult;
import net.i2p.data.Destination;

/** Return value of {@link net.i2p.client.I2PSession#lookupDest2}. */
public class LkupResult implements LookupResult {

    private final int _code;
    private final Destination _dest;
    private final int _nonce;

    /**
     * Lkup result.
     *
     * @param code the LookupResult result code: zero on success, nonzero on failure
     * @param dest the destination the lookup resolved to, null when it failed
     */
    LkupResult(int code, Destination dest) {
        this(code, dest, 0);
    }

    /**
     * Holds the deferred result code, carrying only the nonce of the pending reply.
     * @param nonce the nonce to carry on the deferred reply
     */
    LkupResult(int nonce) {
        this(RESULT_DEFERRED, null, nonce);
    }

    /**
     * Full constructor.
     *
     * @param code zero for success, nonzero for failure, or
     *             RESULT_DEFERRED for a reply still pending
     * @param dest destination found by the lookup, or null on failure or
     *             while deferred
     * @param nonce the nonce of the reply this result answers
     */
    LkupResult(int code, Destination dest, int nonce) {
        _code = code;
        _dest = dest;
        _nonce = nonce;
    }

    /**
     * Zero for success, nonzero for failure.
     * @return zero for success, nonzero for failure
     */
    @Override
    public int getResultCode() {
        return _code;
    }

    /**
     * Destination on success, null on failure.
     * @return Destination on success, null on failure
     */
    @Override
    public Destination getDestination() {
        return _dest;
    }

    /** For async calls only. Nonce will be non-zero. Callback called later with final result and same nonce. */
    @Override
    public int getNonce() {
        return _nonce;
    }
}
