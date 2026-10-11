package net.i2p.client.streaming.impl;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Base class for connection state schedulers. Provides common utilities
 * for rescheduling events and logging.
 */
abstract class SchedulerImpl implements TaskScheduler {
    /**
     * Application context the scheduled connections live in.
     */
    protected final I2PAppContext _context;
    /**
     * Logger scoped to this scheduler class.
     */
    protected final Log _log;

    /**
     * Create a new scheduler with the given context.
     *
     * @param ctx the application context
     */
    public SchedulerImpl(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(SchedulerImpl.class);
    }

    /**
     * Schedule a connection event to occur after the specified delay.
     *
     * @param msToWait the delay in milliseconds
     * @param con the connection to reschedule
     */
    protected void reschedule(long msToWait, Connection con) {
        con.scheduleConnectionEvent(msToWait);
    }

    /**
     * Name of the scheduler class.
     */
    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
