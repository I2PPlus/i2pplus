package net.i2p.i2ptunnel.access;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

/**
 * Which attempt histories are widened so a recorder rule can be met.
 *
 * <p>A recorder is tested over one set of trackers - the destinations no
 * access-list element names. A destination named by a {@code file} or
 * {@code explicit} element lands in the known set and is judged by the rule that
 * names it and nothing else, so widening its history to the definition's widest
 * recorder buys nothing and costs a ring per list entry: a 60,000-entry deny
 * file beside a {@code 1000/60 recorder} allocates some 480 MB of rings while
 * the filter is still being built, and the router GC-stalls or dies at load.
 *
 * <p>No tunnel is needed; the filter is built over a definition written here and
 * its two tracker maps are read reflectively.
 *
 * @since 0.9.72+
 */
public class AccessFilterRetentionScopeTest {

    /** The admission rule of the fixture definition. */
    private static final Threshold ADMISSION = new Threshold(15, 5);

    /** The recorder rule of the fixture definition, far wider than any element. */
    private static final Threshold WIDE_RECORDER = new Threshold(1000, 60);

    /** Fewest timestamps a counter retains, mirrored from the counter's own floor. */
    private static final int MIN_RETAINED = 64;

    /** The destination the fixture's element names. */
    private static final Hash NAMED = Hash.FAKE_HASH;

    /**
     * A fixture group for the recorder widening; it holds no state.
     *
     * @since 0.9.72+
     */
    public AccessFilterRetentionScopeTest() {}

    /**
     * An element naming one destination, and remembering the recorder width the
     * filter handed it when it was read.
     *
     * @since 0.9.72+
     */
    private static class NamingElement extends FilterDefinitionElement {

        /** The widest recorder the filter passed in, -1 until the element is read. */
        private int _recorderConnections = -1;

        /**
         * Builds the element.
         *
         * @param threshold the rule applied to the destination it names
         */
        NamingElement(Threshold threshold) {
            super(threshold);
        }

        /**
         * Names one destination and records what the filter asked it to retain.
         *
         * @param map the destination-to-tracker map to add to
         * @param recorderConnections the widest recorder rule of the definition
         */
        @Override
        void update(Map<Hash, DestTracker> map, int recorderConnections) {
            _recorderConnections = recorderConnections;
            map.put(NAMED, new DestTracker(NAMED, getThreshold(), recorderConnections));
        }
    }

    /**
     * A destination no element names, so the filter builds its tracker itself.
     *
     * @return a fresh destination
     */
    private static Destination destination() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            I2PClientFactory.createClient().createDestination(out);
            return Destination.create(new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            throw new RuntimeException("dest gen failed", e);
        }
    }

    /**
     * @param element the one rule of the definition
     * @return a filter over that definition, its timers not started
     * @throws Exception if the filter cannot read its definition
     */
    private static AccessFilter filter(FilterDefinitionElement element) throws Exception {
        File recorder = new File(System.getProperty("java.io.tmpdir") +
                                 "/opencode/af-scope-" + System.nanoTime(), "breach.txt");
        FilterDefinition def = new FilterDefinition(ADMISSION,
                new FilterDefinitionElement[] {element},
                new Recorder[] {new Recorder(recorder, WIDE_RECORDER)});
        return new AccessFilter(I2PAppContext.getGlobalContext(), def);
    }

    /**
     * A destination named by an element is never tested by a recorder, so its
     * history holds only what its own rule asks for: one timestamp for a deny
     * entry. Pre-fix the filter passed the recorder's 1000 to every element, so
     * each deny entry allocated a ring for attempts no rule would ever read.
     *
     * @throws Exception if the filter cannot be built or its maps read
     */
    @Test
    public void aNamedDestinationKeepsOnlyItsOwnRule() throws Exception {
        NamingElement element = new NamingElement(Threshold.DENY);
        AccessFilter filter = filter(element);
        assertEquals("the filter widened a named destination to the recorder's count",
                     0, element._recorderConnections);
        DestTracker tracker = map(filter, "knownDests").get(NAMED);
        assertEquals("a deny entry allocated the recorder's history",
                     1, tracker.getCounter().capacity());
    }

    /**
     * The same for a throttle entry: its own rule of 15 in 5s is narrower than the
     * counter's floor, so the floor is what it keeps - 64 timestamps, not the
     * recorder's 1000. The floor and the widening are pinned beside it, so the
     * case cannot pass because the counter stopped sizing itself at all.
     *
     * @throws Exception if the filter cannot be built or its maps read
     */
    @Test
    public void aThrottledDestinationKeepsTheFloorNotTheRecorder() throws Exception {
        NamingElement element = new NamingElement(ADMISSION);
        AccessFilter filter = filter(element);
        assertEquals("the filter widened a named destination to the recorder's count",
                     0, element._recorderConnections);
        DestTracker tracker = map(filter, "knownDests").get(NAMED);
        assertEquals(MIN_RETAINED, tracker.getCounter().capacity());
        assertEquals(MIN_RETAINED, new DestTracker(NAMED, ADMISSION, 0).getCounter().capacity());
        assertEquals(1000, new DestTracker(NAMED, ADMISSION, 1000).getCounter().capacity());
    }

    /**
     * The widening still happens where a recorder actually looks: a destination no
     * element names keeps enough attempts for the widest recorder rule, or that
     * rule could never breach and the breach file would stay empty.
     *
     * @throws Exception if the filter cannot be built or its maps read
     */
    @Test
    public void anUnnamedDestinationKeepsTheRecordersWidestRule() throws Exception {
        AccessFilter filter = filter(new NamingElement(Threshold.DENY));
        Destination d = destination();
        filter.allowDestination(d);
        DestTracker tracker = map(filter, "unknownDests").get(d.getHash());
        assertEquals("an unlisted destination lost the history its recorder needs",
                     1000, tracker.getCounter().capacity());
    }

    /**
     * One of the filter's two tracker maps.
     *
     * @param filter the filter to read
     * @param field knownDests or unknownDests
     * @return the live map
     * @throws Exception if the field cannot be read
     */
    @SuppressWarnings("unchecked")
    private static Map<Hash, DestTracker> map(AccessFilter filter, String field) throws Exception {
        Field f = AccessFilter.class.getDeclaredField(field);
        f.setAccessible(true);
        return (Map<Hash, DestTracker>) f.get(filter);
    }
}
