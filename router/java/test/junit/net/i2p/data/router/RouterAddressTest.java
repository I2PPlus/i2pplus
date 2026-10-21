package net.i2p.data.router;

import static org.junit.Assert.*;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.StructureTest;
import net.i2p.util.OrderedProperties;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Test harness for loading / storing RouterAddress objects.
 * Verifies proper handling of options, writes, equality, and string representation.
 */
public class RouterAddressTest extends StructureTest {

    @Override
    public DataStructure createDataStructure() throws DataFormatException {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        return new RouterAddress("Blah", options, 42);
    }

    @Override
    public DataStructure createStructureToRead() {
        return new RouterAddress();
    }

    @Test
    public void testNullOptionsAccepted() {
        RouterAddress addr = new RouterAddress("Blah", null, 42);
        assertEquals(42, addr.getCost());
        assertEquals("Blah", addr.getTransportStyle());
    }

    @Test
    public void testOptionsCopied() {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("host", "1.2.3.4");
        RouterAddress addr = new RouterAddress("Blah", options, 42);
        options.setProperty("host", "5.6.7.8");
        assertEquals("1.2.3.4", addr.getOption("host"));
        options.setProperty("port", "1234");
        assertNull(addr.getOption("port"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeCostThrows() {
        OrderedProperties options = new OrderedProperties();
        new RouterAddress("Blah", options, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCostOver255Throws() {
        OrderedProperties options = new OrderedProperties();
        new RouterAddress("Blah", options, 256);
    }

    @Test
    public void testBadWriteThrows() {
        RouterAddress addr = new RouterAddress();

        try {
            addr.writeBytes(new ByteArrayOutputStream());
            fail("Expected DataFormatException not thrown");
        } catch (DataFormatException e) {
            assertEquals("uninitialized", e.getMessage());
        } catch (IOException e) {
            fail("Unexpected IOException: " + e.getMessage());
        }
    }

    @Test
    public void testEqualsWithNullAndOtherObjects() {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        RouterAddress addr = new RouterAddress("Blah", options, 42);

        assertFalse(addr.equals(null));
        assertFalse(addr.equals("some string"));
    }

    @Test
    public void testToStringFormat() {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        RouterAddress addr = new RouterAddress("Blah", options, 42);

        String expected = "Blah: : * Hostname: localhost * Portnum: 1234 * Cost: 42";

        assertEquals(expected, addr.toString());
    }

    /**
     * The log form wraps onto a continuation line every four pairs so a full
     * SSU2 address stays readable in the log file.
     */
    @Test
    public void testToStringWrapsEveryFourPairs() {
        OrderedProperties options = new OrderedProperties();
        for (int i = 1; i <= 5; i++)
            options.setProperty("a" + i, Integer.toString(i));
        RouterAddress addr = new RouterAddress("Blah", options, 42);

        String expected = "Blah: : * A1: 1 * A2: 2 * A3: 3 * A4: 4\n * A5: 5 * Cost: 42";

        assertEquals(expected, addr.toString());
    }

    /**
     * The HTML form names the keys readably, keeps the ones the log form omits,
     * and pads the labels so the values line up in a monospace block.
     */
    @Test
    public void testToHtmlLabelsAndPadding() {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("host", "1.2.3.4");
        options.setProperty("port", "1234");
        options.setProperty("mtu", "1472");
        options.setProperty("caps", "BC");
        options.setProperty("pq", "1");
        options.setProperty("ih0", "abc");
        options.setProperty("itag0", "1716583659");
        options.setProperty("iexp0", "1792557344");
        RouterAddress addr = new RouterAddress("SSU2", options, 5);

        String expected = "<span class=key>SSU2        </span> <span class=value>1.2.3.4:1234</span>\n" +
                          "<span class=key>Capacity    </span> <span class=value>BC</span>\n" +
                          "<span class=key>Cost        </span> <span class=value>5</span>\n" +
                          "<span class=key>Intro Exp 0 </span> <span class=value>1792557344</span>\n" +
                          "<span class=key>Intro Hash 0</span> <span class=value>abc</span>\n" +
                          "<span class=key>Intro Tag 0 </span> <span class=value>1716583659</span>\n" +
                          "<span class=key>MTU         </span> <span class=value>1472</span>\n" +
                          "<span class=key>PQ Version  </span> <span class=value>1</span>\n";

        assertEquals(expected, addr.toHtml());
    }

    /**
     * The HTML form orders the lines by the name the reader sees, not by the
     * option key - "key" sorts after every "i*" key but its name belongs with
     * the other introducer rows.
     */
    @Test
    public void testToHtmlSortsByLabel() {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("key", "abc");
        options.setProperty("itag0", "1716583659");
        options.setProperty("ihost0", "1.2.3.4");
        options.setProperty("iexp0", "1792557344");
        RouterAddress addr = new RouterAddress("SSU2", options, 5);

        String expected = "<span class=key>SSU2        </span> <span class=value>:</span>\n" +
                          "<span class=key>Cost        </span> <span class=value>5</span>\n" +
                          "<span class=key>Intro Exp 0 </span> <span class=value>1792557344</span>\n" +
                          "<span class=key>Intro Host 0</span> <span class=value>1.2.3.4</span>\n" +
                          "<span class=key>Intro Key   </span> <span class=value>abc</span>\n" +
                          "<span class=key>Intro Tag 0 </span> <span class=value>1716583659</span>\n";

        assertEquals(expected, addr.toHtml());
    }

    /**
     * An unknown key falls back to its capitalized name, and values are escaped
     * before they reach the console.
     */
    @Test
    public void testToHtmlEscapesAndFallsBack() {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("weird", "<a> & \"b\"");
        RouterAddress addr = new RouterAddress("Blah", options, 42);

        String expected = "<span class=key>Blah        </span> <span class=value>:</span>\n" +
                          "<span class=key>Cost        </span> <span class=value>42</span>\n" +
                          "<span class=key>Weird       </span> <span class=value>&lt;a&gt; &amp; &quot;b&quot;</span>\n";

        assertEquals(expected, addr.toHtml());
    }
}
