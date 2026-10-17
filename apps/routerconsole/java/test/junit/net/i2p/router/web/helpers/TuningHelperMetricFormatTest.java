package net.i2p.router.web.helpers;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Format tests for the tuner subsystem ring tooltips.
 *
 * <p>The tooltips used to list raw stat identifiers with no values; they now
 * render "statName: value" lines, and a metric with no data must render
 * nothing rather than a bare identifier or a misleading zero.
 *
 * @since 0.9.71+
 */
public class TuningHelperMetricFormatTest {

    @Test
    public void testMsFormat() {
        assertEquals("12ms", TuningHelper.formatMetricValue("ms", 12.4, 0));
        assertEquals("1.5s", TuningHelper.formatMetricValue("ms", 1500, 0));
        assertNull(TuningHelper.formatMetricValue("ms", 0, 0));
        assertNull(TuningHelper.formatMetricValue("ms", Double.NaN, 0));
    }

    @Test
    public void testPctFormat() {
        assertEquals("94%", TuningHelper.formatMetricValue("pct", 94.7, 0));
        assertEquals("0%", TuningHelper.formatMetricValue("pct", 0, 0));
    }

    @Test
    public void testPerMilleFormat() {
        // stream.rtxRatio records per-mille
        assertEquals("1.2%", TuningHelper.formatMetricValue("pct1000", 12, 0));
        assertEquals("0%", TuningHelper.formatMetricValue("pct1000", 0, 0));
    }

    @Test
    public void testCountFormat() {
        assertEquals("7/min", TuningHelper.formatMetricValue("count", 1.0, 7));
        assertEquals("0/min", TuningHelper.formatMetricValue("count", 1.0, 0));
    }

    @Test
    public void testNumFormat() {
        assertEquals("1234", TuningHelper.formatMetricValue("num", 1234.9, 0));
        assertEquals("0", TuningHelper.formatMetricValue("num", 0, 0));
        assertEquals("0", TuningHelper.formatMetricValue("num", Double.NaN, 0));
    }
}
