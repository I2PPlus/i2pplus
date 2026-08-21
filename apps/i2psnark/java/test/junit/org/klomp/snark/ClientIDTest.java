package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import net.i2p.I2PAppContext;

import org.junit.Test;

/**
 * Tests for the client identity registry: recognition table, spoof profile
 * consistency, peer ID generation, and property parsing.
 *
 * @since 0.9.71+
 */
public class ClientIDTest {

    private static final Random RANDOM = new Random(42);

    @Test
    public void testGetClientNameKnownPrefixes() {
        assertEquals("I2PSnark", ClientID.getClientName("AwMD"));
        assertEquals("Vuze", ClientID.getClientName("LUFa"));
        assertEquals("BiglyBT", ClientID.getClientName("LUJJ"));
        assertEquals("XD", ClientID.getClientName("LVhE"));
        assertEquals("Transmission", ClientID.getClientName("LVhZ"));
        assertEquals("KTorrent", ClientID.getClientName("LUtU"));
        assertEquals("EepTorrent", ClientID.getClientName("LUVU"));
        assertEquals("Deluge", ClientID.getClientName("LURF"));
        assertEquals("qBittorrent", ClientID.getClientName("LXFC"));
        assertEquals("libtorrent", ClientID.getClientName("LUxU"));
        assertEquals("Tixati", ClientID.getClientName("VElY"));
        assertEquals("i2pd", ClientID.getClientName("LUky"));
        assertEquals("Robert", ClientID.getClientName("VUZP"));
        assertEquals("Robert", ClientID.getClientName("AAZV"));
        assertEquals("I2PSnarkXL", ClientID.getClientName("CwsL"));
        assertEquals("I2PRufus", ClientID.getClientName("BFJT"));
        assertEquals("I2P-BT", ClientID.getClientName("TTMt"));
    }

    @Test
    public void testGetClientNameUnknown() {
        assertNull(ClientID.getClientName("abcd"));
        assertNull(ClientID.getClientName("AAAA"));
    }

    @Test
    public void testProfilesAreWellFormed() {
        List<ClientID.Profile> profiles = ClientID.profiles();
        assertEquals(8, profiles.size());
        for (int i = 0; i < profiles.size(); i++) {
            ClientID.Profile p = profiles.get(i);
            assertNotNull(p.getName());
            String prefix = p.getPeerIdPrefix();
            assertTrue(
                    "prefix length out of range for " + p.getName(),
                    prefix.length() >= 5 && prefix.length() <= 8);
            assertNotNull(p.getUserAgent());
            assertFalse(p.getUserAgent().isEmpty());
            assertNotNull(p.getExtHandshakeName());
            assertFalse(p.getExtHandshakeName().isEmpty());
            // names are unique
            for (int j = i + 1; j < profiles.size(); j++) {
                assertNotEquals(p.getName(), profiles.get(j).getName());
            }
        }
    }

