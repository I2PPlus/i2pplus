package org.klomp.snark;

import net.i2p.data.ByteArray;

/**
 * Callback used to fetch data
 *
 * @since 0.8.2
 */
interface DataLoader {
    /**
     * This is the callback that PeerConnectionOut calls to get the data from disk
     *
     * @param piece index of the requested piece
     * @param begin byte offset within the piece where the request starts
     * @param length number of bytes requested
     * @return bytes or null for errors
     */
    public ByteArray loadData(int piece, int begin, int length);
}
