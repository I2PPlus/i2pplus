package org.klomp.snark;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.junit.Test;

/**
 * Verify {@link TorrentCreateFilter} survives Java serialization with its
 * explicit {@code serialVersionUID}. The UI reads this object back from the
 * serialized {@code filters.conf} file, so the added SUID must not break the
 * round trip.
 *
 * @since 0.9.71+
 */
public class TorrentCreateFilterTest {

    /** A plain data round trip must reproduce every field. */
    @Test
    public void testSerializationRoundTrip() throws Exception {
        TorrentCreateFilter original = new TorrentCreateFilter("Mine", "*.pdf", "exclude", true);

        byte[] bytes = serialize(original);
        TorrentCreateFilter copy = (TorrentCreateFilter) deserialize(bytes);

        assertEquals(original.name, copy.name);
        assertEquals(original.filterPattern, copy.filterPattern);
        assertEquals(original.filterType, copy.filterType);
        assertEquals(original.isDefault, copy.isDefault);
    }

    /** The default filter (isDefault=true) must serialize and come back as the default. */
    @Test
    public void testSerializationOfDefaultFilter() throws Exception {
        TorrentCreateFilter original = new TorrentCreateFilter("Default", "*", "include", true);

        TorrentCreateFilter copy = (TorrentCreateFilter) deserialize(serialize(original));

        assertEquals("*", copy.filterPattern);
        assertEquals("include", copy.filterType);
        assertEquals(true, copy.isDefault);
    }

    /** Empty pattern/value fields must survive a round trip unchanged. */
    @Test
    public void testSerializationOfEmptyFields() throws Exception {
        TorrentCreateFilter original = new TorrentCreateFilter("", "", "", false);

        TorrentCreateFilter copy = (TorrentCreateFilter) deserialize(serialize(original));

        assertEquals("", copy.name);
        assertEquals("", copy.filterPattern);
        assertEquals("", copy.filterType);
        assertEquals(false, copy.isDefault);
    }

    private static byte[] serialize(TorrentCreateFilter f) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(f);
        }
        return out.toByteArray();
    }

    private static Object deserialize(byte[] bytes) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }
}
