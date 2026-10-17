package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests the pure row builder of {@link ListeningSocketsHelper}: internal
 * flag propagation, uncovered-socket rows, label fallback and sort order.
 *
 * @since 0.9.71
 */
public class ListeningSocketsHelperTest {

    private static Map<String, InetSocketAddress> services() {
        Map<String, InetSocketAddress> m = new HashMap<>();
        m.put("console", new InetSocketAddress("10.0.0.5", 7657));
        m.put("HTTP", new InetSocketAddress("127.0.0.1", 7738));
        m.put("I2CP", new InetSocketAddress("127.0.0.1", 7654));
        return m;
    }

    @Test
    public void internalFlagSetWhenPortAlsoAnInternalSocket() {
        Map<Integer, String> internal = new HashMap<>();
        internal.put(7738, "ProxyClient");
        List<String[]> rows = ListeningSocketsHelper.buildRows(services(), internal);
        for (String[] row : rows) {
            if (row[0].equals("HTTP")) {
                assertEquals("<span class=yes>yes</span>", row[3]);
            } else {
                assertEquals("<span class=no>no</span>", row[3]);
            }
        }
    }

    @Test
    public void coveredInternalPortGetsNoExtraRow() {
        Map<Integer, String> internal = new HashMap<>();
        internal.put(7738, "ProxyClient");
        List<String[]> rows = ListeningSocketsHelper.buildRows(services(), internal);
        assertEquals(services().size(), rows.size());
    }

    @Test
    public void uncoveredInternalSocketGetsOwnRowWithLabel() {
        Map<Integer, String> internal = new HashMap<>();
        internal.put(6666, "SusimailClient");
        List<String[]> rows = ListeningSocketsHelper.buildRows(services(), internal);
        assertEquals(services().size() + 1, rows.size());
        String[] row = null;
        for (String[] r : rows) {
            if (r[2].equals("6666")) {row = r;}
        }
        assertTrue(row != null);
        assertEquals("SusimailClient", row[0]);
        assertEquals("in-JVM", row[1]);
        assertEquals("6666", row[2]);
        assertEquals("<span class=yes>yes</span>", row[3]);
    }

    @Test
    public void unlabelledSocketFallsBackToGenericName() {
        Map<Integer, String> internal = new HashMap<>();
        internal.put(5555, null);
        List<String[]> rows = ListeningSocketsHelper.buildRows(new HashMap<>(), internal);
        assertEquals(1, rows.size());
        assertEquals("internal", rows.get(0)[0]);
    }

    @Test
    public void rowsSortedByPortThenService() {
        Map<Integer, String> internal = new HashMap<>();
        internal.put(4444, "proxy");
        List<String[]> rows = ListeningSocketsHelper.buildRows(services(), internal);
        assertEquals(4, rows.size());
        assertEquals("4444", rows.get(0)[2]);
        assertEquals("7654", rows.get(1)[2]);
        assertEquals("7657", rows.get(2)[2]);
        assertEquals("7738", rows.get(3)[2]);
    }

    @Test
    public void samePortServicesKeepServiceOrderAfterPort() {
        Map<String, InetSocketAddress> m = services();
        m.put("HTTPS", new InetSocketAddress("127.0.0.1", 7738));
        List<String[]> rows = ListeningSocketsHelper.buildRows(m, new HashMap<>());
        int first = -1;
        int second = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i)[0].equals("HTTP")) {first = i;}
            if (rows.get(i)[0].equals("HTTPS")) {second = i;}
        }
        assertTrue(first >= 0 && second >= 0 && first < second);
    }

    @Test
    public void nullAddressServiceSkipped() {
        Map<String, InetSocketAddress> m = services();
        m.put("broken", null);
        List<String[]> rows = ListeningSocketsHelper.buildRows(m, new HashMap<>());
        assertEquals(3, rows.size());
    }
}
