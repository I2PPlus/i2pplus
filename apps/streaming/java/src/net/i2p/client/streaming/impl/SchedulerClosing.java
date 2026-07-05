package net.i2p.client.streaming.impl;

import net.i2p.I2PAppContext;

/**
 * <p>Scheduler used for after both SYNs have been ACKed and both sides
 * have closed the stream, but either we haven't ACKed their close or
 * they haven't ACKed ours.</p>
 *
 * <h2>Entry conditions:</h2>
 * <ul>
 * <li>Both sides have closed.</li>
 * <li>At least one direction has not ACKed the close.</li>
 * </ul>
 *
 * <h2>Events:</h2>
 * <ul>
 * <li>Packets received (which may or may not ACK the ones sent)</li>
 * <li>RESET received</li>
 * <li>Message sending fails (error talking to the session)</li>
 * <li>Message sending fails (too many resends)</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <ul>
 * <li>{@link SchedulerClosed closed} - after both sending and receiving ACKs on the CLOSE</li>
 * <li>{@link SchedulerDead dead} - after sending or receiving a RESET</li>
 * </ul>
 *
 */
class SchedulerClosing extends SchedulerImpl {

    public SchedulerClosing(I2PAppContext ctx) {
        super(ctx);
    }

    /**
     * Accept connections where both sides have closed but at least one
     * direction hasn't ACKed the close.
     *
     * @param con the connection to check
     * @return true if the connection is in the closing state
     */
    @Override
    public boolean accept(Connection con) {
        if (con == null)
            return false;
        long timeSinceClose = _context.clock().now() - con.getCloseSentOn();
        boolean ok = (!con.getResetSent()) &&
                     (!con.getResetReceived()) &&
                     ( (con.getCloseSentOn() > 0) || (con.getCloseReceivedOn() > 0) ) &&
                      (timeSinceClose < Connection.getDisconnectTimeout()) &&
                     ( (con.getUnackedPacketsReceived() > 0) || (con.getUnackedPacketsSent() > 0) );
        return ok;
    }

    /**
     * Handle an event on a closing connection. If there's data to send
     * and the send delay has elapsed, send it. Otherwise reschedule.
     *
     * @param con the connection that had an event
     */
    public void eventOccurred(Connection con) {
        long nextSend = con.getNextSendTime();
        long now = _context.clock().now();
        long remaining;
        if (nextSend <= 0) {
            remaining = con.getOptions().getSendAckDelay();
            nextSend = now + remaining;
            con.setNextSendTime(nextSend);
        } else {
            remaining = nextSend - now;
        }
        if (_log.shouldDebug())
            _log.debug("Event occurred with " + remaining + "ms remaining\n* " + con); // ms?
        if (remaining <= 0) {
            if (con.getCloseSentOn() <= 0) {
                con.sendAvailable();
            }
            con.setNextSendTime(now + con.getOptions().getSendAckDelay());
        } else {
            reschedule(remaining, con);
        }
    }
}
