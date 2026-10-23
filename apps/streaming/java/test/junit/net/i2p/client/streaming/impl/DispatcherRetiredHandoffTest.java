package net.i2p.client.streaming.impl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.client.I2PSession;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Hands a packet to a dispatcher worker that a concurrent resize or shutdown
 * has just retired (1.2). The worker's queue still has room and its drain has
 * already run, so the entry is accepted and then never processed: the
 * connection's packet sits there until the dispatcher is shut down again, and
 * its payload is never released.
 *
 * The handoff is deliberately not atomic - the worker is picked under the read
 * lock and enqueued after that lock is released, and closing the window would
 * put a lock on every inbound packet. What the dispatcher can do is notice the
 * loss: a retired worker refuses the handoff, so the shard is resolved again
 * and the packet reaches the replacement.
 */
public class DispatcherRetiredHandoffTest {

    /**
     * A retired worker must not accept work, whatever room its queue has.
     */
    @Test
    public void testRetiredWorkerRefusesTheHandoff() {
        PacketDispatcher d = new PacketDispatcher(1, 100, null, null);
        try {
            PacketDispatcher.Worker w = d.new Worker(0, 100, null, null);
            assertTrue(w.offer(new PacketDispatcher.Entry(null, new ReleaseCountPacket("first"))));
            w.retire();
            assertFalse("retired worker still accepted an entry",
                        w.offer(new PacketDispatcher.Entry(null, new ReleaseCountPacket("late"))));
        } finally {
            d.shutdown();
        }
    }

    /**
     * Retiring frees everything already queued, so a handoff that got in just
     * before the retirement cannot leave its payload unreleased behind it.
     */
    @Test
    public void testRetireReleasesWhatIsAlreadyQueued() {
        PacketDispatcher d = new PacketDispatcher(1, 100, null, null);
        try {
            PacketDispatcher.Worker w = d.new Worker(0, 100, null, null);
            ReleaseCountPacket p1 = new ReleaseCountPacket("queued 1");
            ReleaseCountPacket p2 = new ReleaseCountPacket("queued 2");
            w.offer(new PacketDispatcher.Entry(null, p1));
            w.offer(new PacketDispatcher.Entry(null, p2));
            assertEquals(0, p1.releases);
            w.retire();
            assertEquals(1, p1.releases);
            assertEquals(1, p2.releases);
        } finally {
            d.shutdown();
        }
    }

    /**
     * A worker retired while the packet was in flight must not end up holding
     * it: the dispatcher notices the refusal and hands the packet to the shard's
     * replacement.
     *
     * @throws Exception if the wait for the replacement worker to drain the queue is interrupted
     */
    @Test
    public void testHandoffIsRetriedOnTheReplacementShard() throws Exception {
        AtomicInteger processed = new AtomicInteger();
        ReleaseCountPacket packet = new ReleaseCountPacket("in flight");
        PacketDispatcher.PacketProcessor processor = new PacketDispatcher.PacketProcessor() {
            @Override
            public void process(Connection con, Packet p) {
                processed.incrementAndGet();
                p.releasePayload();
            }
        };
        PacketDispatcher d = new PacketDispatcher(1, 100, processor, null);
        try {
            final PacketDispatcher.Worker[] workers = readWorkers(d);
            // The state dispatch can observe mid-resize: the slot still names a
            // worker whose drain has already run.
            PacketDispatcher.Worker dead = d.new Worker(0, 100, processor, null);
            dead.retire();
            workers[0] = dead;
            // The replacement, installed a little later, as a resize would.
            final PacketDispatcher.Worker live = d.new Worker(0, 100, processor, null);
            live.start();
            Thread fixer = new Thread() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    workers[0] = live;
                }
            };
            fixer.start();

            d.dispatch(0, null, packet);

            // dispatch() returns as soon as an entry is queued, so give the
            // replacement worker a moment to drain it.
            long deadline = System.currentTimeMillis() + 5000;
            while (processed.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertEquals("the packet was left on the retired shard", 1, processed.get());
            assertEquals(1, packet.releases);
            assertEquals(0, dead._busy);
            fixer.join(5000);
        } finally {
            d.shutdown();
        }
    }

    /**
     * A queue that is genuinely full is still a full queue, not a retired
     * worker: the entry is refused for capacity and the dispatcher waits for
     * space rather than dropping the packet.
     *
     * @throws Exception if the wait for the worker to enter process() is interrupted
     */
    @Test
    public void testAFullQueueStillWaitsForSpace() throws Exception {
        final CountDownLatch gate = new CountDownLatch(1);
        PacketDispatcher d = new PacketDispatcher(1, 1, new PacketDispatcher.PacketProcessor() {
            @Override
            public void process(Connection con, Packet p) {
                p.releasePayload();
            }
        }, null);
        try {
            PacketDispatcher.Worker w = readWorkers(d)[0];
            // Park the worker inside process() so its queue cannot drain.
            ReleaseCountPacket blocker = new ReleaseCountPacket("blocker") {
                @Override
                public void releasePayload() {
                    try {
                        gate.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            assertTrue(w.offer(new PacketDispatcher.Entry(null, blocker)));
            while (w._busy == 0) {
                Thread.sleep(1);
            }
            assertTrue(w.offer(new PacketDispatcher.Entry(null, new ReleaseCountPacket("queued"))));
            ReleaseCountPacket refused = new ReleaseCountPacket("refused");
            assertFalse("a full queue accepted a third entry",
                        w.offer(new PacketDispatcher.Entry(null, refused)));
            assertEquals(0, refused.releases);
        } finally {
            gate.countDown();
            d.shutdown();
        }
    }

    private static PacketDispatcher.Worker[] readWorkers(PacketDispatcher d) throws Exception {
        java.lang.reflect.Field f = PacketDispatcher.class.getDeclaredField("_workers");
        f.setAccessible(true);
        return (PacketDispatcher.Worker[]) f.get(d);
    }

    /**
     * A packet that counts how often its payload was freed. The label names it
     * in a failure so a stranded entry can be told from a released one.
     */
    private static class ReleaseCountPacket extends Packet {
        final String _label;
        int releases;

        ReleaseCountPacket(String label) {
            super((I2PSession) null);
            _label = label;
        }

        @Override
        public void releasePayload() {
            releases++;
        }

        @Override
        public String toString() {
            return "ReleaseCountPacket " + _label;
        }
    }
}
