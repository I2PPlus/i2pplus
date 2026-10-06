package net.i2p.router.web.helpers;

import java.util.HashMap;
import java.util.Map;
import net.i2p.router.RouterContext;
import net.i2p.router.web.Messages;

/**
 * Renders router capability strings as HTML links, identically for the NetDb
 * and Sybil views.
 *
 * @since 0.9.70+
 */
class CapabilitiesRenderer {
    /** Link HTML for the capability letters used by both views */
    static final Map<Character, String> CAP_REPLACEMENTS;

    /** CAP_REPLACEMENTS plus the letters only the Sybil view links */
    static final Map<Character, String> SYBIL_REPLACEMENTS;

    /** Tier link letters, in the order they are suffixed */
    static final char[] TIER_LINK_LETTERS = { 'K', 'L', 'M', 'N', 'O', 'P', 'X' };

    static {
        CAP_REPLACEMENTS = new HashMap<>();
        CAP_REPLACEMENTS.put('f', "<a href=\"/netdb?caps=f\"><span class=ff>F</span></a>");
        CAP_REPLACEMENTS.put('R', "<a href=\"/netdb?caps=R\"><span class=reachable>R</span></a>");
        CAP_REPLACEMENTS.put('U', "<a href=\"/netdb?caps=U\"><span class=unreachable>U</span></a>");
        CAP_REPLACEMENTS.put('K', "<a href=\"/netdb?caps=K\"><span class=tier>K</span></a>");
        CAP_REPLACEMENTS.put('L', "<a href=\"/netdb?caps=L\"><span class=tier>L</span></a>");
        CAP_REPLACEMENTS.put('M', "<a href=\"/netdb?caps=M\"><span class=tier>M</span></a>");
        CAP_REPLACEMENTS.put('N', "<a href=\"/netdb?caps=N\"><span class=tier>N</span></a>");
        CAP_REPLACEMENTS.put('O', "<a href=\"/netdb?caps=O\"><span class=tier>O</span></a>");
        CAP_REPLACEMENTS.put('P', "<a href=\"/netdb?caps=P\"><span class=tier>P</span></a>");
        CAP_REPLACEMENTS.put('X', "<a href=\"/netdb?caps=X\"><span class=tier>X</span></a>");
        SYBIL_REPLACEMENTS = new HashMap<>(CAP_REPLACEMENTS);
        SYBIL_REPLACEMENTS.put('B', "<a href=\"/netdb?caps=B\"><span class=testing>B</span></a>"); // not shown?
        SYBIL_REPLACEMENTS.put('C', "<a href=\"/netdb?caps=C\"><span class=ssuintro>C</span></a>"); // not shown?
        SYBIL_REPLACEMENTS.put('H', "<a href=\"/netdb?caps=H\"><span class=hidden>H</span></a>"); // not shown?
    }

    /**
     * Replaces each capability letter with its link HTML, leaving unknown
     * letters as-is.
     *
     * @param caps the raw capability string
     * @param replacements the letter to link HTML map
     * @return the linkified capability string
     * @since 0.9.70+
     */
    static String linkify(String caps, Map<Character, String> replacements) {
        StringBuilder buf = new StringBuilder(caps.length() * 2);
        for (int i = 0; i < caps.length(); i++) {
            char c = caps.charAt(i);
            String link = replacements.get(c);
            // String.valueOf(c) would box and copy every unlinked letter
            if (link != null) {buf.append(link);} else {buf.append(c);}
        }
        return buf.toString();
    }

    /** Tier state letters {@link #applyTierState} is built for, indexed by tier. */
    private static final char[] TIER_LETTERS = { 'D', 'E', 'G' };
    /** Reachability suffix letters, indexed by suffix (0 is "no suffix"). */
    private static final char[] SUFFIX_LETTERS = { '\0', 'R', 'U' };
    /** {@code class=tier} rewritten to {@code class="tier is<letter>"}, indexed by tier. */
    private static final String[] TIER_CLASS_REPLACEMENTS;
    /** Link openers rewritten to carry {@code <suffix><tier>}, indexed by suffix then tier. */
    private static final String[][] SUFFIXED_OPENERS;
    /**
     * Tier link openers rewritten to carry {@code <suffix><tier>}, indexed by
     * suffix, then tier, then as interleaved {needle, replacement} pairs over
     * {@link #TIER_LINK_LETTERS}.
     */
    private static final String[][][] SUFFIXED_TIER_OPENERS;

