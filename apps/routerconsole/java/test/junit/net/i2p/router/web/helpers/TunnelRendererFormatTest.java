package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import org.junit.Test;

/**
 * Differential test for the number formatting and expiry bar in {@link TunnelRenderer}.
 *
 * <p>Both are user-visible, and both used to serialize on a single {@code static synchronized}
 * {@link java.text.DecimalFormat} that was taken twice per rendered row. The references below are
 * verbatim copies of the pre-optimization code, so the per-thread form has to agree byte for byte.
 */
public class TunnelRendererFormatTest {

    /** Values spanning the ranges a tunnel row shows: sub-unit, KB, MB and percentages. */
    private static final double[] VALUES = {
        0, 0.0, 0.004, 0.005, 0.5, 0.995, 1, 1.5, 2, 2.5, 9.995, 10, 99.5, 100, 512,
        1023.994, 1024, 1025, 2047.5, 4096, 9999999, 1024.5 * 1024, 0.0000001
    };

    /** Expiry values bracketing the 1m / 3m / 5m class boundaries and the 10 minute cap. */
    private static final long[] EXPIRIES = {
        0, 1, 1000, 59_999, 60_000, 60_001, 179_999, 180_000, 299_999, 300_000, 599_999,
        600_000, 600_001, 900_000, 3_600_000, 86_400_000L
    };

    /** Calls the private static {@code fmt}/{@code fmt0}/{@code fmtTrim} helpers. */
    private static String invoke(String helper, double val) throws ReflectiveOperationException {
        Method m = TunnelRenderer.class.getDeclaredMethod(helper, StringBuilder.class, double.class);
        m.setAccessible(true);
        StringBuilder buf = new StringBuilder();
        m.invoke(null, buf, val);
        return buf.toString();
    }

    /** The pre-optimization formatter, including its rounding mode. */
    private static String referenceFmt(double val) {
        java.text.DecimalFormat df = new java.text.DecimalFormat("#0.00");
        return df.format(val);
    }

    /** The pre-optimization zero-decimal formatter, including its rounding mode. */
    private static String referenceFmt0(double val) {
        java.text.DecimalFormat df = new java.text.DecimalFormat("#0");
        df.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return df.format(val);
    }

    /** Two-decimal formatting must be unchanged, and the monitor must no longer be involved. */
    @Test
    public void testFmtMatchesReference() throws ReflectiveOperationException {
        for (double val : VALUES) {
            assertEquals(Double.toString(val), referenceFmt(val), invoke("fmt", val));
        }
    }

    /** Zero-decimal formatting must be unchanged, rounding mode included. */
    @Test
    public void testFmt0MatchesReference() throws ReflectiveOperationException {
        for (double val : VALUES) {
            assertEquals(Double.toString(val), referenceFmt0(val), invoke("fmt0", val));
        }
    }

    /** The trimmed form must drop every ".00", exactly as the old {@code replace} chain did. */
    @Test
    public void testFmtTrimMatchesReference() throws ReflectiveOperationException {
        for (double val : VALUES) {
            assertEquals(Double.toString(val), referenceFmt(val).replace(".00", ""), invoke("fmtTrim", val));
        }
    }

    /** The expiry bar markup must be unchanged across every class boundary. */
    @Test
    public void testRenderExpiryBarMatchesReference() throws ReflectiveOperationException {
        Method m = TunnelRenderer.class.getDeclaredMethod("renderExpiryBar", StringBuilder.class, long.class);
        m.setAccessible(true);
        for (long timeLeft : EXPIRIES) {
            StringBuilder buf = new StringBuilder("pre");
            m.invoke(new TunnelRenderer(null), buf, timeLeft);
            assertEquals(Long.toString(timeLeft), "pre" + referenceRenderExpiryBar(timeLeft), buf.toString());
        }
    }

    /** The pre-optimization expiry bar, verbatim. */
    private static String referenceRenderExpiryBar(long timeLeft) {
        if (timeLeft <= 0) {timeLeft = 0;}
        boolean fiveLeft = timeLeft < 5*60*1000;
        boolean threeLeft = timeLeft < 3*60*1000;
        boolean oneLeft = timeLeft < 60*1000;
        String expiry = fiveLeft ? " 5m" : threeLeft ? " 3m" : oneLeft ? " 1m" : "";
        int percent = (int) Math.min(100, timeLeft * 100.0 / 600000);
        String timeStr = net.i2p.data.DataHelper.formatDuration2(timeLeft);
        return "<span class=\"percentBarOuter" + expiry + "\">" +
               "<span class=percentBarInner style=width:" + percent + "%>" +
               "<span class=percentBarText>" + timeStr + "</span></span></span>";
    }
}
