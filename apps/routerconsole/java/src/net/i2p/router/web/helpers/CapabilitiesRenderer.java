package net.i2p.router.web.helpers;

import java.util.HashMap;
import java.util.Map;
import net.i2p.router.RouterContext;
import net.i2p.router.web.Messages;

/**
 * Renders router capability strings as HTML links for the NetDb and Sybil
 * views, keeping their markup identical.
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
            buf.append(replacements.getOrDefault(c, String.valueOf(c)));
        }
        return buf.toString();
    }

    /**
     * Applies the router tier state to linkified caps: removes the bare tier
     * letter, marks the tier class, and appends the reachability suffix to
     * the capability links.
     *
     * @param caps the linkified capability string
     * @param tier the tier letter D, E, or G, or 0 for none
     * @param suffix the reachability letter R or U, or 0 for no suffix
     * @param suffixAll if true, suffix every capability link, otherwise only the tier links
     * @return the processed capability string
     * @since 0.9.70+
     */
    static String applyTierState(String caps, char tier, char suffix, boolean suffixAll) {
        if (tier == 0) { return caps; }
        String rv = caps.replace(String.valueOf(tier), "");
        rv = rv.replace("class=tier", "class=\"tier is" + tier + "\"");
        if (suffix != 0) {
            if (suffixAll) {
                rv = rv.replace("\"><span class", suffix + "" + tier + "\"><span class");            } else {
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
