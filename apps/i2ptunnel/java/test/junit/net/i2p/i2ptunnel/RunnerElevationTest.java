package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

/**
 * Contract tests for the runner-pool ceiling, its demand-driven elevation, and the
 * elevation's release.
 *
 * <p>Four properties are pinned, three of which were found by reading the field
 * rather than by a failing test:
 *
 * <ul>
 * <li><b>the ceiling is not frozen.</b> A tunnel whose ceiling is 0 (no explicit
 *     cap) must re-read the Tuner-managed default at every rebalance. Resolving it
 *     once at registration freezes the default at whatever it was when the tunnel
 *     started, so a later rise is silently ignored and the budget goes unspent.</li>
 * <li><b>the elevation is a floor, not a cap.</b> The next rebalance computes each
 *     pool's share from the ceiling, so an elevation held only in the executor's
 *     maximum would be shrunk straight back. Held separately on the handler it
 *     survives.</li>
 * <li><b>the floor composes with a default rise.</b> A floor that also blocked a
 *     later default rise would trade one frozen value for another, so the derived
 *     ceiling is the larger of the two.</li>
 * <li><b>the floor is released on evidence, not on a timer.</b> It drops once the
 *     pool has completed as many tasks while quiet as it had threads at the peak,
 *     and any demand resets the baseline — so a burst with short lulls keeps its
 *     headroom while a tunnel that has genuinely gone quiet gives a shared budget
 *     back.</li>
 * </ul>
 *
 * <p>{@code RunnerHandler} and the decision methods are package-visible for this,
 * and the test restores the global default afterwards so it cannot leak into other
 * tests in the same JVM.
 *
 * @since 0.9.71+
 */
public class RunnerElevationTest {

    private final int _savedDefault = TunnelControllerGroup.getClientDefaultMaxConnections();

    @After
    public void restoreDefault() {
        TunnelControllerGroup.setClientDefaultMaxConnections(_savedDefault);
    }

    /**
     * The default must move the derived ceiling. A frozen ceiling is invisible until
     * the default rises, at which point the pool keeps its startup size forever.
     */
    @Test
    public void defaultRaisesTheCeiling() {
        TunnelControllerGroup.setClientDefaultMaxConnections(512);
        TunnelControllerGroup.RunnerHandler follow =
            new TunnelControllerGroup.RunnerHandler(0);
        assertEquals("a 0 ceiling follows the default",
                     512, TunnelControllerGroup.runnerCeilingFor(follow));

        // Raising the default must raise the ceiling - this is the whole point of
        // storing 0 instead of resolving at registration.
        TunnelControllerGroup.setClientDefaultMaxConnections(1024);
        assertEquals("a raised default must be adopted, not frozen",
                     1024, TunnelControllerGroup.runnerCeilingFor(follow));
    }

    /**
     * An explicit cap wins over the default, so a tunnel that configured its own
     * limit keeps it whatever the Tuner does.
     */
    @Test
    public void explicitCapBeatsTheDefault() {
        TunnelControllerGroup.setClientDefaultMaxConnections(1024);
        TunnelControllerGroup.RunnerHandler h =
            new TunnelControllerGroup.RunnerHandler(256);
        assertEquals(256, TunnelControllerGroup.runnerCeilingFor(h));
    }

    /**
     * The elevation survives a ceiling that would otherwise pull the pool back, and
     * does not block a later rise in the default.
     */
    @Test
    public void elevationIsAFloorThatComposes() {
        TunnelControllerGroup.setClientDefaultMaxConnections(256);
        TunnelControllerGroup.RunnerHandler h =
            new TunnelControllerGroup.RunnerHandler(0);
        assertEquals(256, TunnelControllerGroup.runnerCeilingFor(h));

        // Elevated to 384 under load: the derived ceiling must be the floor, so the
        // next rebalance will not shrink the pool back to 256.
        h.elevatedMax = 384;
        assertEquals("elevation must survive the rebalance",
                     384, TunnelControllerGroup.runnerCeilingFor(h));

        // ...and a later default rise still wins, so the floor is not a new freeze.
        TunnelControllerGroup.setClientDefaultMaxConnections(768);
        assertEquals("a raised default must outgrow the floor",
                     768, TunnelControllerGroup.runnerCeilingFor(h));

        // A floor above a raised default still holds.
        TunnelControllerGroup.setClientDefaultMaxConnections(320);
        assertEquals("floor above the default must hold",
                     384, TunnelControllerGroup.runnerCeilingFor(h));
    }

