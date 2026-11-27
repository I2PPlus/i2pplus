package net.i2p.router.tasks;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.I2PAppContext;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.JobQueue;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.util.LogManager;

import org.junit.Test;

/**
 * The daily routing key modifier job requeues whatever the update does.
 * <p>
 * The job is the only thing that renews the routing key modifier, and it runs on
 * its own schedule in the job queue. An unchecked exception out of the update -
 * a blinded key that cannot be derived is one - escaped runJob(), so the requeue
 * never happened and the modifier stayed stale for the rest of the run. Upstream
 * wraps the update in a catch in 6ad05c0ebc; these pin the requeue on both paths.
 *
 * @since 0.9.72+
 */
public class UpdateRoutingKeyModifierJobTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state.
     *
     * @since 0.9.72+
     */
    public UpdateRoutingKeyModifierJobTest() {}

    /** The job's own failsafe spacing, 15 min, as a bound on the requeue. */
    private static final long MAX_DELAY_FAILSAFE = 15 * 60 * 1000L;

    /**
     * A key generator whose daily update fails the way a bad blinded key does,
     * with an unchecked exception. The failure is armed only after the base
     * constructor has run the real update once to size the mod data.
     *
     * @since 0.9.72+
     */
    private static final class FailingKeyGenerator extends RouterKeyGenerator {

        /** True once the base constructor has generated the mod data. */
        private boolean _armed;

        /**
         * @param ctx the context supplying the log and the clock
         */
        FailingKeyGenerator(I2PAppContext ctx) {
            super(ctx);
            _armed = true;
        }

        /**
         * The daily update, or the failure it stands for here.
         *
         * @return the mod data, only while the generator is being built
         * @throws IllegalArgumentException always, once armed
         */
        @Override
        public boolean generateDateBasedModData() {
            if (_armed) {
                throw new IllegalArgumentException("forced, as a blinded key that cannot be derived does");
            }
            return super.generateDateBasedModData();
        }
    }

    /**
     * A context wired for the job: a real clock and log manager, a queue that
     * records nothing, and a netdb that needs no key ring.
     *
     * @param gen the key generator the job under test will see
     * @return the context, never null
     */
    private static RouterContext contextFor(RouterKeyGenerator gen) {
        I2PAppContext app = I2PAppContext.getGlobalContext();
        RouterContext ctx = mock(RouterContext.class);
        when(ctx.logManager()).thenReturn(new LogManager(app));
        when(ctx.clock()).thenReturn(app.clock());
        when(ctx.jobQueue()).thenReturn(mock(JobQueue.class));
        when(ctx.netDb()).thenReturn(mock(NetworkDatabaseFacade.class));
        when(ctx.routerKeyGenerator()).thenReturn(gen);
        return ctx;
    }

    /**
     * An update that dies with an unchecked exception still leaves the job queued
     * for the next window. At HEAD the exception escaped runJob() and the requeue
     * never happened.
     */
    @Test
    public void theJobIsRequeuedEvenWhenTheUpdateThrows() {
        RouterContext ctx = contextFor(new FailingKeyGenerator(I2PAppContext.getGlobalContext()));
        UpdateRoutingKeyModifierJob job = new UpdateRoutingKeyModifierJob(ctx);
        long before = ctx.clock().now();

        job.runJob();

        assertTrue("the job was not requeued after the update failed",
                   job.getTiming().getStartAfter() > before);
    }

    /**
     * The normal path still requeues, inside the job's own failsafe window - the
     * catch did not change the schedule.
     */
    @Test
    public void theJobIsRequeuedOnTheNormalPath() {
        RouterContext ctx = contextFor(new RouterKeyGenerator(I2PAppContext.getGlobalContext()));
        UpdateRoutingKeyModifierJob job = new UpdateRoutingKeyModifierJob(ctx);
        long before = ctx.clock().now();

        job.runJob();

        long requeueAt = job.getTiming().getStartAfter();
        assertTrue("the job was not requeued", requeueAt > before);
        assertTrue("the requeue is later than the job's own failsafe window: " + (requeueAt - before),
                   requeueAt - before <= MAX_DELAY_FAILSAFE);
    }
}
