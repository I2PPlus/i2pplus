package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

/**
 * Tests for {@link MetaInfo#keyNames}, the compact stand-in for the bencoded
 * dictionary dump in the torrent parse log: sorted key names, comma separated,
 * and an empty string rather than "null" or "[]" when there is nothing to show.
 *
 * @since 0.9.72+
 */
public class MetaInfoKeyNamesTest {

    @Test
    public void nullMapHasNoNames() {
        assertEquals("", MetaInfo.keyNames(null));
    }

    @Test
    public void emptyMapHasNoNames() {
        assertEquals("", MetaInfo.keyNames(new HashMap<String, Object>()));
    }

    @Test
    public void singleKeyHasNoSeparator() {
        Map<String, Object> m = new HashMap<>();
        m.put("info", "x");
        assertEquals("info", MetaInfo.keyNames(m));
    }

    @Test
    public void keysAreSortedNotInMapOrder() {
        // a HashMap gives no useful order, and the same torrent file must produce
        // the same log line on every run
        Map<String, Object> m = new HashMap<>();
        m.put("info", "x");
        m.put("announce", "x");
        m.put("creation date", "x");
        m.put("announce-list", "x");
        assertEquals("announce, announce-list, creation date, info", MetaInfo.keyNames(m));
    }

    @Test
    public void sortedMapKeepsItsOrder() {
        Map<String, Object> m = new TreeMap<>();
        m.put("encoding", "x");
        m.put("name", "x");
        m.put("piece length", "x");
        m.put("pieces", "x");
        assertEquals("encoding, name, piece length, pieces", MetaInfo.keyNames(m));
    }

    @Test
    public void spacesInKeysSurvive() {
        // "piece length" and "creation date" are the bencode names, and they are
        // the reason the separator is ", " and not something a key can contain
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("piece length", "x");
        m.put("creation date", "x");
        assertEquals("creation date, piece length", MetaInfo.keyNames(m));
    }

    @Test
    public void nonStringKeysAreNamedAnyway() {
        Map<Object, Object> m = new HashMap<>();
        m.put(7, "x");
        m.put("name", "x");
        assertEquals("7, name", MetaInfo.keyNames(m));
    }
}
