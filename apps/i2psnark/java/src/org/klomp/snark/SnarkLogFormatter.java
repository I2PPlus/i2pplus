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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * A label that names many identities puts them in a list of its own -
 * {@link #destList} and {@link #nodeList} - one line, at most ten of them named and
 * the rest counted, so no list bounded only by the network can emit one line a
 * reader has to scroll sideways through.
 *
 * @since 0.9.72+
 */
public class SnarkLogFormatter {

    /** Characters of an identity shown in a log message. */
    static final int SHORT_CHARS = 8;

    /** Shown in place of an identity that is not known. */
    static final String NONE = "none";

    /**
     * Not instantiated; every method is a pure formatting function.
     *
     * @since 0.9.72+
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
     * @since 0.9.72+
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
     * @since 0.9.72+
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
     * The torrent a log line names, by the name the user knows it by. A magnet
     * lookup's name is {@code "Lookup [hex]"}, and the console log viewer parses
     * any bracketed eight-character token as a lease set, so every line about a
     * lookup linked to a destination that does not exist - the brackets come off
     * and the info hash prefix stays in the unbracketed form the rest of the logs
     * use for it. Any other name is shown as written.
     *
     * @param snark the torrent to name, null when it is not known
     * @return the name, or {@code none} when there is no torrent to name
     * @since 0.9.72+
     */
    public static String torrent(Snark snark) {
        if (snark == null) {return NONE;}
        String name = snark.getBaseName();
        if (name.startsWith(LOOKUP_PREFIX) && name.endsWith("]")) {
            return "Lookup " + name.substring(LOOKUP_PREFIX.length(), name.length() - 1);
        }
        return name;
    }

    /** The first half of a magnet lookup's synthetic torrent name. */
    private static final String LOOKUP_PREFIX = "Lookup [";

    /**
     * Identities a list shows before it stops and counts the rest. A DHT announce
     * walks its closest-node set every pass and prints the same nodes each time, so
     * a list bounded only by the routing table costs an unbounded line per pass for
     * identities the reader cannot act on. Ten is enough to see which part of the
     * key space the pass is working through, and the label and the
     * {@code "(+N more)"} tail still carry the true total.
     */
    static final int MAX_ENTRIES = 10;

    /**
     * A labelled list of I2P destinations on one line,
     * {@code "Peers (14): [a] [b] … (+4 more)"}, with at most
     * {@link #MAX_ENTRIES} named and the rest counted, so a list bounded only by
     * the tracker or the routing table cannot emit a line of unbounded length.
     *
     * @param dests the destinations to name, in the order to print them; null or
     *              empty is shown as the sentence form
     * @param label the label to show, e.g. "Peers"
     * @param count the number the label reports, shown in parentheses and used
     *              for the {@code "(+N more)"} tail
     * @return the labelled list as one segment, or the sentence form when there is
     *         nothing to name
     * @since 0.9.72+
     */
    public static String destList(Iterable<? extends Hash> dests, String label, int count) {
        return entries(dests, label, count, "destinations", true);
    }

    /**
     * A labelled list of DHT node ids in the shape described by
     * {@link #destList}, each named by {@link #node(SimpleDataStructure)}, capped
     * at {@link #MAX_ENTRIES} entries.
     *
     * @param nodes the nodes to name, in the order to print them; null or empty
     *              is shown as the sentence form
     * @param label the label to show, e.g. "Now to try"
     * @param count the number the label reports, shown in parentheses and used
     *              for the {@code "(+N more)"} tail
     * @return the labelled list as one segment, or the sentence form when there is
     *         nothing to name
     * @since 0.9.72+
     */
    public static String nodeList(Iterable<? extends SimpleDataStructure> nodes, String label,
                                   int count) {
        return entries(nodes, label, count, "nodes", false);
    }

    /**
     * The shared list layout: the label, up to {@link #MAX_ENTRIES} identities on
     * the same line, then the count of what is not shown,
     * {@code " * Tried (21): [a] [b] … (+11 more)"}. One line per list, because a
     * list that is capped does not need an indent to be readable, and the reader
     * scanning for a node finds it in the same place every time. The segment is
     * led by the {@code " * "} separator the house style uses between key-value
     * pairs.
     *
     * @param items the identities to name, in the order to print them; null or
     *              empty is shown as the sentence form
     * @param label the label to show
     * @param count the number the label reports
     * @param noun what is being counted, used for the sentence form so an empty
     *             list still says something
     * @param shortBase32 true to name each entry as a destination, false to name
     *                    it as a node id
     * @return the labelled list as one segment, or the sentence form when there is
     *         nothing to name
     */
    private static String entries(Iterable<? extends SimpleDataStructure> items, String label,
                                  int count, String noun, boolean shortBase32) {
        List<String> ids = new ArrayList<>(MAX_ENTRIES);
        int total = 0;
        if (items != null) {
            for (SimpleDataStructure item : items) {
                if (item == null) {continue;}
                total++;
                if (ids.size() < MAX_ENTRIES) {
                    ids.add(shortBase32 ? dest(item) : node(item));
                }
            }
        }
        // a label with a colon and nothing after it says nothing
        if (ids.isEmpty()) {return " * " + label + ' ' + count + ' ' + noun;}
        StringBuilder sb = new StringBuilder(24 + 11 * ids.size());
        sb.append(" * ").append(label).append(" (").append(count).append("):");
        for (String id : ids) {
            sb.append(' ').append(id);
        }
        // the caller's count is what the label reports; the iterable is what is
        // really there, and the tail has to account for every entry either of
        // them says is present
        int more = Math.max(total, count) - ids.size();
        if (more > 0) {
            sb.append(" (+").append(more).append(" more)");
        }
        return sb.toString();
    }

    /**
     * A list placed immediately after an arrow, where the house form puts the
     * first segment behind the arrow instead of behind a separator:
     * {@code "Finished get_peers -> Tried (1): [xxxxxxxx]"}, not
     * {@code "-> * Tried (1):"}. The separator becomes the one space the arrow
     * needs before its first word, so the caller ends its lead-in at
     * {@code "->"} and cannot double the space or drop it.
     *
     * @param segment the segment as {@link #destList} or {@link #nodeList} returns
     *                it, not null
     * @return the segment with its leading separator replaced by a space
     * @since 0.9.72+
     */
    public static String afterArrow(String segment) {
        if (segment.startsWith(" * ")) {
            return ' ' + segment.substring(3);
        }
        return segment;
    }

    /**
     * Pieces a request tally names before it stops and counts the rest. A peer's
     * request pipeline is normally a run of blocks of two or three pieces, so eight
     * names is already more than the state being reported usually holds, and the
     * {@code "(+N more)"} tail keeps the true total.
     */
    private static final int MAX_PIECES = 8;

    /**
     * Characters one line of a tally may use before it wraps onto a continuation
     * line, inside the logging guide's 100-column limit and leaving room for the
     * caller's lead-in.
     */
    private static final int LINE_LIMIT = 90;

    /**
     * What one peer is being asked for right now, as a total and then a per-piece
     * tally: {@code " 25 blocks across 3 pieces * Piece 27: 16 blocks * Piece 40: 9
     * blocks"}. The request list it replaces named every block offset, which ran to
     * 596 characters for a single peer and never named a piece a reader could act
     * on. Pieces come out in the order the map does, at most {@link #MAX_PIECES} of
     * them, and the tally wraps onto {@code "\n* "} continuation lines once a line
     * would pass {@link #LINE_LIMIT}.
     *
     * @param blocksByPiece blocks in flight per piece number, in the order to show
     *                      them, not null; an empty map reports no blocks
     * @return the tally with one space before its first word, for a caller whose
     *         lead-in ends at {@code "->"}
     * @since 0.9.72+
     */
    public static String requestTally(Map<Integer, Integer> blocksByPiece) {
        int pieces = blocksByPiece.size();
        int blocks = 0;
        for (Integer n : blocksByPiece.values()) {
            blocks += n.intValue();
        }
        StringBuilder sb = new StringBuilder(24 + 24 * Math.min(pieces, MAX_PIECES));
        sb.append(' ').append(blocks).append(' ').append(plural(blocks, "block"))
          .append(" across ").append(pieces).append(' ').append(plural(pieces, "piece"));
        int line = sb.length();
        int shown = 0;
        for (Map.Entry<Integer, Integer> e : blocksByPiece.entrySet()) {
            if (shown++ == MAX_PIECES) {
                sb.append("\n* (+").append(pieces - MAX_PIECES).append(" more)");
                break;
            }
            int n = e.getValue().intValue();
            int key = e.getKey().intValue();
            String seg = "Piece " + key + ": " + n + ' ' + plural(n, "block");
            if (line + 3 + seg.length() > LINE_LIMIT) {
                sb.append("\n* ");
                line = 2;
            } else {
                sb.append(" * ");
                line += 3;
            }
            sb.append(seg);
            line += seg.length();
        }
        return sb.toString();
    }

    /**
     * The countable word for a tally, pluralised.
     *
     * @param n the count that precedes the word
     * @param word the singular word
     * @return the word, with an {@code s} unless the count is one
     */
    private static String plural(int n, String word) {
        return n == 1 ? word : word + 's';
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
