package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link ActionTokens}: minimal-unique-prefix minting and
 * exactly-one-match resolution, including the shared-prefix families that make
 * naive truncation ambiguous.
 *
 * @since 0.9.71+
 */
public class ActionTokensTest {

    private static Map<String, String> mint(String... names) {
        return ActionTokens.mint(Arrays.asList(names));
    }

    private static Collection<String> names(String... names) {
        return Arrays.asList(names);
    }

    @Test
    public void everyNameGetsADistinctToken() {
        Map<String, String> tokens = mint("aaaa", "aaab", "aabb", "bbbb");
        assertNotEquals(tokens.get("aaaa"), tokens.get("aaab"));
        assertNotEquals(tokens.get("aaab"), tokens.get("aabb"));
        assertNotEquals(tokens.get("aabb"), tokens.get("bbbb"));
        assertEquals(4, new java.util.HashSet<>(tokens.values()).size());
    }

    @Test
    public void tokenLengthExceedsLongestSharedPrefixWithNeighbors() {
        // "aaaa" vs "aaab" share 3 chars → aaaa's token must be ≥4 chars.
        Map<String, String> tokens = mint("aaaa", "aaab", "bbbb");
        assertEquals(4, tokens.get("aaaa").length());
        assertEquals(4, tokens.get("aaab").length());
        // "bbbb" differs from "aaab" at index 0; the floor keeps it at 4 chars.
        assertEquals(4, tokens.get("bbbb").length());
    }

    @Test
    public void tokenFloorKeepsShortTokensReadable() {
        Map<String, String> tokens = mint("VfUGfKCEwbUc", "rBwlnWhVF~pS");
        for (String token : tokens.values()) {
            assertTrue("token below floor: " + token, token.length() >= 4);
        }
    }

    @Test
    public void tokensResolveUniquelyAgainstTheSameSet() {
        List<String> set = Arrays.asList("VfUGfKCEwbUc", "rBwlnWhVF~pS", "j--a9NNZlfRJ");
        Map<String, String> tokens = ActionTokens.mint(set);
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            assertEquals(e.getKey(), ActionTokens.resolveUnique(e.getValue(), set));
        }
    }

    @Test
    public void resolveDropsAmbiguousTokensInsteadOfGuessing() {
        // Two loaded torrents sharing the prefix "ab": token "ab" must not resolve.
        assertNull(ActionTokens.resolveUnique("ab", names("abc", "abd")));
        assertNull(ActionTokens.resolveUnique("a", names("abc", "abd", "axx")));
        // Longer prefixes still resolve uniquely.
        assertEquals("abc", ActionTokens.resolveUnique("abc", names("abc", "abd", "axx")));
    }

    @Test
    public void resolveReturnsNullForUnknownOrEmptyTokens() {
        Collection<String> set = names("alpha", "beta");
        assertNull(ActionTokens.resolveUnique("gamma", set));
        assertNull(ActionTokens.resolveUnique("", set));
        assertNull(ActionTokens.resolveUnique(null, set));
    }

    @Test
    public void removalOfOneTorrentDoesNotBreakRemainingTokens() {
        // Tokens minted for the full set keep resolving after a torrent is gone,
        // because remaining names still start with their own (longer) tokens.
        List<String> fullSet = new ArrayList<>(Arrays.asList("hash1name", "hash2name"));
        Map<String, String> tokens = ActionTokens.mint(fullSet);
        List<String> afterRemoval = new ArrayList<>(fullSet);
        afterRemoval.remove("hash2name");

        String keptName = "hash1name";
        String keptToken = tokens.get(keptName);
        assertEquals(keptName, ActionTokens.resolveUnique(keptToken, afterRemoval));
    }
}
