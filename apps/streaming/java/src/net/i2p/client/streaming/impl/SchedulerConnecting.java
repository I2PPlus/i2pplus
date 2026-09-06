package net.i2p.client.streaming.impl;

import net.i2p.I2PAppContext;

/**
 * <p>Scheduler used once we've sent our SYN but it hasn't been ACKed yet.
 * This connection may or may not be locally created.</p>
 *
 * <h2>Entry conditions:</h2>
 * <ul>
 * <li>Packets sent but none ACKed</li>
 * </ul>
 *
 * <h2>Events:</h2>
 * <ul>
 * <li>Packets received (which may or may not ACK the ones sent)</li>
 * <li>Message flush (explicitly, through a full buffer, or stream closure)</li>
 * <li>Connection establishment timeout</li>
 * <li>RESET received</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <ul>
 * <li>{@link SchedulerConnectedBulk connected} - after receiving an ACK</li>
 * <li>{@link SchedulerClosing closing} - after both sending and receiving a CLOSE</li>
 * <li>{@link SchedulerClosed closed} - after both sending and receiving ACKs on the CLOSE</li>
 * <li>{@link SchedulerDead dead} - after sending or receiving a RESET</li>
 * </ul>
 *
 */
class SchedulerConnecting extends SchedulerImpl {

    /**
     * SchedulerConnecting.
     */
    public SchedulerConnecting(I2PAppContext ctx) {
        super(ctx);
    }

    /**
     * Accept connections where the SYN has been sent but not yet ACKed.
     *
     * @param con the connection to check
     * @return true if the connection is in the connecting state
     */
    public boolean accept(Connection con) {
        if (con == null) return false;
        return (con.getIsConnected()) &&
                                  (con.getLastSendId() >= 0) &&
                                  (con.getHighestAckedThrough() < 0) &&
                                  (!con.getResetReceived());
    }

    /**
     * Handle an event on a connecting connection. Checks for connect
     * timeout and sends available data when the send time arrives.
     *
     * <p>The timeout gate uses the connection's effective connect window
     * ({@link Connection#getEffectiveConnectWindow()}) rather than the raw un-scaled
     * connectTimeout.  That window is the (connectDelay + connectTimeout) base scaled by
     * the Tuner's connect-timeout multiplier, the same budget {@code waitForConnect(int)}
     * and the SYN give-up check in {@code Connection#getMaxSynSends()} use, so this
     * scheduler can never tear an unacknowledged SYN down before those render the
     * accurate "Connection timed out: SYN not acknowledged" error.  As a last-resort
     * backstop it defers to an error that already fired and only sets its own generic
     * message when none is present.  A window of 0 means no connect timeout is
     * configured, in which case this scheduler does not give up on its own.
     *
     * @param con the connection that had an event
     */
    public void eventOccurred(Connection con) {
        long waited = _context.clock().now() - con.getCreatedOn();
        long window = con.getEffectiveConnectWindow();
        if ( (window > 0) && (waited >= window) ) {
            // Last-resort backstop on the connect window.  The SYN give-up budget
            // (see Connection.getMaxSynSends()) and waitForConnect() fire accurate
            // errors just before or at the same wall-clock boundary, so don't clobber
            // one that already ran.
            if (con.getConnectionError() == null)
                con.setConnectionError("Timeout waiting for ack (waited " + waited + "ms)");
            con.disconnect(false);
            reschedule(0, con);
            if (_log.shouldDebug())
                _log.debug("Waited too long: " + waited);
            return;
        }
        long timeTillSend = con.getNextSendTime() - _context.clock().now();
        if ( (timeTillSend <= 0) && (con.getNextSendTime() > 0) ) {
            if (_log.shouldDebug())
                _log.debug("send next on " + con);
            con.sendAvailable();
            con.setNextSendTime(-1);
        } else {
            if (con.getNextSendTime() > 0) {
                if (_log.shouldDebug())
                    _log.debug("time till send: " + timeTillSend + " on " + con);
                reschedule(timeTillSend, con);
            } else if (window > 0) {
                // no pending send; re-check the connect timeout when it elapses
                reschedule(window, con);
            }
            // else: no send pending and no connect timeout configured; the
            // connection is poked on inbound packets (see ConnectionPacketHandler)
        }
    }
}
