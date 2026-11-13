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
import java.util.Collections;
import java.util.HashMap;
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
 * {@link #destList} and {@link #nodeList} - at most {@link #MAX_ENTRIES} of them
 * named and the rest counted, so no list bounded only by the network can emit one
 * line a reader has to scroll sideways through.
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

    /**
     * The torrent a log line names, from the metadata the torrent carries, cut to
     * the name budget. A torrent's own name is user data of no fixed length - a
     * scene release name runs past 60 characters and pushes every line naming it
     * over the width limit - so the name is capped the way every other value is.
     *
     * @param metainfo the torrent's metadata, null before the metadata arrives
     * @return the capped name, or {@code none} when there is no metadata to name it
     * @since 0.9.72+
     */
    public static String torrent(MetaInfo metainfo) {
        return metainfo == null ? NONE : name(metainfo.getName());
    }

    /**
     * The torrent a peer's line names, falling back to the info hash while a
     * magnet fetch has not delivered the metadata yet. A peer runs before the
     * metadata arrives, so the one-argument form would key most of its lines by
     * the bare word "none" and leave the reader no way to say which torrent the
     * peer belongs to.
     *
     * @param metainfo the torrent's metadata, null before the metadata arrives
     * @param infohash the 20-byte info hash to fall back to, null when unknown
     * @return the capped name, or the eight-character info hash when there is no
     *         metadata to name it by
     * @since 0.9.72+
     */
    public static String torrent(MetaInfo metainfo, byte[] infohash) {
        return metainfo == null ? infoHash(infohash) : name(metainfo.getName());
    }

    /** The first half of a magnet lookup's synthetic torrent name. */
    private static final String LOOKUP_PREFIX = "Lookup [";

    /**
     * Identities a list shows before it stops and counts the rest. The cap is set
     * by the line budget, not by taste: a caller's lead-in and label take about 40
     * characters, each identity takes 11 ({@code " [xxxxxxxx]"}), and the
     * {@code " (+N more)"} tail takes about 12, so four identities is what fits
     * inside the logging guide's 100 columns. A DHT announce walks its closest-node
     * set every pass and prints the same nodes each time, so a list bounded only by
     * the routing table would cost an over-long line per pass for identities the
     * reader cannot act on; four names still show which part of the key space the
     * pass is working through, and the label and the tail still carry the true
     * total.
     */
    static final int MAX_ENTRIES = 4;

    /**
     * A labelled list of I2P destinations on one line,
     * {@code " * Peers (14): [a] [b] … (+4 more)"}, or {@code " * Peers: 0"} when
     * the list is empty, with at most
     * {@link #MAX_ENTRIES} named and the rest counted, so a list bounded only by
     * the tracker or the routing table cannot emit a line of unbounded length.
     *
     * @param dests the destinations to name, in the order to print them; null or
     *              empty is shown as a zero count
     * @param label the label to show, e.g. "Peers"
     * @param count the number the label reports, shown in parentheses and used
     *              for the {@code "(+N more)"} tail
     * @return the labelled list as one segment, or a zero count when there is
     *         nothing to name
     * @since 0.9.72+
     */
    public static String destList(Iterable<? extends Hash> dests, String label, int count) {
        return entries(dests, label, count, true);
    }

    /**
     * A labelled list of DHT node ids in the shape described by
     * {@link #destList}, each named by {@link #node(SimpleDataStructure)}, capped
     * at {@link #MAX_ENTRIES} entries.
     *
     * @param nodes the nodes to name, in the order to print them; null or empty
     *              is shown as a zero count
     * @param label the label to show, e.g. "Now to try"
     * @param count the number the label reports, shown in parentheses and used
     *              for the {@code "(+N more)"} tail
     * @return the labelled list as one segment, or a zero count when there is
     *         nothing to name
     * @since 0.9.72+
     */
    public static String nodeList(Iterable<? extends SimpleDataStructure> nodes, String label,
                                   int count) {
        return entries(nodes, label, count, false);
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
     *              empty is shown as a zero count
     * @param label the label to show
     * @param count the number the label reports
     * @param shortBase32 true to name each entry as a destination, false to name
     *                    it as a node id
     * @return the labelled list as one segment, or a zero count when there is
     *         nothing to name
     */
    private static String entries(Iterable<? extends SimpleDataStructure> items, String label,
                                  int count, boolean shortBase32) {
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
        // an empty list is a count, not a label with nothing after it
        if (ids.isEmpty()) {return " * " + label + ": 0";}
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
     * A list moved onto a continuation line of its own, for a message that reports
     * more than one of them: only one list can ride the arrow, and two lists side by
     * side on one line run past the logging guide's width even when each is capped,
     * because the lead-in and both labels are charged to the same line. The first
     * list keeps the arrow ({@link #afterArrow}), the rest get this.
     *
     * @param segment the segment as {@link #destList} or {@link #nodeList} returns
     *                it, not null
     * @return the segment on a {@code "\n* "} continuation line
     * @since 0.9.72+
     */
    public static String onOwnLine(String segment) {
        if (segment.startsWith(" * ")) {
            return "\n* " + segment.substring(3);
        }
        return segment;
    }

    /**
     * Why an operation failed, for a line that does not pass the throwable to the
     * logger. A dropped peer, a refused connection or a closed socket is what the
     * torrent does every day, and its stack trace is four lines of router plumbing
     * that says nothing beyond the exception's own message, so the message goes on
     * the line instead. An exception with no message is itself the diagnosis, so
     * its class name is used.
     *
     * @param t what went wrong, null means the failure was not reported
     * @return the {@code " * Error: x"} segment, never empty
     * @since 0.9.72+
     */
    public static String error(Throwable t) {
        if (t == null) {
            return " * Error: Not reported";
        }
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            return " * Error: " + t.getClass().getName();
        }
        return " * Error: " + msg;
    }

    /** The suffix every I2P host name carries. */
    private static final String I2P_TLD = ".i2p";

    /** The suffix marking a host written out as a base32 address. */
    private static final String B32_SUFFIX = ".b32";

    /**
     * Characters of a free-text name a log message shows. Torrent names are user
     * data of no fixed length - a scene release name runs past 80 characters - and a
     * name is the one value on a piece or torrent line that cannot be shortened by
     * rewording, so it gets a budget like any other value.
     */
    static final int NAME_CHARS = 24;

    /**
     * A torrent name shortened to {@link #NAME_CHARS} characters for a log message.
     * The three dots mark where the name was cut, which is what makes them different
     * from the ellipsis the logging guide bans: that one stands in for a fact the
     * message never states, these state that the fact is longer than the line can
     * carry. A reader who needs the whole name has the console torrent page, and the
     * info hash on the neighbouring lines identifies the torrent exactly.
     *
     * @param name the name to show, null when it is not known
     * @return the name, cut to {@link #NAME_CHARS} characters with a trailing
     *         {@code "..."} when it is longer, or {@code none} when there is no name
     * @since 0.9.72+
     */
    public static String name(String name) {
        if (name == null) {return NONE;}
        if (name.length() <= NAME_CHARS) {return name;}
        return name.substring(0, NAME_CHARS - 3) + "...";
    }

    /** Characters of the shortest base32 address, used to tell one from a nickname. */
    private static final int MIN_B32_CHARS = 52;

    /**
     * Known public trackers, address to nickname - the one place in snark that
     * maps a tracker's base32 or base64 address to the name its operators use.
     * A tracker's announce URL normally carries the raw address, and a reader
     * who sees {@code w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p}
     * in a log line cannot tell which tracker failed, or whether they configured
     * it themselves. The nickname is what they match against the console's
     * tracker list, so it goes in the log instead.
     *
     * <p>Keys are the host exactly as the announce URL spells it, suffix
     * included. The base64 entry is a full {@code .i2p} address, not a
     * {@code .b32.i2p} one, and it is 516 characters long, so it is keyed
     * alongside the rest rather than handled by a second rule.
     */
    private static final Map<String, String> TRACKER_NAMES;

    static {
        Map<String, String> names = new HashMap<>(16);
        names.put("ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p",
                  "tracker2.postman.i2p");
        names.put("lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBg"
                  + "wlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx"
                  + "8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV"
                  + "18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4"
                  + "k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICkEbKUqJ9mPYQlT"
                  + "SujhNxiRIW-oLwMtvayCFci99oX8MvazPS7~97x0Gsm-onEK1Td9nBdmq30OqDxpRtXBimb"
                  + "zkLbR1IKObbg9HvrKs3L-kSyGwTUmHG9rSQSoZEvFMA-S0EXO~o4g21q1oikmxPMhkeVwQ2"
                  + "2VHB0-LZJfmLr4SAAAA.i2p",
                  "tracker2.postman.i2p");
        names.put("w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p",
                  "opentracker.dg2.i2p");
        names.put("afuuortfaqejkesne272krqvmafn65mhls6nvcwv3t7l2ic2p4kq.b32.i2p",
                  "lyoko.i2p");
        names.put("s5ikrdyjwbcgxmqetxb3nyheizftms7euacuub2hic7defkh3xhq.b32.i2p",
                  "tracker.thebland.i2p");
        names.put("nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq.b32.i2p",
                  "torrfreedom.i2p");
        names.put("by7luzwhx733fhc5ug2o75dcaunblq2ztlshzd7qvptaoa73nqua.b32.i2p",
                  "opentracker.skank.i2p");
        names.put("punzipidirfqspstvzpj6gb4tkuykqp6quurj6e23bgxcxhdoe7q.b32.i2p",
                  "opentracker.r4sas.i2p");
        names.put("qimlze77z7w32lx2ntnwkuqslrzlsqy7774v3urueuarafyqik5a.b32.i2p",
                  "sigmatracker.i2p");
        names.put("4cxcka62bhpnl6raidcx7mgwkybaw3svckgbkprktd4zogc7npca.b32.i2p",
                  "tracker.insulaocculta.i2p");
        TRACKER_NAMES = Collections.unmodifiableMap(names);
    }

    /**
     * The nickname a tracker's address stands for, or the address back unchanged
     * when the table does not know it. Used by {@link #host(String)} for log
     * lines and by {@link I2PSnarkUtil#trackerB32ToHostname(String)} for the
     * console's display of a tracker's URL, so both name a tracker the same way.
     *
     * @param host the tracker host as the announce URL spells it, not null
     * @return the nickname when the table knows the address, the host otherwise
     * @since 0.9.72+
     */
    public static String trackerName(String host) {
        return TRACKER_NAMES.getOrDefault(host, host);
    }

    /**
     * A tracker host as a log value. A private tracker's host is normally its full
     * base32 address, some 60 characters no reader compares by eye, and it pushes
     * every line naming it past the logging guide's width; that form becomes the same
     * eight-character bracketed identity the rest of the snark logs use, so the
     * console log viewer links it. A named tracker keeps its name, which is shorter
     * than the hash and is what a reader acts on - so a known address is translated
     * to its nickname by {@link #trackerName(String)} first, and only an unknown one
     * is shortened to the hash.
     *
     * @param host the tracker's host name, null when it is not known
     * @return the nickname when the table knows it, otherwise {@code [xxxxxxxx]}
     *         for a base32 address, the name unchanged otherwise, or {@code none}
     *         when there is no host to name
     * @since 0.9.72+
     */
    public static String host(String host) {
        if (host == null) {return NONE;}
        String named = trackerName(host);
        if (!named.equals(host)) {return named;}
        if (!host.endsWith(I2P_TLD)) {return host;}
        String head = host.substring(0, host.length() - I2P_TLD.length());
        if (head.endsWith(B32_SUFFIX)) {
            head = head.substring(0, head.length() - B32_SUFFIX.length());
        }
        return isBase32(head) ? '[' + head.substring(0, SHORT_CHARS) + ']' : host;
    }

    /**
     * Whether a string is I2P base32 - lower case letters and the digits 2 through 7 -
     * and long enough to be an address rather than a nickname that happens to end in
     * {@code .i2p}.
     *
     * @param s the text to test, not null
     * @return true if s is at least {@link #MIN_B32_CHARS} base32 characters
     */
    private static boolean isBase32(String s) {
        if (s.length() < MIN_B32_CHARS) {return false;}
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '2' && c <= '7'))) {return false;}
        }
        return true;
    }

    /**
     * Pieces a request tally names before it stops and counts the rest. A peer's
     * request pipeline is normally a run of blocks of two or three pieces, so the
     * pipeline fits without truncation in the common case, and the {@code "(+N
     * more)"} tail keeps the total when it does not. Two is the cap because the
     * tally shares its line with the caller's lead-in - three named pieces plus a
     * tail, at the widest piece and block counts, runs past 100 columns.
     */
    private static final int MAX_PIECES = 2;

    /**
     * What one peer is being asked for right now, as one line of per-piece counts:
     * {@code " Piece 27: 16 blocks * Piece 40: 9 blocks"}. The request list it
     * replaces named every block offset, which ran to 596 characters for a single
     * peer and never named a piece a reader could act on. Pieces come out in the
     * order the map does, at most {@link #MAX_PIECES} of them, and the rest are
     * counted in a {@code "(+N more)"} tail.
     *
     * <p>There is no totals lead, and the tally never wraps, whatever the map
     * holds: the lead repeated what the segments add up to, and a lead plus three
     * named pieces at the widest piece and block counts ran past 100 columns once
     * the caller's lead-in was counted - which is what the wrapping form did, with
     * 1,953 of 3,512 tally lines in one day's log running past the limit.
     *
     * @param blocksByPiece blocks in flight per piece number, in the order to show
     *                      them, not null; an empty map reports no blocks
     * @return the tally with one space before its first word, for a caller whose
     *         lead-in ends at {@code "->"}
     * @since 0.9.72+
     */
    public static String requestTally(Map<Integer, Integer> blocksByPiece) {
        int pieces = Math.min(blocksByPiece.size(), MAX_PIECES);
        StringBuilder sb = new StringBuilder(24 + 24 * pieces);
        int shown = 0;
        for (Map.Entry<Integer, Integer> e : blocksByPiece.entrySet()) {
            if (shown == MAX_PIECES) {
                sb.append(" (+").append(blocksByPiece.size() - MAX_PIECES).append(" more)");
                break;
            }
            int n = e.getValue().intValue();
            sb.append(shown++ == 0 ? " Piece " : " * Piece ").append(e.getKey().intValue())
              .append(": ").append(n).append(' ').append(plural(n, "block"));
        }
        return sb.length() == 0 ? " no blocks" : sb.toString();
    }

    /**
     * A partial piece as one value: which piece it is and how much of it is in.
     * The form the object prints, {@code "Partial(1220,28672,65536,262144)"}, is a
     * comma tuple that names none of its four numbers, so a line about a stalled
     * piece could not be read.
     *
     * @param pp the piece being fetched, or null
     * @return {@code "Piece 1220 (65536 of 262144 bytes in)"}, or {@code "none"}
     * @since 0.9.72+
     */
    public static String partial(PartialPiece pp) {
        if (pp == null) {return NONE;}
        return "Piece " + pp.getPiece() + " (" + pp.getDownloaded() + " of " + pp.getLength()
               + " bytes in)";
    }

    /**
     * A list of partial pieces as their piece numbers, the only part of a partial
     * a reader acts on: {@code " * Our partials (3): 305 50 1111 (+1 more)"}. At
     * most {@link #MAX_ENTRIES} numbers, the rest counted, because the list a
     * downloading torrent holds is bounded by nothing but the piece count and the
     * {@code List.toString()} form printed every partial's full comma tuple.
     *
     * @param partials the partial pieces to name, in the order to print them, not
     *                 null; an empty list reports no partials
     * @param label the label to show, e.g. {@code "Our partials"}
     * @return the labelled list as one segment, or a zero count when there is
     *         nothing to name
     * @since 0.9.72+
     */
    public static String partialList(Iterable<? extends PartialPiece> partials,
                                     String label) {
        List<String> nums = new ArrayList<>(MAX_ENTRIES);
        int total = 0;
        if (partials != null) {
            for (PartialPiece pp : partials) {
                if (pp == null) {continue;}
                total++;
                if (nums.size() < MAX_ENTRIES) {
                    nums.add(Integer.toString(pp.getPiece()));
                }
            }
        }
        if (total == 0) {
            return " * " + label + ": 0";
        }
        StringBuilder sb = new StringBuilder(12 + 8 * nums.size());
        sb.append(" * ").append(label).append(" (").append(total).append("):");
        for (String n : nums) {
            sb.append(' ').append(n);
        }
        if (total > nums.size()) {
            sb.append(" (+").append(total - nums.size()).append(" more)");
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
