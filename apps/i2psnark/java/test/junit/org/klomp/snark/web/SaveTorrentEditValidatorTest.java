package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.util.*;

import org.klomp.snark.Tracker;
import org.klomp.snark.MetaInfo;
import org.junit.Test;

/**
 * Tests for I2PSnarkServlet saveTorrentEdit pure helpers:
 * {@link I2PSnarkServlet#parseEditParams},
 * {@link I2PSnarkServlet#hasChanges},
 * {@link I2PSnarkServlet#buildAnnounceList}.
 *
 * @since 0.9.71+
 */
public class SaveTorrentEditValidatorTest {

    // ---- parseEditParams --------------------------------------------------

    @Test
    public void testParseEditParamsEmpty() {
        Map<String, String[]> empty = new HashMap<>();
        I2PSnarkServlet.EditParams ep = I2PSnarkServlet.parseEditParams(empty);
        assertTrue(ep.toAdd.isEmpty());
        assertTrue(ep.toDel.isEmpty());
        assertNull(ep.primary);
        assertEquals("", ep.newComment);
        assertEquals("", ep.newCreatedBy);
    }

    @Test
    public void testParseEditParamsAddRemovePrimary() {
        Map<String, String[]> params = new HashMap<>();
        params.put("addTracker-123", new String[] { "" });
        params.put("addTracker-456", new String[] { "" });
        params.put("removeTracker-789", new String[] { "" });
        params.put("primary", new String[] { "999" });
        params.put("nofilter_newTorrentComment", new String[] { "  hello  " });
        params.put("nofilter_newTorrentCreatedBy", new String[] { "  me  " });

        I2PSnarkServlet.EditParams ep = I2PSnarkServlet.parseEditParams(params);
        assertEquals(Arrays.asList(123, 456), ep.toAdd);
        assertEquals(Arrays.asList(789), ep.toDel);
        assertEquals(Integer.valueOf(999), ep.primary);
        assertEquals("hello", ep.newComment);
        assertEquals("me", ep.newCreatedBy);
    }

    @Test
    public void testParseEditParamsInvalidIdsIgnored() {
        Map<String, String[]> params = new HashMap<>();
        params.put("addTracker-abc", new String[] { "" });
        params.put("removeTracker-", new String[] { "" });
        params.put("primary", new String[] { "notanint" });
        params.put("addTracker-1", new String[] { "" });

        I2PSnarkServlet.EditParams ep = I2PSnarkServlet.parseEditParams(params);
        assertEquals(Arrays.asList(1), ep.toAdd);
        assertTrue(ep.toDel.isEmpty());
        assertNull(ep.primary);
    }

    // ---- hasChanges -------------------------------------------------------

    private static MetaInfo makeMeta(String announce, String comment, String createdBy) throws Exception {
        return new MetaInfo(new ByteArrayInputStream(buildTorrentBytes(announce, comment, createdBy)));
    }

    private static MetaInfo makeMetaWithAnnounceList(String announce, String comment, String createdBy,
            List<List<String>> announceList) throws Exception {
        MetaInfo base = new MetaInfo(new ByteArrayInputStream(buildTorrentBytes(announce, comment, createdBy)));
        // Use the copy constructor to create a new MetaInfo with the custom announce list
        return new MetaInfo(base, announce, announceList, comment, createdBy, null);
    }

