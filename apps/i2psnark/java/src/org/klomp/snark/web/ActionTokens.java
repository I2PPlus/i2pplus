package org.klomp.snark.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mints and resolves short, unique action tokens for per-torrent form controls.
 *
 * A token is the shortest prefix of a torrent's base64 info-hash name that no
 * other currently-loaded torrent shares, floored at 4 chars so tokens stay
 * readable and the stale-token match surface stays small. Tokens are recomputed
 * from the live torrent set on every render (stateless), so uniqueness within a
 * render is guaranteed by construction; POST resolution requires exactly one
 * matching torrent, which makes stale or ambiguous tokens degrade to a safe
 * no-op rather than a misdirected action.
 *
 * Replaces 44-char full-hash parameter names, shrinking row markup by ~40 bytes
 * per control.
 *
 * @since 0.9.71+
 */
public final class ActionTokens {

    /** b64 names of 32-byte hashes are 44 chars incl. one '=' pad; tokens stop before it */
    private static final int MAX_TOKEN_LEN = 43;

    /** Floor for readability/stability: tokens shorter than this are too collision-prone */
    private static final int MIN_TOKEN_LEN = 4;

    private ActionTokens() {}

    /**
     * Mints minimal unique prefix tokens for the given names.
     *
     * Names are sorted once; each name's token length is one past the longest
     * common prefix it shares with either sorted neighbor, so every token is
     * distinct from every other by construction. Identical names cannot occur
     * (one info hash = one torrent).
     *
     * @param names base64 info-hash names of all rendered torrents
     * @return map of full name to its token; every input name has an entry
     * @since 0.9.71+
     */
    public static Map<String, String> mint(Collection<String> names) {
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        Map<String, String> out = new HashMap<>(sorted.size() * 2);
        int n = sorted.size();
        for (int i = 0; i < n; i++) {
            String name = sorted.get(i);
            int prevLcp = i > 0 ? lcp(sorted.get(i - 1), name) : 0;
            int nextLcp = i < n - 1 ? lcp(name, sorted.get(i + 1)) : 0;
            int len = Math.min(Math.max(Math.max(prevLcp, nextLcp) + 1, MIN_TOKEN_LEN),
                               Math.min(name.length(), MAX_TOKEN_LEN));
            out.put(name, name.substring(0, len));
        }
        return out;
    }

    /**
     * Resolves a token against candidate names: returns the single name that
     * starts with the token, or null when none or several do. The multi-match
     * null return is what makes stale/ambiguous tokens a safe no-op instead of
     * a misdirected action.
     *
     * @param token the token extracted from a submitted control name
     * @param names base64 info-hash names of all currently loaded torrents
     * @return the unique matching name, or null when zero or multiple match
     * @since 0.9.71+
     */
    public static String resolveUnique(String token, Collection<String> names) {
        if (token == null || token.isEmpty()) {return null;}
        String match = null;
        for (String name : names) {
            if (!name.startsWith(token)) {continue;}
            if (match != null) {return null;}
            match = name;
        }
        return match;
    }

    /**
     * Longest common prefix length of two strings.
     *
     * @param a first string
     * @param b second string
     * @return number of leading characters shared by both
     */
    private static int lcp(String a, String b) {
        int max = Math.min(a.length(), b.length());
        for (int i = 0; i < max; i++) {
            if (a.charAt(i) != b.charAt(i)) {return i;}
        }
        return max;
    }
}
