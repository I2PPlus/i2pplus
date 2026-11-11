package org.klomp.snark;

/*
 *  Copyright 2026 the original author or authors.
 *  This file is part of the I2P software suite <https://geti2p.net/>.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

import net.i2p.data.Base64;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

/**
 * Renders the identities the snark logs name, in one shape everywhere, so a
 * reader can tell at a glance what a line is talking about and the console log
 * viewer can link it.
 * <p>
 * An I2P destination is its eight-character short base32 hash inside brackets -
 * the one bracketed form the log viewer parses as a destination
 * ({@code apps/routerconsole/jsp/js/refreshLogs.js}), so a 44-character base64
 * blob or a 6-character base64 tag renders as dead text. A torrent info hash is
 * eight hex characters with no brackets: it is a BitTorrent identity the router
 * cannot look up, and a bracket pair around it is read as a broken destination
 * link. Keeping the label outside the value ("Torrent: d344dabd") also keeps the
 * messages that name a torrent short enough to stay on one line.
 * <p>
 * Kept free of router and snark state so the layout can be pinned by test, and
 * deliberately not applied to {@link PeerID#toString()} or
 * {@code dht.NodeInfo#toString()}, whose forms the peer table's client
 * fingerprinting and column slicing already depend on.
 *
 * @since 0.9.73+
 */
public class SnarkLogFormatter {

    /** Characters of an identity shown in a log message. */
    static final int SHORT_CHARS = 8;

    /** Shown in place of an identity that is not known. */
    static final String NONE = "none";

    /**
     * Not instantiated; every method is a pure formatting function.
     *
     * @since 0.9.73+
     */
    private SnarkLogFormatter() {}

    /**
     * The bracketed short form of an I2P destination, the shape the console log
     * viewer links.
     *
     * @param destHash the destination's hash, null when it is not known
     * @return {@code [xxxxxxxx]}, or {@code none} when there is no hash to name
     */
    public static String dest(Hash destHash) {
        if (destHash == null) {return NONE;}
        String shortForm = destHash.toShortBase32();
        return shortForm != null ? '[' + shortForm + ']' : NONE;
    }

    /**
     * The bracketed short form of an I2P destination, the shape the console log
     * viewer links.
     *
     * @param dest the destination, null when it is not known
     * @return {@code [xxxxxxxx]}, or {@code none} when there is no destination
     *         to name
     */
    public static String dest(Destination dest) {
        return dest(dest != null ? dest.getHash() : null);
    }

    /**
     * The short form of a torrent info hash, eight hex characters, unbracketed.
     * Two torrents whose info hashes share their first eight characters are
     * rare enough that the short form is the one readers grep for.
     *
     * @param infoHash the 20-byte info hash, null when it is not known
     * @return eight hex characters, or {@code none} when there is no hash to name
     */
    public static String infoHash(byte[] infoHash) {
        if (infoHash == null || infoHash.length < 4) {return NONE;}
        return I2PSnarkUtil.toHex(infoHash).substring(0, SHORT_CHARS);
    }

    /**
     * The short form of a torrent info hash held as the base64 key the
     * torrent-destination map is keyed by, so the same torrent is named the same
     * way in every log.
     *
     * @param key the base64-encoded info hash, null when it is not known
     * @return eight hex characters, or {@code none} when there is no key to name
     */
    public static String infoHashKey(String key) {
        if (key == null) {return NONE;}
        byte[] decoded = Base64.decode(key);
        if (decoded == null || decoded.length < 4) {return NONE;}
        return I2PSnarkUtil.toHex(decoded).substring(0, SHORT_CHARS);
    }
}
