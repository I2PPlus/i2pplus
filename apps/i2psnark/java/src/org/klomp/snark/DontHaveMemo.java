package org.klomp.snark;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the pieces a peer said it no longer has (BEP 54 dont_have), so the piece is not
 * re-requested from that peer even if it re-announces HAVE, until the remembered state expires.
 *
 * <p>The peer's HAVE bitfield is already cleared on dont_have; this additionally suppresses
 * requests once the peer re-sets the bit, avoiding a request/dont_have ping-pong with peers that
 * flap or purge pieces transiently.
 *
 * @since 0.9.71+
 */
class DontHaveMemo {

    /** How long a dont_have keeps a piece unrequestable from that peer. */
    private static final long DEFAULT_MEMO_TIME = 5 * 60 * 1000;

    private final long _memoTime;
    private final Map<Integer, Long> _dontHave = new HashMap<>(8);

    DontHaveMemo() {
        this(DEFAULT_MEMO_TIME);
    }

    /**
     * @param memoTime how long a dont_have suppresses requests, milliseconds
     */
    DontHaveMemo(long memoTime) {
        _memoTime = memoTime;
    }

    /**
     * Remember that the peer no longer has the piece.
     *
     * @param piece the piece index
     */
    synchronized void add(int piece) {
        _dontHave.put(Integer.valueOf(piece), Long.valueOf(System.currentTimeMillis()));
    }

    /**
     * Whether the peer recently said it does not have the piece. Expired entries are dropped.
     *
     * @param piece the piece index
     * @return true if within the retention window
     */
    synchronized boolean contains(int piece) {
        Long added = _dontHave.get(Integer.valueOf(piece));
        if (added == null) {
            return false;
        }
        if (System.currentTimeMillis() - added.longValue() > _memoTime) {
            _dontHave.remove(Integer.valueOf(piece));
            return false;
        }
        return true;
    }
}