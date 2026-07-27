package net.i2p.client.impl;

import net.i2p.client.LookupResult;
import net.i2p.data.Destination;

/** Return value of I2PSession.lookupDest2(). */
public class LkupResult implements LookupResult {

    private final int _code;
    private final Destination _dest;
    private final int _nonce;

    /** Lkup result */
    LkupResult(int code, Destination dest) {
        this(code, dest, 0);
    }

    /** Deferred. @param nonce the nonce */
    LkupResult(int nonce) {
        this(RESULT_DEFERRED, null, nonce);
    }

    /** @param code result code @param dest destination @param nonce the nonce */
    LkupResult(int code, Destination dest, int nonce) {
        _code = code;
        _dest = dest;
        _nonce = nonce;
    }

    /**
     * @return zero for success, nonzero for failure
     */
    @Override
    public int getResultCode() {
        return _code;
    }

    /**
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
