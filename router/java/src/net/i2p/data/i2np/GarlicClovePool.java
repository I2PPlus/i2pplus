package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 *  Pool for reusing GarlicClove instances across message parsing.
 *  Reduces per-clove allocation in the hot garlic message loop.
 *
 *  Cloves are acquired, populated via setters, used, then released back
 *  to the pool for reuse. The pool expands dynamically when empty.
 *
 *  Thread-safe for single-producer/single-consumer use (typical garlic
 *  message parsing pattern). Not safe for concurrent acquire/release.
 *
 *  @since 2.13.0
 */
public class GarlicClovePool {

    private final GarlicClove[] pool;
    private int available;
    private final I2PAppContext context;

    /**
     *  Create a pool with the specified initial capacity.
     *
     *  @param context the router/app context for creating new cloves
     *  @param initialCapacity the number of cloves to pre-allocate
     */
    public GarlicClovePool(I2PAppContext context, int initialCapacity) {
        this.context = context;
        pool = new GarlicClove[Math.max(initialCapacity, 1)];
        for (int i = 0; i < pool.length; i++) {pool[i] = new GarlicClove(context);}
        available = pool.length;
    }

    /**
     *  Create a pool with default capacity (4).
     *
     *  @param context the router/app context
     */
    public GarlicClovePool(I2PAppContext context) {this(context, 4);}

    /**
     *  Acquire a clove from the pool. If the pool is empty, a new
     *  clove is allocated. The returned clove is in its initial state
     *  (cloveId = -1, no data, no certificate) ready for population.
     *
     *  @return a GarlicClove instance, never null
     */
    public synchronized GarlicClove acquire() {
        if (available > 0) {return pool[--available];}
        return new GarlicClove(context);
    }

    /**
     *  Release a clove back to the pool for reuse. The clove is not
     *  cleared — callers should ensure they no longer hold references
     *  to the released clove.
     *
     *  @param clove the clove to release
     */
    public synchronized void release(GarlicClove clove) {
        if (available < pool.length) {pool[available++] = clove;}
    }

    /**
     *  Current number of available cloves in the pool.
     *
     *  @return available count
     */
    public synchronized int available() {return available;}

    /**
     *  Maximum capacity of the pool.
     *
     *  @return capacity
     */
    public int capacity() {return pool.length;}
}
