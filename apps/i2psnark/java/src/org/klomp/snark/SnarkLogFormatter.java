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

import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SimpleDataStructure;

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
 * <p>
 * A label that names many identities puts them in a sub-list of its own -
 * {@link #destList} and {@link #nodeList} - eight to a line, the same shape the
 * router's peer lists use, so no list bounded only by the network can emit one
 * line a reader has to scroll sideways through.
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
     * The bracketed short form of a DHT node id - the same eight base64
     * characters {@code dht.NodeInfo} prints, so a node reads the same inline,
     * in a list and in a tally.
     *
     * @param nid the node id, null when it is not known
     * @return {@code [xxxxxxxx]}, or {@code none} when there is no node to name
     */
    public static String node(SimpleDataStructure nid) {
        if (nid == null) {return NONE;}
        String b64 = nid.toBase64();
        if (b64 == null || b64.length() < SHORT_CHARS) {return NONE;}
        return '[' + b64.substring(0, SHORT_CHARS) + ']';
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

    /**
     * The bracketed short form of a peer's I2P destination, the shape the console
     * log viewer links. {@link PeerID#toString()} is a four-character peer id and
     * a four-character public-key prefix, and {@link Peer#toString()} appends the
     * id again, so the {@code [" + peer + "]} form the call sites used matches
     * neither of the two bracketed shapes the viewer parses, and every one of them
     * rendered as dead text.
     *
     * @param peer the peer to name, null when it is not known
     * @return {@code [xxxxxxxx]}, or {@code none} when there is no destination
     *         hash to name
     * @since 0.9.73+
     */
    public static String peer(Peer peer) {
        return peer(peer != null ? peer.getPeerID() : null);
    }

    /**
     * The short form of a peer as described by {@link #peer(Peer)}. A BEP 19
     * webseed has no I2P destination - its {@link PeerID} carries a hash of the
     * URL - so it is named in prose with that hash's prefix instead of in
     * brackets, which would render a link to a lease set that does not exist.
     *
     * @param peerID the peer's id, null when it is not known
     * @return {@code [xxxxxxxx]} for an I2P peer, {@code webseed xxxxxxxx} for a
     *         webseed, or {@code none} when there is no id to name
     * @since 0.9.73+
     */
    public static String peer(PeerID peerID) {
        if (peerID == null) {return NONE;}
        byte[] destHash = peerID.getDestHash();
        if (destHash == null || destHash.length != Hash.HASH_LENGTH) {return NONE;}
        if (peerID.isWebSeed()) {
            String b32 = Base32.encode(destHash);
            return b32.length() >= SHORT_CHARS ? "webseed " + b32.substring(0, SHORT_CHARS)
                                               : "webseed";
        }
        return dest(new Hash(destHash));
    }

    /**
     * Identities shown on one sub-list line before wrapping. Eight short
     * identities plus the five-column indent stays inside the logging guide's
     * 80-90 column line.
     */
    static final int ENTRIES_PER_LINE = 8;

    /** Spaces before the first identity on a sub-list line, lining them up in one column. */
    static final int INDENT = 5;

    /**
     * A labelled sub-list of I2P destinations, led by its own {@code "\n* "}
     * label line and eight identities to a line, so a list bounded only by the
     * tracker or the routing table cannot emit one line of unbounded length.
     * The shape mirrors {@code router.util.PeerLogFormatter.peerList}, which the
     * console log reader already knows.
     *
     * @param dests the destinations to name, in the order to print them; null or
     *              empty is shown as the sentence form
     * @param label the label to show, e.g. "Peers"
     * @param count the number the label reports, shown in parentheses and used
     *              for the {@code "(+N more)"} tail
     * @return the label line and the indented destination lines, or the sentence
     *         form when there is nothing to name
     * @since 0.9.73+
     */
    public static String destList(Iterable<? extends Hash> dests, String label, int count) {
        return entries(dests, label, count, "destinations", true);
    }

    /**
     * A labelled sub-list of DHT node ids in the shape described by
     * {@link #destList}, each named by {@link #node(SimpleDataStructure)}.
     *
     * @param nodes the nodes to name, in the order to print them; null or empty
     *              is shown as the sentence form
     * @param label the label to show, e.g. "Now to try"
     * @param count the number the label reports, shown in parentheses and used
     *              for the {@code "(+N more)"} tail
     * @return the label line and the indented node lines, or the sentence form
     *         when there is nothing to name
     * @since 0.9.73+
     */
    public static String nodeList(Iterable<? extends SimpleDataStructure> nodes, String label,
                                   int count) {
        return entries(nodes, label, count, "nodes", false);
    }

    /**
     * The shared sub-list layout: a label line naming how many entries follow,
     * then the identities eight to a line at a five-column indent, then a tail
     * counting what is not shown.
     *
     * @param items the identities to name, in the order to print them; null or
     *              empty is shown as the sentence form
     * @param label the label to show
     * @param count the number the label reports
     * @param noun what is being counted, used for the sentence form so an empty
     *             list still says something
     * @param shortBase32 true to name each entry as a destination, false to name
     *                    it as a node id
     * @return the label line and the indented identity lines, or the sentence
     *         form when there is nothing to name
     */
    private static String entries(Iterable<? extends SimpleDataStructure> items, String label,
                                  int count, String noun, boolean shortBase32) {
        StringBuilder sb = new StringBuilder(64);
        int shown = 0;
        if (items != null) {
            for (SimpleDataStructure item : items) {
                if (item == null) {continue;}
                if (shown == 0) {
                    sb.append("\n* ").append(label).append(" (").append(count).append("):\n");
                }
                if (shown % ENTRIES_PER_LINE == 0) {
                    if (shown > 0) {sb.append('\n');}
                    for (int i = 0; i < INDENT; i++) {
                        sb.append(' ');
                    }
                } else {
                    sb.append(' ');
                }
                sb.append(shortBase32 ? dest(item) : node(item));
                shown++;
            }
        }
        // a label line with a colon and nothing under it says nothing
        if (shown == 0) {return "\n* " + label + ' ' + count + ' ' + noun;}
        int more = count - shown;
        if (more > 0) {
            sb.append('\n');
            for (int i = 0; i < INDENT; i++) {
                sb.append(' ');
            }
            sb.append("(+").append(more).append(" more)");
        }
        return sb.toString();
    }

    /**
     * The short form of any identity, used by {@link #destList} - a {@link Hash}
     * is a destination, anything else is named by its base64 prefix.
     *
     * @param item the identity to name, not null
     * @return the bracketed eight-character form
     */
    private static String dest(SimpleDataStructure item) {
        return item instanceof Hash ? dest((Hash) item) : node(item);
    }
}
