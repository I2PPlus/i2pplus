package net.i2p.client.streaming.impl;

import net.i2p.I2PAppContext;

/**
 * <p>Scheduler used for after both sides have had their close packets
 * ACKed, but the final timeout hasn't passed.</p>
 *
 * <h2>Entry conditions:</h2>
 * <ul>
 * <li>Both sides have closed and ACKed.</li>
 * <li>Less than the final timeout period has passed since the last ACK.</li>
 * </ul>
 *
 * <h2>Events:</h2>
 * <ul>
 * <li>Packets received</li>
 * <li>RESET received</li>
 * <li>Message sending fails (error talking to the session)</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <ul>
 * <li>{@link SchedulerDead dead} - after the final timeout passes</li>
 * </ul>
 *
 *
 */
class SchedulerClosed extends SchedulerImpl {
    public SchedulerClosed(I2PAppContext ctx) {
        super(ctx);
    }

    /**
     * Accept connections where both sides have closed and ACKed, and the
     * final timeout hasn't passed.
     *
     * @param con the connection to check
     * @return true if the connection is in the closed state
     */
    public boolean accept(Connection con) {
        if (con == null) return false;
        long timeSinceClose = _context.clock().now() - con.getCloseSentOn();
        boolean ok = (con.getCloseSentOn() > 0) &&
                     (con.getCloseReceivedOn() > 0) &&
                     (con.getUnackedPacketsSent() <= 0) &&
                     (!con.getResetReceived()) &&
                      (timeSinceClose < Connection.getDisconnectTimeout());
        boolean conTimeout = (con.getOptions().getConnectTimeout() < con.getLifetime()) &&
                             con.getSendStreamId() <= 0 &&
                             con.getLifetime() < Connection.getDisconnectTimeout();
        return (ok || conTimeout);
    }

    @Override
    public void eventOccurred(Connection con) {
        // noop - timeout handled by the simpleTimer
    }
}
