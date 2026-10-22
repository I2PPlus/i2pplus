package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.router.crypto.ratchet.RatchetPayload.Block;
import net.i2p.router.crypto.ratchet.RatchetPayload.GarlicBlock;
import net.i2p.router.crypto.ratchet.RatchetPayload.NextKeyBlock;

import org.junit.Test;

/**
 * Tests what a recycled ratchet payload block keeps holding.
 * <p>
 * Payload blocks are pooled per transport thread and re-armed on each acquire,
 * which is what makes the pooling worth having. It also means a pooled block
 * keeps whatever it last carried - and a GarlicBlock's clove reaches the whole
 * clove payload, while a NextKeyBlock's key is a live session key. Clearing
 * those on release is only safe if the reuse path still works, so both halves
 * are pinned: the reference is gone, and the block is still reused and re-armed.
 *
 * @since 0.9.72+
 */
public class RatchetBlockPoolRetentionTest {

    /**
     * Read a private field.
     *
     * @param target the object to read from
     * @param owner the class declaring the field
     * @param name the field name
     * @return the field's current value
     */
    private static Object field(Object target, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * One of the pool's internal free lists.
     *
     * @param pool the block pool
     * @param name the free list's field name
     * @param <T> the block type the list holds
     * @return the live list, not a copy
     */
    @SuppressWarnings("unchecked")
    private static <T> List<T> freeList(ECIESAEADEngine.BlockPool pool, String name) throws Exception {
        return (List<T>) field(pool, ECIESAEADEngine.BlockPool.class, name);
    }

    /**
     * A released garlic block must not keep the clove it carried. The pool is
     * per-thread, so a retained clove - and everything it holds - stays pinned
     * for the life of that transport thread.
     */
    @Test
    public void testReleasedGarlicBlockDropsItsClove() throws Exception {
        ECIESAEADEngine.BlockPool pool = new ECIESAEADEngine.BlockPool();
        GarlicClove clove = new GarlicClove(I2PAppContext.getGlobalContext());
        List<Block> blocks = pool.getBlocks();
        blocks.add(pool.acquireClove(clove));

        pool.release(blocks);

        List<GarlicBlock> free = freeList(pool, "cloveBlocks");
        assertEquals(1, free.size());
        assertNull("a pooled garlic block still pins the clove it carried",
                   field(free.get(0), GarlicBlock.class, "c"));
    }

    /**
     * A released next-key block must not keep the session key it carried.
     */
    @Test
    public void testReleasedNextKeyBlockDropsItsKey() throws Exception {
        ECIESAEADEngine.BlockPool pool = new ECIESAEADEngine.BlockPool();
        List<Block> blocks = pool.getBlocks();
        blocks.add(pool.acquireNextKey(new NextSessionKey(7, false, false)));

        pool.release(blocks);

        List<NextKeyBlock> free = freeList(pool, "nextKeyBlocks");
        assertEquals(1, free.size());
        assertNull("a pooled next-key block still pins a live session key",
                   field(free.get(0), NextKeyBlock.class, "next"));
    }

    /**
     * Clearing must not break reuse: the same instance comes back, carrying the
     * new clove. If it did break, the fix would be a correctness regression
     * dressed up as a memory one.
     */
    @Test
    public void testGarlicBlockIsStillReusedAndRearmed() throws Exception {
        ECIESAEADEngine.BlockPool pool = new ECIESAEADEngine.BlockPool();
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        GarlicClove first = new GarlicClove(ctx);
        GarlicClove second = new GarlicClove(ctx);

        List<Block> blocks = pool.getBlocks();
        GarlicBlock acquired = pool.acquireClove(first);
        blocks.add(acquired);
        pool.release(blocks);

        GarlicBlock again = pool.acquireClove(second);
        assertSame("the pool must still recycle the block", acquired, again);
        assertSame(second, field(again, GarlicBlock.class, "c"));
    }

    /**
     * The caller's list is emptied by the release, as documented - the payload
     * builder reuses that same list for the next payload.
     */
    @Test
    public void testReleaseEmptiesTheCallersList() throws Exception {
        ECIESAEADEngine.BlockPool pool = new ECIESAEADEngine.BlockPool();
        List<Block> blocks = pool.getBlocks();
        blocks.add(pool.acquireClove(new GarlicClove(I2PAppContext.getGlobalContext())));
        blocks.add(pool.acquirePadding(16));
        assertEquals(2, blocks.size());

        pool.release(blocks);

        assertTrue(blocks.isEmpty());
    }
}
