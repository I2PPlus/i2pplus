package net.i2p.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Properties;
import org.junit.Test;

/**
 * LeaseSet2.setOptions() must replace the previous options, and null must
 * clear them instead of throwing NullPointerException after the clear.
 *
 * @since 0.9.71+
 */
public class LeaseSet2OptionsTest {

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2)
            p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    @Test
    public void testSetOptionsCopies() {
        LeaseSet2 ls2 = new LeaseSet2();
        Properties p = props("foo", "bar");
        ls2.setOptions(p);
        assertEquals("bar", ls2.getOption("foo"));
        // a copy, not the caller's Properties
        p.setProperty("foo", "changed");
        assertEquals("bar", ls2.getOption("foo"));
    }

    @Test
    public void testSetOptionsReplaces() {
        LeaseSet2 ls2 = new LeaseSet2();
        ls2.setOptions(props("foo", "bar", "baz", "1"));
        ls2.setOptions(props("baz", "2"));
        assertNull(ls2.getOption("foo"));
        assertEquals("2", ls2.getOption("baz"));
    }

    @Test
    public void testSetNullOptionsClears() {
        LeaseSet2 ls2 = new LeaseSet2();
        ls2.setOptions(props("foo", "bar"));
        ls2.setOptions(null);
        assertNull("getOptions() must be null after clearing", ls2.getOptions());
        assertNull(ls2.getOption("foo"));
    }

    @Test
    public void testSetNullOptionsOnFreshLeaseSet() {
        LeaseSet2 ls2 = new LeaseSet2();
        assertNull(ls2.getOptions());
        ls2.setOptions(null);
        assertNull(ls2.getOptions());
    }
}
