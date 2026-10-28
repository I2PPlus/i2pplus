package net.i2p.router.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.i2p.data.Hash;

/**
 * Renders the peer lists the peer selectors log, in one shape everywhere: a
 * label line ending in a colon, then the peers as an indented sub-list of at
 * most sixteen bracketed 6-character base64 hashes per line. The sub-list lines
 * carry no bullet of their own, so each group reads as one block under its
 * label, and every hash in the block lines up in the same column. A label with
 * no peers to name is rendered as a sentence stating the count, never as a
 * colon with nothing after it.
 * <p>
 * Shared by the tunnel peer selectors and the floodfill selector so the two
 * cannot drift apart, and kept free of router state so the layout can be
 * pinned by test.
 *
 * @since 0.9.72+
 */
public class PeerLogFormatter {

    /** Peer hashes shown on one sub-list line before wrapping. */
    static final int ENTRIES_PER_LINE = 16;

    /** Spaces before the first hash on a sub-list line, lining the hashes up in one column. */
    static final int INDENT = 5;

    /** Label used for the exclusion list, the one case every selector spells the same way. */
    private static final String EXCLUDING = "Excluding";

    /**
     * Target width of a reason-tally bullet line, the logging guide's 80-90
     * range. A pair that would push the line past this starts a new line.
     */
    private static final int MAX_TALLY_LINE = 90;

    /**
     * Display labels for the peer skip reasons, in sentence case, keyed by the
     * raw reason string recorded in the excluders. A reason with no label here
     * is shown as recorded, so an unlabelled reason stays greppable.
     */
    private static final Map<String, String> REASON_LABELS;
    static {
        Map<String, String> labels = new LinkedHashMap<>(12);
        labels.put("too-many-tunnels", "Too many tunnels");
        labels.put("recently-rejected", "Recently rejected");
        labels.put("unreachable", "Unreachable");
        labels.put("no-routerinfo", "No router info");
        labels.put("floodfill", "Floodfill");
        labels.put("U-cap", "Unreachable cap");
        labels.put("moderate-congestion", "Moderate congestion");
        labels.put("severe-congestion", "Severe congestion");
        labels.put("slow/capped", "Slow or capped");
        labels.put("no-signal", "No signal");
        REASON_LABELS = Collections.unmodifiableMap(labels);
    }

    /**
     * Not instantiated; every method is a pure formatting function.
     *
     * @since 0.9.72+
     */
    private PeerLogFormatter() {}

    /**
     * The display label for a raw skip reason, or the reason itself when no
     * label is registered for it.
     *
     * @param reason the raw reason string recorded by an excluder
     * @return the label to show, the reason itself if it has no label
     */
    public static String reasonLabel(String reason) {
        String label = REASON_LABELS.get(reason);
        return label != null ? label : reason;
    }

    /**
     * The exclusion sub-list, the one label every selector spells the same way.
     *
     * @param peers the excluded peers, null for none
     * @param count the number excluded
     * @return the label line and the indented hash lines, or the sentence form
     *         when there is nothing to name
     */
    public static String excluding(Iterable<Hash> peers, int count) {
        return peerList(peers, EXCLUDING, count);
    }

    /**
     * The form used when a label has no peers to name: a sentence stating the
     * count, with no colon, since nothing follows it. A label line ending in a
     * colon with an empty list under it says nothing, so every label goes
     * through here.
     *
     * @param label the label that has no peers, e.g. "Excluding"
     * @param count the number of peers, normally zero
     * @return "\n* Label N peers"
     */
    private static String noPeers(String label, int count) {
        return "\n* " + label + ' ' + count + " peers";
    }

    /**
     * A labelled peer sub-list, led by its own "\n* " label line.
     *
     * @param peers the peers to show; null is shown as no peers
     * @param label the label to show, e.g. "Excluding"
     * @param count the number of peers the label reports, shown in parentheses;
     *              pass the full size even when a cap truncates the list
     * @return the label line and the indented hash lines, or the sentence form
     *         when there is nothing to name
     */
    public static String peerList(Iterable<Hash> peers, String label, int count) {
        return peerList(peers, label, count, 0);
    }

