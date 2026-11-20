package net.i2p.i2ptunnel.web;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.i2ptunnel.TunnelController;

import org.junit.Test;

/**
 * The order a bulk tunnel restart touches its tunnels in, and what it counts as
 * restarted.
 *
 * <p>A stop drops every stream running over that tunnel. When the pass stopped
 * every tunnel first and started them afterwards, "Restart all" took down every
 * live set of streams on the router at once and then rebuilt a dozen tunnels in
 * parallel, where the old per-tunnel pass kept at most one tunnel down. Moving
 * the delay off the request thread is what made that shape possible: every stop
 * was committed before the single sleep.
 *
 * <p>Separately, the pass counted every startTunnelBackground() call that
 * returned, including the ones that started nothing - a start superseded by a
 * stop from another console tab, or a tunnel that was no longer stopped - so the
 * page could report "Restarted 12 running server tunnels" having started none.
 *
 * <p>No router and no tunnels: the controllers are stubs, and the delay is a
 * parameter, so the pass runs in milliseconds and the order the tunnels are
 * touched in is what is being asserted.
 *
 * @since 0.9.72+
 */
public class IndexBeanRestartPassTest {

    /** How long the pass waits between stopping and starting one tunnel. */
    private static final long DELAY_MS = 1;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public IndexBeanRestartPassTest() {}

    /**
     * One tunnel at a time: the pass must not touch a second tunnel until the
     * first is started again, so at most one tunnel is ever down.
     */
    @Test
    public void testThePassFinishesOneTunnelBeforeTouchingTheNext() {
        List<String> events = new ArrayList<>();
        TunnelController first = controller("first", events, true);
        TunnelController second = controller("second", events, true);

        new IndexBean().restartPass(Arrays.asList(first, second), DELAY_MS, msg -> {});

        assertEquals("the pass stopped both tunnels before starting either",
                     Arrays.asList("stop first", "start first", "stop second", "start second"),
                     events);
    }

    /**
     * Only a start that was handed off counts. A tunnel whose start was
     * superseded, or which was no longer stopped, starts nothing, and the count
     * the page turns into its closing line must say so.
     */
    @Test
    public void testOnlyAStartThatWasHandedOffIsCounted() {
        TunnelController started = controller("started", new ArrayList<>(), true);
        TunnelController skipped = controller("skipped", new ArrayList<>(), false);

        int count = new IndexBean().restartPass(Arrays.asList(started, skipped), DELAY_MS,
                                                msg -> {});

        assertEquals("a start that started nothing was counted", 1, count);
    }

    /**
     * A pass that starts nothing reports none, rather than one per tunnel it
     * walked over.
     */
    @Test
    public void testAPassThatStartsNothingCountsNone() {
        TunnelController first = controller("first", new ArrayList<>(), false);
        TunnelController second = controller("second", new ArrayList<>(), false);

        int count = new IndexBean().restartPass(Arrays.asList(first, second), DELAY_MS, msg -> {});

        assertEquals(0, count);
    }

    /**
     * The start is queued against the generation read after this pass's own stop:
     * the stop the pass made itself must not supersede the start it then queues,
     * while a second stop from anywhere else still does.
     */
    @Test
    public void testTheStartIsQueuedAgainstTheGenerationAfterTheStop() {
        AtomicLong generation = new AtomicLong(5);
        TunnelController tc = mock(TunnelController.class);
        when(tc.getName()).thenReturn("gen");
        doAnswer(inv -> Long.valueOf(generation.incrementAndGet())).when(tc).stopTunnel();
        when(tc.getLifecycleGeneration()).thenAnswer(inv -> Long.valueOf(generation.get()));
        when(tc.startTunnelBackground(anyLong())).thenReturn(true);

        new IndexBean().restartPass(Arrays.asList(tc), DELAY_MS, msg -> {});

        verify(tc).startTunnelBackground(6L);
    }

    /**
     * A tunnel whose stop throws is reported to the operator, by name, and the
     * pass carries on with the tunnels after it rather than abandoning them.
     */
    @Test
    public void testAFailedStopIsReportedAndThePassCarriesOn() {
        List<String> events = new ArrayList<>();
        TunnelController broken = controller("broken", events, true);
        doAnswer(inv -> {
            events.add("stop broken");
            throw new RuntimeException("stop refused");
        }).when(broken).stopTunnel();
        TunnelController next = controller("next", events, true);
        List<String> lines = new ArrayList<>();

        int count = new IndexBean().restartPass(Arrays.asList(broken, next), DELAY_MS, lines::add);

        assertEquals("the pass stopped at the tunnel whose stop failed",
                     Arrays.asList("stop broken", "start broken", "stop next", "start next"),
                     events);
        assertEquals(2, count);
        boolean reported = false;
        for (String line : lines) {
            if (line.contains("broken") && line.contains("\u2716")) {
                reported = true;
                break;
            }
        }
        assertTrue("the failed stop was never reported to the operator: " + lines, reported);
    }

    /**
     * A tunnel for the pass, recording the two lifecycle calls that matter.
     *
     * @param name the tunnel's name, as the pass reports it
     * @param events where this tunnel's stops and starts are recorded
     * @param handedOff true if a start is accepted, false if it starts nothing
     * @return the controller to hand to the pass
     */
    private static TunnelController controller(String name, List<String> events,
                                               boolean handedOff) {
        TunnelController tc = mock(TunnelController.class);
        when(tc.getName()).thenReturn(name);
        when(tc.getLifecycleGeneration()).thenReturn(1L);
        doAnswer(inv -> {
            events.add("stop " + name);
            return null;
        }).when(tc).stopTunnel();
        doAnswer(inv -> {
            events.add("start " + name);
            return Boolean.valueOf(handedOff);
        }).when(tc).startTunnelBackground(anyLong());
        return tc;
    }
}
