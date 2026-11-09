package net.i2p.client.streaming.impl;

import java.util.Properties;

import junit.framework.TestCase;

/**
 * Tests the log formatter for the streaming options: that a change report names
 * only what changed, that the numeric sentinels are rendered as prose, and that
 * the continuation lines stay inside the log's line-length budget.
 */
public class ConnectionOptionsChangeTest extends TestCase {

    private ConnectionOptions _opts;

    protected void setUp() throws Exception {
        _opts = new ConnectionOptions();
    }

    /**
     * A copy that is not changed produces no diff at all, which is what keeps the
     * "nothing changed" log line to one line.
     */
    public void testNoChangesIsEmpty() {
        assertEquals("", _opts.describeChanges(new ConnectionOptions(_opts)));
    }

    /**
     * A changed option is reported as "label: old -&gt; new", and the options that
     * did not change are not repeated.
     */
    public void testOnlyChangedOptionsAreReported() {
        ConnectionOptions next = new ConnectionOptions(_opts);
        next.setSendAckDelay(10);
        next.setMaxResends(_opts.getMaxResends() + 5);
        String changes = _opts.describeChanges(next);
        assertTrue("[" + changes + "]", changes.contains("Ack delay: " + _opts.getSendAckDelay() +
                                                        "ms -> 10ms"));
        assertTrue("[" + changes + "]",
                   changes.contains("Max resends: " + _opts.getMaxResends() + " -> " +
                                    (_opts.getMaxResends() + 5)));
        assertFalse(changes.contains("RTT:"));
        assertFalse(changes.contains("Read timeout:"));
        assertEquals(2, countPairs(changes));
    }

    /**
     * The sentinels are named in prose: a -1 read timeout never gives up, a 0
     * window cap is the Tuner's value, and no connection limit is unlimited.
     */
    public void testSentinelsAreProse() {
        ConnectionOptions off = new ConnectionOptions();
        off.setInactivityTimeout(0);
        String full = off.toString();
        assertTrue("[" + full + "]", full.contains("Read timeout: never"));
        assertTrue("[" + full + "]", full.contains("Max window size: Tuner default"));
        assertTrue("[" + full + "]", full.contains("Max connections: unlimited"));
        assertTrue("[" + full + "]", full.contains("Inactivity timeout: disabled"));
        ConnectionOptions next = new ConnectionOptions();
        next.setReadTimeout(30000);
        next.setMaxWindowSize(4000);
        String changes = _opts.describeChanges(next);
        assertTrue("[" + changes + "]", changes.contains("Read timeout: never -> 30000ms"));
        assertTrue("[" + changes + "]",
                   changes.contains("Max window size: Tuner default -> 4000B"));
    }

    /**
     * A change small enough to round away in a scaled display is still reported,
     * because the diff compares the configured values and prints exact ones.
     */
    public void testChangeBelowDisplayRoundingIsReported() {
        ConnectionOptions next = new ConnectionOptions(_opts);
        next.setStallGiveupMs(_opts.getStallGiveupMs() + 1);
        String changes = _opts.describeChanges(next);
        assertTrue("[" + changes + "]",
                   changes.contains("Stall give-up: " + _opts.getStallGiveupMs() + "ms -> " +
                                    (_opts.getStallGiveupMs() + 1) + "ms"));
    }

    /**
     * A capped connection rate names only the periods that are capped, so a
     * per-minute limit does not print two zeroes.
     */
    public void testConnectionLimitsNameTheCappedPeriods() {
        Properties props = new Properties();
        props.setProperty(ConnectionOptions.PROP_MAX_CONNS_MIN, "300");
        props.setProperty(ConnectionOptions.PROP_MAX_CONNS_DAY, "100000");
        ConnectionOptions capped = new ConnectionOptions(props);
        String full = capped.toString();
        assertTrue("[" + full + "]", full.contains("Max connections: 300/min, 100000/day"));
        assertFalse(full.contains("/hour"));
    }

    /**
     * Every measured value carries its unit, and no pair is written key=value.
     */
    public void testValuesCarryUnits() {
        String full = _opts.toString();
        assertTrue("[" + full + "]", full.contains("Max message size: " +
                                                   formatBytes(_opts.getMaxMessageSize())));
        assertTrue("[" + full + "]", full.contains("Inbound buffer: " +
                                                   formatBytes(_opts.getInboundBufferSize())));
        assertFalse("[" + full + "]", full.contains("="));
    }

    /**
     * The full dump is wrapped: no emitted line runs past the line-length budget
     * and no line carries more pairs than the per-line cap.
     */
    public void testFullDumpIsWrapped() {
        String full = _opts.toString();
        String[] lines = full.split("\n");
        assertTrue(full.startsWith("\n* "));
        int linesSeen = 0;
        for (String line : lines) {
            if (line.isEmpty()) {continue;}
            linesSeen++;
            assertTrue("line too long (" + line.length() + "): " + line, line.length() <= 100);
            assertTrue("too many pairs: " + line, countPairs(line) <= 8);
        }
        assertTrue("lines: " + linesSeen, linesSeen > 1);
    }

    /**
     * Formats a byte count the way the formatter does, so the assertions above do
     * not hard-code the value twice.
     *
     * @param bytes the count to format
     * @return the value with its unit, as the formatter writes it
     */
    private static String formatBytes(long bytes) {
        return bytes + "B";
    }

    /**
     * Counts the " * "-separated key: value pairs on one rendered line.
     *
     * @param line one rendered continuation line
     * @return how many pairs it carries, 1 or more
     */
    private static int countPairs(String line) {
        String body = line.startsWith("* ") ? line.substring(2) : line;
        int count = 1;
        for (int i = body.indexOf(" * "); i >= 0; i = body.indexOf(" * ", i + 3)) {count++;}
        return count;
    }
}
