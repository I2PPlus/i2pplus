package net.i2p.sam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the pure session-table builder of
 * {@link SAMBridge#buildSessionTable}: one row per session with a state dot,
 * full (untruncated) destination, html escaping, and the state-to-color
 * mapping of {@link SAMBridge#stateDotClass}.
 *
 * @since 0.9.71
 */
public class SAMDebugTableTest {

    @Test
    public void rendersOneRowPerSession() {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] {"irc", "RAW", "dest-base64", "127.0.0.1", "control", "2026-10-17 12:00"});
        rows.add(new String[] {"srv", "STREAM", "other-dest", "?", "orphan", "2026-10-17 11:00"});
        String html = SAMBridge.buildSessionTable(rows);
        assertTrue(html, html.startsWith("<table id=samsessions>"));
        assertTrue(html, html.contains("<span class=\"appdot green\"></span>control"));
        assertTrue(html, html.contains("<span class=\"appdot red\"></span>orphan"));
        assertTrue(html, html.contains("<td>dest-base64</td>"));
        assertEquals(2, html.split("<tr><td>", -1).length - 1);
    }

    @Test
    public void escapesNickDestAndIp() {
        List<String[]> rows = Collections.singletonList(
                new String[] {"<b>x</b>", "RAW", "d&d", "<script>", "idle", "t</td>"});
        String html = SAMBridge.buildSessionTable(rows);
        assertTrue(html, html.contains("&lt;b&gt;x&lt;/b&gt;"));
        assertTrue(html, html.contains("d&amp;d"));
        assertTrue(html, html.contains("&lt;script&gt;"));
        assertTrue(html, html.contains("t&lt;/td&gt;"));
        // idle is the grey default - no color class
        assertTrue(html, html.contains("<span class=\"appdot \"></span>idle"));
    }

    @Test
    public void stateDotMapping() {
        assertEquals("green", SAMBridge.stateDotClass("control"));
        assertEquals("blue", SAMBridge.stateDotClass("forwarding"));
        assertEquals("blue", SAMBridge.stateDotClass("stolen"));
        assertEquals("red", SAMBridge.stateDotClass("orphan"));
        assertEquals("", SAMBridge.stateDotClass("idle"));
        assertEquals("", SAMBridge.stateDotClass("unknown"));
    }
}
