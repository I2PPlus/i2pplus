package i2p.susi.webmail;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.Test;

/**
 * Pins that a parameter with no value is not a checked item.
 *
 * <p>The list page ticks a message by sending checkN=1, and the scan walked the
 * parameter names and compared each value. A name can be listed and still have no
 * value: the XSS filter drops a value that fails its character whitelist and
 * returns null for it, while the name stays in the enumeration. Comparing the other
 * way round made that null a NullPointerException, and the mailbox died with a 500
 * over one odd field.
 *
 * @since 0.9.72+
 */
public class CheckedItemsTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public CheckedItemsTest() {}

    /**
     * A parameter reader over a fixed set of values.
     *
     * @param values the value of each parameter name, null for a name that has none
     * @return the names in insertion order, and the value reader
     */
    private static Function<String, String> reader(Map<String, String[]> values) {
        return name -> {
            String[] vals = values.get(name);
            return vals == null || vals.length == 0 ? null : vals[0];
        };
    }

    /**
     * The defect: a check parameter whose value the filter dropped. The name is
     * still enumerated, and the comparison dereferenced the null.
     */
    @Test
    public void testACheckWithNoValueIsNotChecked() {
        Map<String, String[]> values = new HashMap<>();
        values.put("check1", null);
        Enumeration<String> names = Collections.enumeration(Arrays.asList("check1"));
        assertEquals(Collections.emptyList(), WebMail.checkedItems(names, reader(values)));

        // and a name that is enumerated but absent from the parameters altogether
        values.put("check2", new String[0]);
        names = Collections.enumeration(Arrays.asList("check1", "check2"));
        assertEquals(Collections.emptyList(), WebMail.checkedItems(names, reader(values)));
    }

    /**
     * A checked item is still found, and a rejected neighbour does not hide it -
     * this is the request that used to crash the page.
     */
    @Test
    public void testACheckedItemSurvivesARejectedNeighbour() {
        Map<String, String[]> values = new HashMap<>();
        values.put("check3", new String[] {"1"});
        values.put("check4", new String[] {"<b>"});
        values.put("check5", new String[] {"1"});
        Enumeration<String> names =
            Collections.enumeration(Arrays.asList("check3", "check4", "check5"));
        assertEquals(Arrays.asList("3", "5"), WebMail.checkedItems(names, reader(values)));
    }

    /**
     * Only checkN parameters count, and only when the value is exactly 1.
     */
    @Test
    public void testOnlyCheckParametersSetToOneCount() {
        Map<String, String[]> values = new HashMap<>();
        values.put("folder", new String[] {"1"});
        values.put("check1", new String[] {"0"});
        values.put("check2", new String[] {"11"});
        values.put("check6", new String[] {"1"});
        Enumeration<String> names =
            Collections.enumeration(Arrays.asList("folder", "check1", "check2", "check6"));
        assertEquals(Collections.singletonList("6"), WebMail.checkedItems(names, reader(values)));
    }

    /**
     * An empty enumeration is a request with no parameters at all, and answers an
     * empty list rather than null.
     */
    @Test
    public void testNoParametersIsAnEmptyList() {
        Enumeration<String> none = Collections.enumeration(Collections.<String>emptyList());
        List<String> items = WebMail.checkedItems(none, name -> null);
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }
}
