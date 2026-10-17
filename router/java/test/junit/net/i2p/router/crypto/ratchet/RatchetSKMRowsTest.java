package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.i2p.crypto.EncType;
import net.i2p.data.PublicKey;

/**
 * Tests the pure row builders and labels of
 * {@link RatchetSKM#buildInboundRows},
 * {@link RatchetSKM#buildOutboundRows} and
 * {@link RatchetSKM#buildOutboundSetRows}: peer labels are the full base64
 * key, set labels use the NSR/ES special cases, expired outbound sets are
 * skipped, and rows sort by peer then set.
 *
 * @since 0.9.71
 */
public class RatchetSKMRowsTest {

    private static PublicKey key(byte fill) {
        byte[] data = new byte[32];
        data[0] = fill;
        return new PublicKey(EncType.ECIES_X25519, data);
    }

    private static RatchetTagSet tagSet(int id, int debugID, long expiration, int size, int remaining) {
        RatchetTagSet ts = mock(RatchetTagSet.class);
        when(ts.getID()).thenReturn(id);
        when(ts.getDebugID()).thenReturn(debugID);
        when(ts.getExpiration()).thenReturn(expiration);
        when(ts.size()).thenReturn(size);
        when(ts.remaining()).thenReturn(remaining);
        return ts;
    }

    @Test
    public void keyLabelFullBase64OrNull() {
        assertEquals("null", RatchetSKM.keyLabel(null));
        String label = RatchetSKM.keyLabel(key((byte) 1));
        assertEquals(label, key((byte) 1).toBase64());
        assertFalse(label.endsWith("..."));
        assertFalse(label.startsWith("["));
    }

    @Test
    public void setIdLabelSpecialCases() {
        assertEquals("NSR/12", RatchetSKM.setIdLabel(RatchetTagSet.DEBUG_IB_NSR, 12));
        assertEquals("NSR/7", RatchetSKM.setIdLabel(RatchetTagSet.DEBUG_OB_NSR, 7));
        assertEquals("ES/3", RatchetSKM.setIdLabel(RatchetTagSet.DEBUG_SINGLE_ES, 3));
        assertEquals("42/9", RatchetSKM.setIdLabel(42, 9));
    }

    @Test
    public void inboundRowsSortedByPeerThenSet() {
        Map<PublicKey, Set<RatchetTagSet>> sets = new HashMap<>();
        Set<RatchetTagSet> b = new HashSet<>();
        b.add(tagSet(5, 2, 60_000, 3, 10));
        b.add(tagSet(RatchetTagSet.DEBUG_IB_NSR, 1, 60_000, 4, 20));
        sets.put(key((byte) 2), b);
        Set<RatchetTagSet> a = new HashSet<>();
        a.add(tagSet(RatchetTagSet.DEBUG_SINGLE_ES, 8, 60_000, 1, 1));
        sets.put(key((byte) 1), a);
        List<String[]> rows = RatchetSKM.buildInboundRows(sets, 0);
        assertEquals(3, rows.size());
        assertEquals(key((byte) 1).toBase64(), rows.get(0)[0]);
        assertEquals("ES/8", rows.get(0)[1]);
        assertEquals(key((byte) 2).toBase64(), rows.get(1)[0]);
        assertEquals("5/2", rows.get(1)[1]);
        assertEquals("NSR/1", rows.get(2)[1]);
        assertEquals("4", rows.get(2)[3]);
        assertEquals("20", rows.get(2)[4]);
    }

    @Test
    public void outboundRowsFromSession() {
        // OutboundSession is an inner class; mock it directly
        RatchetSKM.OutboundSession sess = mock(RatchetSKM.OutboundSession.class);
        when(sess.getTarget()).thenReturn(key((byte) 3));
        when(sess.getEstablishedDate()).thenReturn(0L);
        when(sess.getLastUsedDate()).thenReturn(0L);
        when(sess.getLastReceivedDate()).thenReturn(0L);
        List<RatchetTagSet> ts = new ArrayList<>();
        ts.add(tagSet(1, 1, 1, 1, 1));
        when(sess.getTagSets()).thenReturn(ts);
        List<String[]> rows = RatchetSKM.buildOutboundRows(java.util.Collections.singletonList(sess), 0);
        assertEquals(1, rows.size());
        assertEquals(key((byte) 3).toBase64(), rows.get(0)[0]);
        assertTrue(rows.get(0)[1].endsWith(" ago"));
        assertEquals("1", rows.get(0)[4]);
    }

    @Test
    public void outboundSetRowsSkipExpiredAndMapFlags() {
        RatchetSKM.OutboundSession sess = mock(RatchetSKM.OutboundSession.class);
        when(sess.getTarget()).thenReturn(key((byte) 4));
        RatchetTagSet live = tagSet(RatchetTagSet.DEBUG_OB_NSR, 6, 120_000, 0, 15);
        when(live.getAcked()).thenReturn(true);
        when(live.getCreated()).thenReturn(0L);
        when(live.getDate()).thenReturn(0L);
        when(live.getNextKey()).thenReturn(mock(NextSessionKey.class));
        RatchetTagSet expired = tagSet(9, 9, 0, 0, 0);
        List<RatchetTagSet> ts = new ArrayList<>();
        ts.add(live);
        ts.add(expired);
        when(sess.getTagSets()).thenReturn(ts);
        List<String[]> rows = RatchetSKM.buildOutboundSetRows(java.util.Collections.singletonList(sess), 0);
        assertEquals(1, rows.size());
        assertEquals("NSR/6", rows.get(0)[1]);
        assertEquals("yes", rows.get(0)[2]);
        assertEquals("15", rows.get(0)[6]);
        assertEquals("yes", rows.get(0)[7]);
    }

    @Test
    public void unifyRowsShareTheColumnGrid() {
        List<String[]> inbound = java.util.Collections.singletonList(new String[] {"peerA", "set1", "5 min", "12", "8"});
        List<String[]> sessions = java.util.Collections.singletonList(new String[] {"peerB", "1 hr ago", "2 min ago", "3 min ago", "4"});
        List<String[]> sets = java.util.Collections.singletonList(new String[] {"peerB", "set2", "yes", "created", "date", "20 min", "6", "no"});
        List<String[]> rows = RatchetSKM.unifyRows(inbound, sessions, sets);
        assertEquals(3, rows.size());
        for (String[] r : rows) {
            assertEquals(12, r.length);
        }
        assertEquals("Inbound", rows.get(0)[0]);
        assertEquals("set1", rows.get(0)[2]);
        assertEquals("5 min", rows.get(0)[8]);    // Expires in
        assertEquals("12", rows.get(0)[9]);       // Tags
        assertEquals("8", rows.get(0)[10]);       // Remaining
        assertEquals("—", rows.get(0)[3]);
        assertEquals("Session", rows.get(1)[0]);
        assertEquals("1 hr ago", rows.get(1)[5]); // Established
        assertEquals("2 min ago", rows.get(1)[6]); // Last Used
        assertEquals("3 min ago", rows.get(1)[7]); // Last Rcvd
        assertEquals("—", rows.get(1)[2]);
        assertEquals("Set", rows.get(2)[0]);
        assertEquals("set2", rows.get(2)[2]);
        assertEquals("yes", rows.get(2)[3]);      // Acked
        assertEquals("created", rows.get(2)[4]);  // Created
        assertEquals("date", rows.get(2)[6]);     // Last Used
        assertEquals("20 min", rows.get(2)[8]);   // Expires in
        assertEquals("6", rows.get(2)[10]);       // Remaining
        assertEquals("no", rows.get(2)[11]);      // NK sent
    }
}
