package org.klomp.snark.dht;

/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.crypto.SHA1Hash;

import org.klomp.snark.I2PSnarkUtil;

/**
 * Represents a BitTorrent info hash used for DHT operations.
 *
 * <p>An info hash is a 20-byte SHA1 hash that uniquely identifies a torrent. In the DHT context,
 * info hashes are used as keys to locate peers and track torrent participation across the
 * distributed network.
 *
 * <p>Info hashes serve as the primary identifier for DHT operations:<br>
 * - get_peer requests use info hashes to find peers for specific torrents<br>
 * - announce_peer requests associate peers with specific info hashes<br>
 * - DHT routing uses info hashes to determine which nodes should store peer information
 *
 * <p>This class extends SHA1Hash to provide torrent-specific functionality and formatting for
 * debugging and logging purposes.
 *
 * @since 0.9.2
 * @author zzz
 */
class InfoHash extends SHA1Hash {

    /**
     * Creates a new info hash from the specified 20-byte data.
     *
     * @param data the 20-byte SHA1 hash data representing the torrent info hash
     */
    public InfoHash(byte[] data) {
        super(data);
    }

    /**
     * Returns a string representation of the info hash for debugging and logging.
     *
     * <p>Formats the info hash as hexadecimal within brackets for easy identification in log files
     * and debug output. This provides a more readable representation than the default SHA1Hash
     * toString() method.
     *
     * @return a formatted string containing the info hash in hexadecimal format
     */
    @Override
    public String toString() {
        if (_data == null) {
            return super.toString();
        } else {
            return "[InfoHash " + I2PSnarkUtil.toHex(_data) + "]";
        }
    }
}
