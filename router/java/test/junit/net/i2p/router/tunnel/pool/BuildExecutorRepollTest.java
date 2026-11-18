package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;

import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.pool.BuildExecutor.Result;

/**
 * Tests that a completed build is remembered as a wakeup, not only announced.
 *
 * <p>The build loop parks on the notify lock for the whole pass interval at the
 * end of a pass, and a completion that arrives while the loop is running the
 * pass body has nobody parked to receive it. If the completion is only
 * announced, the loop parks anyway and a freed tunnel slot sits unfilled for the
 * full 15 seconds, so the completion has to set the repoll flag under the same
 * monitor the loop reads that flag under. The flag is what makes the wakeup
 * survive arriving early.
 *
 * <p>No build thread: the completion is driven directly and the flag is read
 * back by reflection.
 *
 * @since 0.9.72+
 */
public class BuildExecutorRepollTest {

    private static RouterContext _ctx;

    /**
     * One context for the whole group; the executor and its pool are built per
     * test over it.
     */
    @BeforeClass
    public static void globalSetUp() {
        _ctx = RouterTestHelper.newContext();
    }

    /**
     * A fixture group for the executor's wakeup handling; it holds no state.
     *
     * @since 0.9.72+
     */
    public BuildExecutorRepollTest() {}

    /**
     * The threshold is exclusive: a build that ended at it does not wake the
     * loop. The CPU backoff for a router with no network depends on not being
     * woken once per unanswered request.
     */
    @Test
    public void testTheWakeupThresholdIsExclusive() {
        assertFalse(BuildExecutor.shouldRepoll(0));
        assertFalse(BuildExecutor.shouldRepoll(250));
        assertTrue(BuildExecutor.shouldRepoll(251));
        assertTrue(BuildExecutor.shouldRepoll(60 * 1000L));
    }

    /**
     * A build that took a second records the completion, so a loop that is in
     * the middle of a pass still sees it when the pass ends.
     *
     * @throws Exception if the private repoll flag cannot be read
     */
    @Test
    public void testASlowCompletionIsRemembered() throws Exception {
        BuildExecutor exec = newExecutor();
        PooledTunnelCreatorConfig cfg = newConfig(_ctx.clock().now() - 1000);
        exec.buildComplete(cfg, Result.OTHER_FAILURE);
        assertTrue("the completion was announced but not remembered", readRepoll(exec));
    }

    /**
     * A build that ended immediately - the no-network shape, one of those per
     * outstanding request - leaves the flag alone, so the loop still waits out
     * its interval instead of re-passing per failure.
     *
     * @throws Exception if the private repoll flag cannot be read
     */
    @Test
    public void testAFastCompletionIsNotRemembered() throws Exception {
        BuildExecutor exec = newExecutor();
        PooledTunnelCreatorConfig cfg = newConfig(_ctx.clock().now());
        exec.buildComplete(cfg, Result.OTHER_FAILURE);
        assertFalse("a fast failure woke the loop", readRepoll(exec));
    }

    /**
     * Stopping the executor also takes the loop out of its park, or shutdown
     * waits out the pass interval before the thread notices it is over.
     *
     * @throws Exception if the private repoll flag cannot be read
     */
    @Test
    public void testShutdownWakesTheLoop() throws Exception {
        BuildExecutor exec = newExecutor();
        assertFalse(readRepoll(exec));
        exec.shutdown();
        assertTrue("shutdown left the loop parked", readRepoll(exec));
    }

    /**
     * An executor with no thread running and mocked collaborators; the skipped
     * paths never touch the manager or the ghost peer manager.
     *
     * @return an executor over the test context
     */
    private static BuildExecutor newExecutor() {
        return new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));
    }

    /**
     * A three-hop client tunnel config, with the time its first hop was asked.
     *
     * @param created when the build was dispatched, which is what the completion
     *                time is measured from
     * @return a config bound to a live pool
     */
    private static PooledTunnelCreatorConfig newConfig(long created) {
        TunnelPool pool = new TunnelPool(_ctx, mock(TunnelPoolManager.class),
                                         new TunnelPoolSettings(false),
                                         mock(TunnelPeerSelector.class));
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, false, null, pool);
        cfg.getConfig(0).setCreation(created);
        return cfg;
    }

    /**
     * The flag the loop reads before it decides to park.
     *
     * @param exec the executor to read
     * @return whether a wakeup is pending
     * @throws Exception if the private repoll field is gone or reflection is not
     *                   allowed to open it
     */
    private static boolean readRepoll(BuildExecutor exec) throws Exception {
        Field f = BuildExecutor.class.getDeclaredField("_repoll");
        f.setAccessible(true);
        return f.getBoolean(exec);
    }
}
