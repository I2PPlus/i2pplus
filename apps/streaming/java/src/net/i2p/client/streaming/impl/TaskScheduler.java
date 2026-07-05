package net.i2p.client.streaming.impl;

/**
 * Coordinates what we do 'next'.  The scheduler used by a connection is
 * selected based upon its current state.
 *
 */
public interface TaskScheduler {
    /**
     * An event has occurred (timeout, message sent, or message received),
     * so schedule what to do next based on our current state.
     *
     * @param con the connection on which the event occurred
     */
    public void eventOccurred(Connection con);

    /**
     * Determine whether this scheduler is fit to operate against the
     * given connection.
     *
     * @param con the connection to check
     * @return true if this scheduler should handle the connection
     */
    public boolean accept(Connection con);
}
