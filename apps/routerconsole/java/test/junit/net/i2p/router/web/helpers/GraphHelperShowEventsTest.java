package net.i2p.router.web.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Whether a performance graph plots event counts or elapsed time.
 *
 * <p>Time is the default, taken from {@code routerconsole.graphEvents} defaulting to false, and
 * events are a toggle. The toggle was not honoured in one direction: the enlarged-graph link
 * only emits {@code showEvents} when events are already on, so a time-mode link omitted the
 * parameter entirely, and the reader turned that absence into {@code true}. Opening the larger
 * view of a time graph silently switched it to event counts.
 *
 * <p>That is the exact symptom of a default that looks like it is events when it is not: the
 * main page and the inline image both respect the configured default, and only the link out of
 * them breaks it.
 *
 * @since 0.9.71+
 */
public class GraphHelperShowEventsTest {

    private static final boolean TIME = false;
    private static final boolean EVENTS = true;

    // ---- the regression ----

    /**
     * The bug: with time in force, the link omits the parameter, and an omitted parameter must
     * not be read as a request for events.
     */
    @Test
    public void anAbsentParameterKeepsTimeAsTime() {
        assertFalse("an omitted parameter must not turn a time graph into an events graph",
                    GraphHelper.resolveShowEvents(null, TIME));
        assertFalse("nor may an empty one",
                    GraphHelper.resolveShowEvents("", TIME));
    }

    @Test
    public void anAbsentParameterKeepsEventsAsEvents() {
        assertTrue("nor may it turn an events graph into a time graph",
                   GraphHelper.resolveShowEvents(null, EVENTS));
        assertTrue(GraphHelper.resolveShowEvents("", EVENTS));
    }

    // ---- explicit requests still work ----

    @Test
    public void explicitRequestsAreHonoured() {
        for (String yes : new String[]{"true", "1", "yes", "TRUE", "YES"}) {
            assertTrue(yes + " must request events", GraphHelper.resolveShowEvents(yes, TIME));
        }
        for (String no : new String[]{"false", "0", "no", "FALSE", "NO"}) {
            assertFalse(no + " must request time", GraphHelper.resolveShowEvents(no, EVENTS));
        }
    }

    /**
     * A value the parser does not recognise must not be allowed to flip the mode. The old
     * {@code !"false".equals(b)} treated anything unrecognised as a request for events.
     */
    @Test
    public void anUnrecognisedValueLeavesTheModeAlone() {
        for (String junk : new String[]{"maybe", "2", "on", "-1"}) {
            assertFalse(junk + " must not turn time into events",
                        GraphHelper.resolveShowEvents(junk, TIME));
            assertTrue(junk + " must not turn events into time",
                       GraphHelper.resolveShowEvents(junk, EVENTS));
        }
    }

    /**
     * Round trip, which is what actually happens in the browser: render a link in each mode,
     * then feed whatever the link emitted back in.
     *
     * <p>The link emits {@code showEvents=1} only when events are on, so the time case must
     * survive an absent parameter.
     */
    @Test
    public void theModeSurvivesALinkRoundTrip() {
        // time: the link omits the parameter
        String paramFromTimeLink = null;
        assertFalse("time mode must survive its own link",
                    GraphHelper.resolveShowEvents(paramFromTimeLink, TIME));

        // events: the link emits showEvents=1
        String paramFromEventsLink = "1";
        assertTrue("events mode must survive its own link",
                   GraphHelper.resolveShowEvents(paramFromEventsLink, EVENTS));
    }

    /**
     * And the image src, which emits the parameter unconditionally on both sites. Both must
     * round trip too, since that is the path the main page actually uses.
     */
    @Test
    public void theModeSurvivesAnImageRoundTrip() {
        assertFalse(GraphHelper.resolveShowEvents("false", TIME));
        assertTrue(GraphHelper.resolveShowEvents("true", EVENTS));
    }
}