    /**
     * A labelled peer sub-list whose entries are joined by " -&gt; ", for a list
     * whose order is the point - a tunnel's gateway-to-endpoint sequence, for
     * example. Sixteen entries per line, same indent as {@link #peerList}.
     *
     * @param peers the peers to show, in the order to print them; null is shown
     *              as no peers
     * @param label the label to show, e.g. "Tunnel"
     * @param count the number of peers the label reports, shown in parentheses
     * @return the label line and the indented hash lines, or the sentence form
     *         when there is nothing to name
     */
    public static String peerListOrdered(Iterable<Hash> peers, String label, int count) {
        if (peers == null) {return noPeers(label, count);}
        StringBuilder sb = new StringBuilder(64);
        boolean first = true;
        int shown = 0;
        for (Hash h : peers) {
            if (h == null) {continue;}
            if (shown % ENTRIES_PER_LINE == 0) {
                if (shown > 0) {sb.append('\n');}
                if (first) {
                    sb.append("\n* ").append(label).append(" (").append(count).append("):\n");
                    first = false;
                }
                indent(sb);
            } else {
                sb.append(" -> ");
            }
            sb.append('[').append(h.toBase64(), 0, 6).append(']');
            shown++;
        }
        return shown > 0 ? sb.toString() : noPeers(label, count);
    }

    /**
     * A labelled peer sub-list showing at most {@code maxShown} peers, with the
     * remainder reported as a count so a list bounded only by the netdb cannot
     * emit a line of unbounded length.
     *
     * @param peers the peers to show; null is shown as no peers
     * @param label the label to show
     * @param count the number of peers the label reports, shown in parentheses
     * @param maxShown the number of peers to name before the "more" tail; 0 or
     *                 less shows all of them
     * @return the label line and the indented hash lines, or the sentence form
     *         when there is nothing to name
     */
    public static String peerList(Iterable<Hash> peers, String label, int count, int maxShown) {
        if (peers == null) {return noPeers(label, count);}
        StringBuilder sb = new StringBuilder(64);
        int shown = 0;
        for (Hash h : peers) {
            if (h == null) {continue;}
            if (maxShown > 0 && shown >= maxShown) {break;}
            if (shown == 0) {
                sb.append("\n* ").append(label).append(" (").append(count).append("):\n");
            }
            if (shown % ENTRIES_PER_LINE == 0) {
                if (shown > 0) {sb.append('\n');}
                indent(sb);
            } else {
                sb.append(' ');
            }
            sb.append('[').append(h.toBase64(), 0, 6).append(']');
            shown++;
        }
        if (shown == 0) {return noPeers(label, count);}
        int more = count - shown;
        if (more > 0) {
            sb.append('\n');
            indent(sb);
            sb.append("(+").append(more).append(" more)");
        }
        return sb.toString();
    }

    /**
     * A flat tally of skip reasons, one {@code label: count} pair per reason,
     * led by its own "\n* " line. No peer hashes - see {@link #peerList} for
     * the grouped form that names them. Pairs share a bullet line until the
     * line would exceed {@link #MAX_TALLY_LINE}, then they wrap onto a new one.
     *
     * @param counts reason to number of peers excluded for it, in the order to
     *               show them; the keys are mapped through the display labels
     * @return "\n* Excluded: N" then the reason pairs on bullet lines, empty if
     *         counts is null or empty
     */
    public static String reasonsTally(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {return "";}
        int total = 0;
        for (Integer c : counts.values()) {
            if (c != null) {total += c.intValue();}
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append("\n* Excluded: ").append(total);
        // the length of the bullet line being built, counting the "* " the
        // console renders, so the wrap decision matches what a reader sees
        int len = 2;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Integer c = e.getValue();
            if (c == null) {continue;}
            String seg = reasonLabel(e.getKey()) + ": " + c.intValue();
            if (len + seg.length() > MAX_TALLY_LINE) {
                sb.append("\n* ");
                len = 2;
            } else if (len > 2) {
                sb.append(" * ");
                len += 3;
            } else {
                sb.append("\n* ");
                len = 2;
            }
            sb.append(seg);
            len += seg.length();
        }
        return sb.toString();
    }

    /**
     * Append the sub-list indent, the spaces that line every hash up in one
     * column under its label.
     *
     * @param sb the buffer to append to
     */
    private static void indent(StringBuilder sb) {
        for (int i = 0; i < INDENT; i++) {sb.append(' ');}
    }
}
