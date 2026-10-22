package net.i2p.data.i2np;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Build and lookup log text. The router log audit found the IBGW / OBEP
 * shorthands in build records and whole search keys at INFO in lookup
 * messages, both of which hide what a reader is looking for.
 */
public class BuildRecordLogFormatTest {

    /** The hop role has to be readable without knowing the transport jargon. */
    @Test
    public void roleNamesTheHopInPlainWords() {
        assertEquals("inbound gateway", BuildRequestRecord.role(true, false));
        assertEquals("outbound endpoint", BuildRequestRecord.role(false, true));
        assertEquals("middle hop", BuildRequestRecord.role(false, false));
    }

    /** A lease-set key is a destination: 8 base32 characters, bracketed. */
    @Test
    public void leaseSetKeyIsTruncatedToEightBase32Characters() {
        Hash key = hash(1);
        assertEquals('[' + key.toBase32().substring(0, 8) + ']',
                     DatabaseLookupMessage.shortKey(DatabaseLookupMessage.Type.LS, key));
    }

    /** A router-info key is a router hash: 6 base64 characters, bracketed. */
    @Test
    public void routerInfoKeyIsTruncatedToSixBase64Characters() {
        Hash key = hash(2);
        assertEquals('[' + key.toBase64().substring(0, 6) + ']',
                     DatabaseLookupMessage.shortKey(DatabaseLookupMessage.Type.RI, key));
    }

    /** Whatever the log level, the message dump must not carry the whole key. */
    @Test
    public void toStringOmitsTheFullSearchKey() {
        DatabaseLookupMessage msg = new DatabaseLookupMessage(I2PAppContext.getGlobalContext());
        Hash key = hash(3);
        msg.setSearchType(DatabaseLookupMessage.Type.RI);
        msg.setSearchKey(key);
        msg.setFrom(hash(4));
        String s = msg.toString();
        assertFalse("the full search key must not be logged: " + s, s.contains(key.toBase64()));
    }

    /** The full key is still available, in its own DEBUG-only dump. */
    @Test
    public void keyStringCarriesTheFullSearchKey() {
        DatabaseLookupMessage msg = new DatabaseLookupMessage(I2PAppContext.getGlobalContext());
        Hash key = hash(5);
        msg.setSearchType(DatabaseLookupMessage.Type.RI);
        msg.setSearchKey(key);
        String keys = msg.toKeyString();
        assertTrue("the debug dump must carry the key: " + keys, keys.contains(key.toBase64()));
        assertTrue("the dump must name what it holds: " + keys, keys.contains("Full search key:"));
    }

    /**
     * A hash with a known, distinct value, so the truncations and the full
     * encodings can be told apart.
     */
    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[Hash.HASH_LENGTH - 1] = (byte) (b + 1);
        return Hash.create(data);
    }
}