    private static byte[] buildTorrentBytes(String announce, String comment, String createdBy) {
        byte[] pieceHash = new byte[20]; // SHA1 hash (1 piece)
        StringBuilder sb = new StringBuilder(256);
        sb.append('d');
        sb.append("8:announce");
        sb.append(announce.length()).append(':').append(announce);
        if (comment != null) {
            sb.append("7:comment");
            sb.append(comment.length()).append(':').append(comment);
        }
        if (createdBy != null) {
            sb.append("10:created by");
            sb.append(createdBy.length()).append(':').append(createdBy);
        }
        sb.append("4:info");
        sb.append('d');
        sb.append("6:length").append('i').append(1000).append('e');
        sb.append("4:name").append("8:test.txt");
        sb.append("12:piece length").append('i').append(16384).append('e');
        sb.append("6:pieces").append(20).append(':');
        // append piece hash bytes (20 bytes)
        byte[] head = sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] tail = "ee".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] rv = new byte[head.length + pieceHash.length + tail.length];
        System.arraycopy(head, 0, rv, 0, head.length);
        System.arraycopy(pieceHash, 0, rv, head.length, pieceHash.length);
        System.arraycopy(tail, 0, rv, head.length + pieceHash.length, tail.length);
        return rv;
    }

    @Test
    public void testHasChangesNoChange() throws Exception {
        MetaInfo meta = makeMeta("http://t1.i2p/announce", "old comment", "old by");
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(), Collections.emptyList(), null, "old comment", "old by");
        assertFalse(I2PSnarkServlet.hasChanges(meta, ep));
    }

    @Test
    public void testHasChangesCommentDiffers() throws Exception {
        MetaInfo meta = makeMeta("http://t1.i2p/announce", "old", "by");
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(), Collections.emptyList(), null, "new", "by");
        assertTrue(I2PSnarkServlet.hasChanges(meta, ep));
    }

    @Test
    public void testHasChangesCreatedByDiffers() throws Exception {
        MetaInfo meta = makeMeta("http://t1.i2p/announce", "comment", "old");
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(), Collections.emptyList(), null, "comment", "new");
        assertTrue(I2PSnarkServlet.hasChanges(meta, ep));
    }

    @Test
    public void testHasChangesPrimaryDiffers() throws Exception {
        MetaInfo meta = makeMeta("http://t1.i2p/announce", "c", "b");
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(), Collections.emptyList(), Integer.valueOf(123), "c", "b");
        assertTrue(I2PSnarkServlet.hasChanges(meta, ep));
    }

    @Test
    public void testHasChangesAddTracker() throws Exception {
        MetaInfo meta = makeMeta("http://t1.i2p/announce", "c", "b");
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Arrays.asList(1), Collections.emptyList(), null, "c", "b");
        assertTrue(I2PSnarkServlet.hasChanges(meta, ep));
    }

    @Test
    public void testHasChangesRemoveTracker() throws Exception {
        MetaInfo meta = makeMeta("http://t1.i2p/announce", "c", "b");
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(), Arrays.asList(1), null, "c", "b");
        assertTrue(I2PSnarkServlet.hasChanges(meta, ep));
    }

    @Test
    public void testHasChangesNullCommentAndCreatedBy() throws Exception {
        MetaInfo meta = makeMeta("http://t1.i2p/announce", null, null);
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(), Collections.emptyList(), null, "", "");
        assertFalse(I2PSnarkServlet.hasChanges(meta, ep));
    }

    // ---- buildAnnounceList ------------------------------------------------

    private static List<Tracker> trackers(String... urls) {
        List<Tracker> list = new ArrayList<>();
        for (int i = 0; i < urls.length; i++) {
            list.add(new Tracker("t" + i, urls[i], "http://t" + i + ".i2p"));
        }
        return list;
    }

    @Test
    public void testBuildAnnounceListNoChange() throws Exception {
        MetaInfo meta = makeMeta("http://t1.i2p/announce", "c", "b");
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(), Collections.emptyList(), null, "c", "b");
        List<Tracker> trackers = trackers("http://t1.i2p/announce", "http://t2.i2p/announce");

        I2PSnarkServlet.AnnounceListResult alr = I2PSnarkServlet.buildAnnounceList(meta, ep, trackers, true);
        assertNotNull(alr.newAnnList);
        assertEquals(1, alr.newAnnList.size());
        assertEquals(1, alr.newAnnList.get(0).size());
        assertEquals("http://t1.i2p/announce", alr.newAnnList.get(0).get(0));
        assertEquals("http://t1.i2p/announce", alr.thePrimary);
    }

    @Test
    public void testBuildAnnounceListAddTracker() throws Exception {
        MetaInfo meta = makeMeta("http://t1.i2p/announce", "c", "b");
        int t2Hash = "http://t2.i2p/announce".hashCode();
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Arrays.asList(t2Hash), Collections.emptyList(), null, "c", "b");
        List<Tracker> trackers = trackers("http://t1.i2p/announce", "http://t2.i2p/announce");

        I2PSnarkServlet.AnnounceListResult alr = I2PSnarkServlet.buildAnnounceList(meta, ep, trackers, true);
        assertNotNull(alr.newAnnList);
        assertEquals(1, alr.newAnnList.size());
        assertEquals(2, alr.newAnnList.get(0).size());
        assertTrue(alr.newAnnList.get(0).contains("http://t1.i2p/announce"));
        assertTrue(alr.newAnnList.get(0).contains("http://t2.i2p/announce"));
    }

    @Test
    public void testBuildAnnounceListRemoveTracker() throws Exception {
        MetaInfo meta = makeMetaWithAnnounceList(
            "http://t1.i2p/announce",
            "c", "b",
            Collections.singletonList(Arrays.asList("http://t1.i2p/announce", "http://t2.i2p/announce"))
        );
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(),
            Collections.singletonList("http://t2.i2p/announce".hashCode()),
            null, "c", "b");
        List<Tracker> trackers = trackers("http://t1.i2p/announce", "http://t2.i2p/announce");

        I2PSnarkServlet.AnnounceListResult alr = I2PSnarkServlet.buildAnnounceList(meta, ep, trackers, true);
        assertNotNull(alr.newAnnList);
        assertEquals(1, alr.newAnnList.get(0).size());
        assertEquals("http://t1.i2p/announce", alr.newAnnList.get(0).get(0));
    }

    @Test
    public void testBuildAnnounceListSelectPrimary() throws Exception {
        MetaInfo meta = makeMetaWithAnnounceList(
            "http://t1.i2p/announce",
            "c", "b",
            Collections.singletonList(Arrays.asList("http://t1.i2p/announce", "http://t2.i2p/announce"))
        );
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(), Collections.emptyList(),
            Integer.valueOf("http://t2.i2p/announce".hashCode()), "c", "b");
        List<Tracker> trackers = trackers("http://t1.i2p/announce", "http://t2.i2p/announce");

        I2PSnarkServlet.AnnounceListResult alr = I2PSnarkServlet.buildAnnounceList(meta, ep, trackers, true);
        assertEquals("http://t2.i2p/announce", alr.thePrimary);
    }

    @Test
    public void testBuildAnnounceListPrimaryNotInListFallsBack() throws Exception {
        MetaInfo meta = makeMetaWithAnnounceList(
            "http://t1.i2p/announce",
            "c", "b",
            Collections.singletonList(Collections.singletonList("http://t1.i2p/announce"))
        );
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(), Collections.emptyList(),
            Integer.valueOf(999999), "c", "b");
        List<Tracker> trackers = trackers("http://t1.i2p/announce");

        I2PSnarkServlet.AnnounceListResult alr = I2PSnarkServlet.buildAnnounceList(meta, ep, trackers, true);
        assertEquals("http://t1.i2p/announce", alr.thePrimary);
    }

    @Test
    public void testBuildAnnounceListEmptyResult() throws Exception {
        MetaInfo meta = makeMeta("http://t1.i2p/announce", "c", "b");
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(),
            Collections.singletonList("http://t1.i2p/announce".hashCode()),
            null, "c", "b");
        List<Tracker> trackers = trackers("http://t1.i2p/announce");

        I2PSnarkServlet.AnnounceListResult alr = I2PSnarkServlet.buildAnnounceList(meta, ep, trackers, true);
        assertNull(alr.newAnnList);
        assertNull(alr.thePrimary);
    }

    @Test
    public void testBuildAnnounceListStripsNonI2pFromAnnounceListAndPrimary() throws Exception {
        // The announce list is filtered for non-i2p, AND oldPrimary is filtered too
        MetaInfo meta = makeMetaWithAnnounceList(
            "http://tracker.public/announce",
            "c", "b",
            Collections.singletonList(Arrays.asList("http://tracker.public/announce", "http://t1.i2p/announce"))
        );
        I2PSnarkServlet.EditParams ep = new I2PSnarkServlet.EditParams(
            Collections.emptyList(), Collections.emptyList(), null, "c", "b");
        List<Tracker> trackers = trackers("http://t1.i2p/announce");

        I2PSnarkServlet.AnnounceListResult alr = I2PSnarkServlet.buildAnnounceList(meta, ep, trackers, true);
        assertNotNull(alr.newAnnList);
        // Only i2p trackers remain (1 from announce list, oldPrimary is non-i2p so filtered out)
        assertEquals(1, alr.newAnnList.get(0).size());
        assertEquals("http://t1.i2p/announce", alr.newAnnList.get(0).get(0));
        // primary should be the first i2p tracker since oldPrimary was non-i2p
        assertEquals("http://t1.i2p/announce", alr.thePrimary);
    }

    @Test
    public void testIsI2PTrackerStatic() {
        // http, udp enabled
        assertTrue(I2PSnarkServlet.isI2PTracker("http://tracker.i2p/announce", true));
        assertTrue(I2PSnarkServlet.isI2PTracker("udp://tracker.i2p/announce", true));
        // http, udp disabled
        assertTrue(I2PSnarkServlet.isI2PTracker("http://tracker.i2p/announce", false));
        assertFalse(I2PSnarkServlet.isI2PTracker("udp://tracker.i2p/announce", false));
        // non-i2p
        assertFalse(I2PSnarkServlet.isI2PTracker("http://tracker.public/announce", true));
        assertFalse(I2PSnarkServlet.isI2PTracker("http://tracker.com/announce", true));
        // invalid
        assertFalse(I2PSnarkServlet.isI2PTracker("not-a-url", true));
        assertFalse(I2PSnarkServlet.isI2PTracker("", true));
        assertFalse(I2PSnarkServlet.isI2PTracker(null, true));
    }
}