    @Test
    public void testBuildPeerId() {
        for (ClientID.Profile p : ClientID.profiles()) {
            byte[] id = p.buildPeerId(RANDOM);
            assertEquals(20, id.length);
            byte[] prefix = p.getPeerIdPrefix().getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < prefix.length; i++) {
                assertEquals("prefix mismatch at " + i + " for " + p.getName(),
                             prefix[i], id[i]);
            }
            for (int i = prefix.length; i < id.length; i++) {
                char c = (char) (id[i] & 0xff);
                assertTrue("bad tail char " + c + " for " + p.getName(),
                           Character.isLetterOrDigit(c));
            }
        }
    }

    @Test
    public void testBuildPeerIdRandomized() {
        byte[] first = ClientID.VUZE.buildPeerId(new Random(1));
        byte[] second = ClientID.VUZE.buildPeerId(new Random(2));
        assertFalse(Arrays.equals(first, second));
    }

    /**
     * The version digits in the peer ID prefix must match the versions in the
     * UA and handshake strings, as trackers may cross-check them.
     */
    @Test
    public void testVersionConsistency() {
        assertVersionMatches(ClientID.VUZE, "-AZ5770-", "5.7.7.0");
        assertVersionMatches(ClientID.BIGLYBT, "-BI4100-", "4.1.0.0");
        assertVersionMatches(ClientID.TRANSMISSION, "-TR4130-", "4.1.3");
        assertVersionMatches(ClientID.KTORRENT, "-KT2604-", "26.04.3");
        assertVersionMatches(ClientID.DELUGE, "-DE2200-", "2.2.0");
        assertVersionMatches(ClientID.QBITTORRENT, "-qB5230-", "5.2.3");
        assertVersionMatches(ClientID.LIBTORRENT, "-LT1219-", "1.2.19");
        assertVersionMatches(ClientID.TIXATI, "TIX34", "3.44");
    }

    private static void assertVersionMatches(
            ClientID.Profile p, String prefix, String version) {
        assertEquals(prefix, p.getPeerIdPrefix());
        assertTrue(
                "UA missing version for " + p.getName(),
                p.getUserAgent().contains(version));
        assertTrue(
                "handshake v missing version for " + p.getName(),
                p.getExtHandshakeName().contains(version));
    }

    @Test
    public void testVuzeUserAgentHasOsAndJava() {
        String ua = ClientID.VUZE.getUserAgent();
        String os = System.getProperty("os.name", "");
        String jv = System.getProperty("java.version", "");
        assertEquals("Azureus 5.7.7.0;" + os + ";Java " + jv, ua);
        // but the handshake name does not carry OS/Java
        assertEquals("Vuze 5.7.7.0", ClientID.VUZE.getExtHandshakeName());
    }

    @Test
    public void testGetByName() {
        assertSame(ClientID.VUZE, ClientID.getByName("Vuze"));
        assertSame(ClientID.VUZE, ClientID.getByName("vuze"));
        assertSame(ClientID.QBITTORRENT, ClientID.getByName("qBittorrent"));
        assertNull(ClientID.getByName("NoSuchClient"));
        assertNull(ClientID.getByName(""));
        assertNull(ClientID.getByName(null));
    }

    @Test
    public void testGetRandomProfile() {
        for (int i = 0; i < 50; i++) {
            assertTrue(ClientID.profiles().contains(ClientID.getRandomProfile(RANDOM, null)));
            assertTrue(ClientID.profiles()
                               .contains(ClientID.getRandomProfile(
                                       RANDOM, Collections.<ClientID.Profile>emptyList())));
        }
        List<ClientID.Profile> subset =
                Collections.singletonList(ClientID.TRANSMISSION);
        for (int i = 0; i < 10; i++) {
            assertSame(ClientID.TRANSMISSION, ClientID.getRandomProfile(RANDOM, subset));
        }
    }

    @Test
    public void testParseCandidateList() {
        List<ClientID.Profile> rv =
                ClientID.parseCandidateList(" vuze , QBittorrent ,Bogus,");
        assertEquals(2, rv.size());
        assertEquals("Vuze", rv.get(0).getName());
        assertEquals("qBittorrent", rv.get(1).getName());
        assertTrue(ClientID.parseCandidateList("").isEmpty());
        assertTrue(ClientID.parseCandidateList("Bogus").isEmpty());
        assertTrue(ClientID.parseCandidateList(null).isEmpty());
        // duplicates collapse
        assertEquals(1, ClientID.parseCandidateList("Vuze,vuze").size());
    }

    /** The generated peer ID is recognized by our own identification table. */
    @Test
    public void testGeneratedIdsAreRecognized() {
        for (ClientID.Profile p : ClientID.profiles()) {
            byte[] id = p.buildPeerId(RANDOM);
            String ch = net.i2p.data.Base64.encode(id).substring(0, 4);
            assertEquals(
                    "generated ID not recognized for " + p.getName(),
                    p.getName(),
                    ClientID.getClientName(ch));
        }
    }

    @Test
    public void testMultiDestDefaultRandomPerPool() {
        I2PSnarkUtil util = newUtil();
        util.setMultiDest(true);
        util.setMaxDest(4);
        byte[] ih = randomHash();
        ClientID.Profile p = util.getClientID(ih);
        assertNotNull("multi-dest default should spoof", p);
        assertTrue(ClientID.profiles().contains(p));
        // stable per torrent, and the UA follows the profile
        assertSame(p, util.getClientID(ih));
        assertEquals(p.getUserAgent(), util.getUserAgent(ih));
    }

    @Test
    public void testSingleDestDefaultUnspoofed() {
        I2PSnarkUtil util = newUtil();
        assertNull(util.getClientID(randomHash()));
        assertEquals(I2PSnarkUtil.EEPGET_USER_AGENT, util.getUserAgent(randomHash()));
    }

    @Test
    public void testExplicitI2PSnarkDisablesSpoofing() {
        I2PSnarkUtil util = newUtil();
        util.setClientId("i2psnark");
        util.setMultiDest(true);
        util.setMaxDest(4);
        assertNull(util.getClientID(randomHash()));
    }

    @Test
    public void testExplicitNameBothModes() {
        I2PSnarkUtil util = newUtil();
        util.setClientId("vuze");
        assertSame(ClientID.VUZE, util.getClientID(randomHash()));
        util.setMultiDest(true);
        util.setMaxDest(4);
        assertSame(ClientID.VUZE, util.getClientID(randomHash()));
    }

    private static I2PSnarkUtil newUtil() {
        return new I2PSnarkUtil(I2PAppContext.getGlobalContext());
    }

    private static byte[] randomHash() {
        byte[] rv = new byte[20];
        RANDOM.nextBytes(rv);
        return rv;
    }
}
