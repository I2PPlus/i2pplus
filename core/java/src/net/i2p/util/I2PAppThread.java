package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Like {@link I2PThread} but with per-thread OOM listeners,
 * rather than a static router-wide listener list,
 * so that an OOM in an app won't call the router listener
 * to shutdown the whole router.
 *
 * This is preferred for application use.
 * See {@link I2PThread} for features.
 */
public class I2PAppThread extends I2PThread {

    private final Set<OOMEventListener> _threadListeners = new CopyOnWriteArraySet<>();

    /** Creates a new I2PAppThread with no target or name. */
    public I2PAppThread() {
        super();
    }

    /**
     * Creates a new I2PAppThread with the given name.
     *
     * @param name the thread name
     */
    public I2PAppThread(String name) {
        super(name);
    }

    /**
     * Creates a new I2PAppThread with the given target runnable.
     *
     * @param r the target runnable
     */
    public I2PAppThread(Runnable r) {
        super(r);
    }

    /**
     * Creates a new I2PAppThread with the given target and name.
     *
     * @param r the target runnable
     * @param name the thread name
     */
    public I2PAppThread(Runnable r, String name) {
        super(r, name);
    }

    /**
     * Creates a new I2PAppThread with the given target, name, and daemon status.
     *
     * @param r the target runnable
     * @param name the thread name
     * @param isDaemon whether the thread is a daemon
     */
    public I2PAppThread(Runnable r, String name, boolean isDaemon) {
        super(r, name, isDaemon);
    }

    /**
     * Creates a new I2PAppThread in the given thread group.
     *
     * @param group the thread group
     * @param r the target runnable
     * @param name the thread name
     * @since 0.9.23
     */
    public I2PAppThread(ThreadGroup group, Runnable r, String name) {
        super(group, r, name);
    }

    @Override
    protected void fireOOM(OutOfMemoryError oom) {
        for (OOMEventListener listener : _threadListeners) listener.outOfMemory(oom);
    }

    /**
     * Register a new component that wants notification of OOM events.
     *
     * @param lsnr the listener to register
     */
    public void addOOMEventThreadListener(OOMEventListener lsnr) {
        _threadListeners.add(lsnr);
    }

    /**
     * Unregister a component that wants notification of OOM events.
     *
     * @param lsnr the listener to unregister
     */
    public void removeOOMEventThreadListener(OOMEventListener lsnr) {
        _threadListeners.remove(lsnr);
    }
}
