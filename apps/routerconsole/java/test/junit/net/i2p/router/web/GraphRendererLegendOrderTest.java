package net.i2p.router.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;
import org.rrd4j.data.Variable;

/**
 * Tests the order and the wiring of the legend's summary block.
 *
 * <p>The console has always read {@code Min: … Max: … Avg: … Now: …}, and the graphs on
 * {@code /graphs} kept that order, but the block a combined graph prints for its second
 * series led with the maximum. One page therefore read two ways, and nothing in rrd4j asks
 * for either order, so the only defence is to state the convention in one place and pin it.
 *
 * <p>Everything under test is static: the field list and the variable each field reads. The
 * labels are checked as msgids, untranslated, so the test does not depend on the console's
 * language.
 *
 * @since 0.9.72+
 */
public class GraphRendererLegendOrderTest {

    /** The house order, spelled out here so a change to the production list fails loudly. */
    private static final String[] EXPECTED_KEYS = { "min", "max", "avg", "last" };

    /** The labels beside those keys, as the translation bundle knows them. */
    private static final String[] EXPECTED_LABELS = { "Min", "Max", "Avg", "Now" };

    ///////////// order

    /** The summary block reads min, max, average, now, in that order, everywhere. */
    @Test
    public void testFieldsAreInHouseOrder() {
        assertEquals(EXPECTED_KEYS.length, GraphRenderer.SUMMARY_FIELDS.length);
        for (int i = 0; i < EXPECTED_KEYS.length; i++) {
            assertEquals(EXPECTED_KEYS[i], GraphRenderer.SUMMARY_FIELDS[i].key);
            assertEquals(EXPECTED_LABELS[i], GraphRenderer.SUMMARY_FIELDS[i].label);
        }
    }

    /**
     * The block ends with the current value, so a caller that ends the legend line on the
     * last field puts {@code Now} there, as every graph on {@code /graphs} does.
     */
    @Test
    public void testCurrentValueIsLast() {
        assertEquals("last", GraphRenderer.SUMMARY_FIELDS[GraphRenderer.SUMMARY_FIELDS.length - 1].key);
        assertEquals("Now", GraphRenderer.SUMMARY_FIELDS[GraphRenderer.SUMMARY_FIELDS.length - 1].label);
    }

    ///////////// ids

    /**
     * The ids have to be distinct, because a graph definition cannot declare one twice and
     * the primary's block is declared alongside an extra series' suffixed one.
     */
    @Test
    public void testKeysAreDistinct() {
        Set<String> seen = new HashSet<>();
        for (GraphRenderer.SummaryField field : GraphRenderer.SUMMARY_FIELDS) {
            assertTrue("duplicate summary id: " + field.key, seen.add(field.key));
        }
    }

    ///////////// variables

    /** Each field reads the value its label claims, not the one the block used to lead with. */
    @Test
    public void testFieldsReadTheirOwnVariable() {
        assertSame(Variable.MIN.class, GraphRenderer.summaryVariable("min").getClass());
        assertSame(Variable.MAX.class, GraphRenderer.summaryVariable("max").getClass());
        assertSame(Variable.AVERAGE.class, GraphRenderer.summaryVariable("avg").getClass());
        assertSame(Variable.LAST.class, GraphRenderer.summaryVariable("last").getClass());
    }

    /**
     * An id that is not a field is a programming error, and reading it as the last value
     * would draw a wrong number on a graph rather than say what is missing.
     */
    @Test
    public void testUnknownKeyIsRejected() {
        try {
            GraphRenderer.summaryVariable("total");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("total"));
            return;
        }
        fail("summaryVariable accepted a key that is not a field");
    }
}