    /**
     * The elevation ladder is bounded: one step past the ceiling, of half the
     * ceiling or 32, whichever is larger. Unbounded growth would let one tunnel
     * claim an arbitrary share of the thread budget.
     */
    @Test
    public void elevationStepIsBounded() {
        // Below the ceiling there is no elevation - just the ceiling.
        assertEquals(256, TunnelControllerGroup.elevatedRunnerMax(128, 256));
        // At the ceiling, exactly one bounded step beyond it.
        assertEquals(384, TunnelControllerGroup.elevatedRunnerMax(256, 256));
        // A small ceiling still gets the 32-thread minimum step rather than 8.
        assertEquals(48, TunnelControllerGroup.elevatedRunnerMax(16, 16));
        // Already elevated, so the next step is measured from the present maximum.
        assertEquals(576, TunnelControllerGroup.elevatedRunnerMax(384, 384));
    }

    /**
     * Elevation requires a genuinely backed-up queue, not momentary saturation.
     *
     * <p>This is a live-measured regression: on a tracker, every elevation fired
     * with {@code queued 1} — a submit that finds every worker busy plus one task
     * in flight is indistinguishable, at that instant, from real backlog. The
     * result was 256->384 three times in twenty minutes with the decay clawing it
     * back each time: pure churn. Half the 64-slot burst queue is the first honest
     * signal that the burst is not micro.
     */
    @Test
    public void elevationRequiresBacklogNotSaturation() {
        // The burst queue holds 64; the trigger sits at half of it.
        int trigger = TunnelControllerGroup.RUNNER_BURST_QUEUE / 2;
        assertEquals(32, trigger);

        // Below the trigger - including the measured queued 1 - no elevation.
        assertFalse("a single queued task must not elevate",
                   TunnelControllerGroup.shouldElevateRunner(1, 256, 256));
        assertFalse("one short of the trigger must not elevate",
                   TunnelControllerGroup.shouldElevateRunner(trigger - 1, 256, 256));
        // At the trigger, with the pool already at its ceiling: elevate.
        assertTrue("half the burst queue with the pool pinned must elevate",
                  TunnelControllerGroup.shouldElevateRunner(trigger, 256, 256));
        // Below the ceiling, growth to the ceiling is a separate concern and is not
        // an elevation - a half-empty queue there must not escalate.
        assertFalse("below the ceiling must grow, not elevate",
                   TunnelControllerGroup.shouldElevateRunner(trigger, 128, 256));
    }

    /**
     * The floor is released once the pool has done as much work while quiet as it
     * had threads at the peak — and not before.
     */
    @Test
    public void elevationReleasesOnIdleWork() {
        // Baseline recorded at 1000 completed; the floor is 384.
        assertFalse("not enough idle work yet",
                    TunnelControllerGroup.shouldReleaseElevation(1000, 1000, 384));
        assertFalse("one short of the floor",
                    TunnelControllerGroup.shouldReleaseElevation(1383, 1000, 384));
        assertTrue("a full pool's worth of idle work releases the floor",
                   TunnelControllerGroup.shouldReleaseElevation(1384, 1000, 384));
        assertTrue("more than enough releases it too",
                   TunnelControllerGroup.shouldReleaseElevation(5000, 1000, 384));
    }

    /**
     * A pool that was never elevated never releases, and a baseline that has been
     * cleared (demand restarted) re-arms the measurement rather than releasing.
     */
    @Test
    public void releaseRequiresAnElevationAndABaseline() {
        assertFalse("never elevated: nothing to release",
                    TunnelControllerGroup.shouldReleaseElevation(9999, 1000, 0));
        assertFalse("no baseline recorded yet: re-arm instead",
                    TunnelControllerGroup.shouldReleaseElevation(9999, 0, 384));
        assertFalse("a backwards count means the pool was recreated",
                    TunnelControllerGroup.shouldReleaseElevation(500, 1000, 384));
    }
}
