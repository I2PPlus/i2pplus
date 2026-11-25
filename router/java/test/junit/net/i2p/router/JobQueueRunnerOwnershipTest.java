package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Who may empty a registry slot when a runner thread ends.
 *
 * <p>A runner key names a reusable slot, not permanent ownership. Scale-down
 * unregisters an idle runner, a later scale-up hands that same key to a
 * replacement, and only after that does the retired thread reach its cleanup.
 * Removing by key alone deleted the replacement's registration instead, and a
 * replacement that is not in the registry keeps taking jobs, so it never goes
 * idle and nothing can retire it again - the unmanaged threads a long-running
 * router accumulates. These pin the identity test that replaced the bare key,
 * and the scale-up that must not land on a live runner.
 *
 * @since 0.9.72+
 */
public class JobQueueRunnerOwnershipTest {

    /** A throwaway router context, or null when one cannot be built. */
    private RouterContext _ctx;

    /** The queue whose runner registry the cases read and edit. */
    private JobQueue _queue;

    /** How many runners the queue started, so the tests count relative to it. */
    private int _started;

    /**
     * The registry-ownership cases. Each one drives a real job queue, because
     * what is being pinned is which runner object the registry holds under a
     * key, not a decision that could be called on its own.
     */
    public JobQueueRunnerOwnershipTest() {
    }

    /**
     * A context with runners allowed and started, which leaves a dense block of
     * keys the cases can name. The scaler's first pass is minutes away, so
     * nothing else moves the registry while a case is looking at it.
     */
    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _queue = _ctx.jobQueue();
        _queue.allowParallelOperation();
        _started = _queue.getActiveRunnerCount();
        assertTrue("runners did not start", _started >= 6);
    }

    /**
     * Stops the runners and the pumper this class started, so a case that
     * unregistered a runner does not leave its thread behind.
     */
    @After
    public void tearDown() {
        if (_queue != null) {_queue.shutdown();}
    }

    /**
     * A runner that still holds its own key unregisters itself, which is the
     * case the identity test must not break.
     */
    @Test
    public void theOwnerStillUnregistersItsOwnSlot() {
        JobQueueRunner runner = _queue.registeredRunner(0);
        assertNotNull(runner);

        _queue.removeRunner(0, runner);

        assertNull(_queue.registeredRunner(0));
        assertEquals(_started - 1, _queue.getActiveRunnerCount());
    }

    /**
     * The retired thread reaching its cleanup after its key was reused must
     * leave the replacement registered.
     */
    @Test
    public void aStaleRunnerCannotUnregisterItsReplacement() {
        JobQueueRunner first = _queue.registeredRunner(0);
        assertNotNull(first);

        // The retire path unregisters the runner; its thread ends later.
        _queue.removeRunner(0, first);
        assertNull(_queue.registeredRunner(0));

        // The scale-up refills the hole it left with a different runner.
        _queue.runQueue(_started);
        JobQueueRunner replacement = _queue.registeredRunner(0);
        assertNotNull(replacement);
        assertNotSame(first, replacement);

        // Now the retired thread reaches its own cleanup. It must leave the
        // replacement registered: the replacement is taking jobs, so nothing
        // will ever retire it, and an unregistered thread outlives the pool.
        int registered = _queue.getActiveRunnerCount();
        _queue.removeRunner(0, first);

        assertSame(replacement, _queue.registeredRunner(0));
        assertEquals(registered, _queue.getActiveRunnerCount());
    }

    /**
     * A scale-up that wants more runners than the holes left below the live
     * range must not land on a runner that is still working.
     */
    @Test
    public void aScaleUpNeverReplacesALiveRunner() {
        JobQueueRunner atZero = _queue.registeredRunner(0);
        JobQueueRunner atTwo = _queue.registeredRunner(2);
        JobQueueRunner atThree = _queue.registeredRunner(3);
        assertNotNull(atZero);
        assertNotNull(atTwo);
        assertNotNull(atThree);

        // A hole below live keys, which is what retiring an idle runner leaves.
        _queue.removeRunner(1, _queue.registeredRunner(1));
        assertEquals(_started - 1, _queue.getActiveRunnerCount());

        // Four wanted. Claiming a contiguous range from the hole would land on
        // the live runners at 2 and 3; each key has to be tested on its own.
        _queue.runQueue(_started + 3);

        assertEquals(_started + 3, _queue.getActiveRunnerCount());
        assertSame(atZero, _queue.registeredRunner(0));
        assertNotNull(_queue.registeredRunner(1));
        assertSame(atTwo, _queue.registeredRunner(2));
        assertSame(atThree, _queue.registeredRunner(3));
    }

    /**
     * Cleaning up a key nothing holds removes nothing.
     */
    @Test
    public void removingAnAbsentKeyLeavesTheRegistryAlone() {
        int absent = _started + 5;
        assertNull(_queue.registeredRunner(absent));

        _queue.removeRunner(absent, _queue.registeredRunner(0));

        assertNull(_queue.registeredRunner(absent));
        assertEquals(_started, _queue.getActiveRunnerCount());
        assertNotNull(_queue.registeredRunner(0));
    }
}