    static {
        TIER_CLASS_REPLACEMENTS = new String[TIER_LETTERS.length];
        for (int t = 0; t < TIER_LETTERS.length; t++) {
            TIER_CLASS_REPLACEMENTS[t] = "class=\"tier is" + TIER_LETTERS[t] + "\"";
        }
        SUFFIXED_OPENERS = new String[SUFFIX_LETTERS.length][];
        SUFFIXED_TIER_OPENERS = new String[SUFFIX_LETTERS.length][][];
        for (int s = 1; s < SUFFIX_LETTERS.length; s++) {
            SUFFIXED_OPENERS[s] = new String[TIER_LETTERS.length];
            SUFFIXED_TIER_OPENERS[s] = new String[TIER_LETTERS.length][];
            for (int t = 0; t < TIER_LETTERS.length; t++) {
                String carry = "" + SUFFIX_LETTERS[s] + TIER_LETTERS[t];
                SUFFIXED_OPENERS[s][t] = carry + "\"><span class";
                String[] pairs = new String[TIER_LINK_LETTERS.length * 2];
                for (int i = 0; i < TIER_LINK_LETTERS.length; i++) {
                    String opener = "href=\"/netdb?caps=" + TIER_LINK_LETTERS[i];
                    pairs[i * 2] = opener;
                    pairs[i * 2 + 1] = opener + carry;
                }
                SUFFIXED_TIER_OPENERS[s][t] = pairs;
            }
        }
    }

    /**
     * Index of a tier letter in {@link #TIER_LETTERS}.
     *
     * @param tier the tier letter, or 0 for none
     * @return the index, or -1 for an unrecognized letter
     * @since 0.9.72+
     */
    private static int tierIndex(char tier) {
        for (int i = 0; i < TIER_LETTERS.length; i++) {
            if (TIER_LETTERS[i] == tier) {return i;}
        }
        return -1;
    }

    /**
     * Index of a reachability suffix in {@link #SUFFIX_LETTERS}.
     *
     * @param suffix the suffix letter, or 0 for none
     * @return the index, or -1 for an unrecognized letter
     * @since 0.9.72+
     */
    private static int suffixIndex(char suffix) {
        for (int i = 1; i < SUFFIX_LETTERS.length; i++) {
            if (SUFFIX_LETTERS[i] == suffix) {return i;}
        }
        return -1;
    }

/**
 * Applies the router tier state to linkified caps: removes the bare tier
 * letter, marks the tier class, and appends the reachability suffix to
 * the capability links. Every replacement string is pre-built in
 * {@link #TIER_CLASS_REPLACEMENTS}, {@link #SUFFIXED_OPENERS} and
 * {@link #SUFFIXED_TIER_OPENERS}, keyed by the tier and suffix letters.
 *
 * @param caps the linkified capability string
 * @param tier the tier letter D, E, or G, or 0 for none
 * @param suffix the reachability letter R or U, or 0 for no suffix
 * @param suffixAll if true, suffix every capability link, otherwise only the tier links
 * @return the processed capability string
 * @since 0.9.70+
 */
    static String applyTierState(String caps, char tier, char suffix, boolean suffixAll) {
        if (tier == 0) {return caps;}
        int t = tierIndex(tier);
        int s = suffixIndex(suffix);
        if (t < 0 || s < 0) {return applyTierStateUncached(caps, tier, suffix, suffixAll);}
        String rv = caps.replace(String.valueOf(tier), "");
        rv = rv.replace("class=tier", TIER_CLASS_REPLACEMENTS[t]);
        if (s > 0) {
            if (suffixAll) {
                rv = rv.replace("\"><span class", SUFFIXED_OPENERS[s][t]);
            } else {
                String[] pairs = SUFFIXED_TIER_OPENERS[s][t];
                for (int i = 0; i < pairs.length; i += 2) {
                    rv = rv.replace(pairs[i], pairs[i + 1]);
                }
            }
        }
        return rv;
    }

/**
 *  Tier-state rewrite for letters outside the pre-built tables. Not reachable
 *  from the console, which only passes D, E or G with an optional R or U; kept
 *  so an unexpected letter degrades to the original behaviour rather than
 *  being dropped.
 *
 *  @param caps the linkified capability string
 *  @param tier the tier letter
 *  @param suffix the reachability letter, or 0 for none
 *  @param suffixAll if true, suffix every capability link
 *  @return the processed capability string
 *  @since 0.9.72+
 */
    private static String applyTierStateUncached(String caps, char tier, char suffix, boolean suffixAll) {
        String rv = caps.replace(String.valueOf(tier), "");
        rv = rv.replace("class=tier", "class=\"tier is" + tier + "\"");
        if (suffix != 0) {
            if (suffixAll) {
                rv = rv.replace("\"><span class", suffix + "" + tier + "\"><span class");
            } else {
                for (char c : TIER_LINK_LETTERS) {
                    rv = rv.replace("href=\"/netdb?caps=" + c, "href=\"/netdb?caps=" + c + suffix + tier);
                }
            }
        }
        return rv;
    }

    /**
     * Tooltip suffix shared by all capability links.
     *
     * @param ctx the router context for translation
     * @return the tooltip suffix
     * @since 0.9.70+
     */
    static String capTooltip(RouterContext ctx) {
        return "\" title=\"" + Messages.getString("Show all routers with this capability in the NetDb", ctx) + "\"><span";
    }
}
