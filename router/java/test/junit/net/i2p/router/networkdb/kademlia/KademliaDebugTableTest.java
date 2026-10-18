package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the pure bucket-table builder of
 * {@link KademliaNetworkDatabaseFacade#buildBucketTable}: one row per bucket
 * with index, bit range, entry count, and fill percentage rounded against
 * the bucket capacity. The entry renderer and translator are injected, so
 * the test supplies a chip renderer and the identity translation.
 *
 * @since 0.9.71
 */
public class KademliaDebugTableTest {

    /** Stand-in for commSystem().renderPeerHTML, keeping the assertions readable */
    private static final Function<String, String> CHIP =
        k -> "<span class=routerHash><a href=\"netdb?r=" + k + "\" title=\"" + k + "\">" +
             (k.length() > 6 ? k.substring(0, 6) : k) + "</a></span>";

    private static final Function<String, String> TR = s -> s;

    @Test
    public void rendersOneRowPerBucket() {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] {"1", "2", "0", "127", "18", "abc"});
        rows.add(new String[] {"2", "2", "128", "255", "24", "def,ghi"});
        String html = KademliaNetworkDatabaseFacade.buildBucketTable(rows, 24, CHIP, TR);
        assertTrue(html, html.startsWith("<table id=dhtbuckets>"));
        assertTrue(html, html.contains("<th>Key range</th>"));
        assertTrue(html, html.contains("<td>1</td>"));
        assertTrue(html, html.contains("<td>2</td>"));
        assertTrue(html, html.contains("title=\"XOR distance from us, bits 0-127 (closest)\">0-127</td>"));
        assertTrue(html, html.contains("(furthest)\">128-255</td>"));
        assertTrue(html, html.contains("<details><summary>18</summary><span class=routerHash><a href=\"netdb?r=abc\" title=\"abc\">abc</a></span></details>"));
        assertTrue(html, html.contains("<summary>24</summary>"));
        assertTrue(html, html.contains("<span class=percentBarInner style=\"width:75%\"><span class=percentBarText>75%"));   // 18/24
        assertTrue(html, html.contains("<span class=percentBarInner style=\"width:100%\"><span class=percentBarText>100%"));  // 24/24
        assertEquals(2, html.split("<tr><td>", -1).length - 1);
    }

    @Test
    public void emptyRowsStillRenderHeader() {
        String html = KademliaNetworkDatabaseFacade.buildBucketTable(new ArrayList<String[]>(), 8, CHIP, TR);
        assertTrue(html, html.contains("<th>Fill</th>"));
        assertTrue(html, html.endsWith("</tbody></table>"));
    }

    @Test
    public void overfullBucketExceedsOneHundredPercent() {
        List<String[]> rows = Collections.singletonList(new String[] {"1", "1", "0", "255", "9", "abc"});
        String html = KademliaNetworkDatabaseFacade.buildBucketTable(rows, 8, CHIP, TR);
        // bar width capped at 100%, text shows the real fill
        assertTrue(html, html.contains("<span class=percentBarInner style=\"width:100%\"><span class=percentBarText>113%"));  // 9/8
        // single bucket gets no closest/furthest marker
        assertTrue(html, !html.contains("closest"));
    }

    @Test
    public void emptyBucketShowsPlainZero() {
        List<String[]> rows = Collections.singletonList(new String[] {"1", "1", "0", "255", "0", ""});
        String html = KademliaNetworkDatabaseFacade.buildBucketTable(rows, 8, CHIP, TR);
        assertTrue(html, html.contains("<td>0</td>"));
        assertTrue(html, !html.contains("<details>"));
    }

    @Test
    public void entryLinksAreSortedAndRendered() {
        String html = KademliaNetworkDatabaseFacade.entryLinks("zzzzzzzzzz,aaaaaa", CHIP);
        assertTrue(html, html.startsWith("<span class=routerHash><a href=\"netdb?r=aaaaaa\" title=\"aaaaaa\">aaaaaa</a></span>"));
        assertTrue(html, html.contains("<a href=\"netdb?r=zzzzzzzzzz\" title=\"zzzzzzzzzz\">zzzzzz</a>"));
        assertEquals("—", KademliaNetworkDatabaseFacade.entryLinks("", CHIP));
        assertEquals("—", KademliaNetworkDatabaseFacade.entryLinks(null, CHIP));
    }
}
