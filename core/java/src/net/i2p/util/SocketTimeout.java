package net.i2p.util;

import java.io.IOException;
import java.net.Socket;
import java.time.Instant;

/**
 * Implements one or two timers; one for inactivity, that is reset by {@link #resetTimer()},
 * and optionally, a total time since instantiation, that is configured by {@link #setTotalTimeoutPeriod}.
 * <p>
 * On timer expiration, this will close a provided socket, and/or run a configured job.
 * <p>
 * Deprecated for external use.
 * It is only used by {@link EepGet}, its subclasses, and Syndie.
 * Take care not to break Syndie.
 * The only advantage seems to be a total timeout period, which is the second
 * argument to {@link net.i2p.util.EepGet#fetch(long, long, long)},
 * which is most likely always set to -1.
 * <p>
 * Not for use by plugins or external applications, subject to change.
 * <p>
 * Use socket.setsotimeout instead?
 */
public class SocketTimeout extends SimpleTimer2.TimedEvent {
    /** The socket this timeout watches. */
    private volatile Socket _targetSocket;
    /** When the timeout was created. */
    private final long _startTime;
    /** Quiet time past which the socket times out, in ms. */
    private volatile long _inactivityDelay;
    /** When the socket last carried data. */
    private volatile long _lastActivity;
    /** Absolute deadline past which the socket times out. */
    private volatile long _totalTimeoutTime;
    /** True once the timeout will not fire. */
    private volatile boolean _cancelled;
    /** What runs when the timeout fires. */
    private volatile Runnable _command;

    /**
     * Create a timer that runs a command after a period of socket inactivity.
     *
     * @param delay The inactivity delay, greater than zero
     */
    public SocketTimeout(long delay) {
        this(null, delay);
    }

    /**
     * If socket is non-null, or is set later by {@link #setSocket},
     * it will be closed when the timer expires.
     *
     * @param socket may be null
     * @param delay The inactivity delay, greater than zero
     */
    public SocketTimeout(Socket socket, long delay) {
        super(SimpleTimer2.getInstance());
        _inactivityDelay = delay;
        _targetSocket = socket;
        _lastActivity = _startTime = System.currentTimeMillis();
        schedule(delay);
    }

    @Override
    public void timeReached() {
        if (_cancelled) return;
        long now = System.currentTimeMillis();
        if ((_totalTimeoutTime > 0 && _totalTimeoutTime <= now) || (_inactivityDelay + _lastActivity <= now)) {
            if (_targetSocket != null) {
                try {
                    if (!_targetSocket.isClosed()) _targetSocket.close();
                } catch (IOException ioe) { /* ignored */ }
            }
            if (_command != null) _command.run();
        } else {
            if (_totalTimeoutTime > 0) {
                schedule(Math.min(_inactivityDelay, _totalTimeoutTime - now));
            } else {
                schedule(_inactivityDelay);
            }
        }
    }

    /**
     * Change in return value from void to boolean in
     * 0.9.3 accidentally broke Syndie, sorry.
     * Recompile Syndie to fix it.
     */
    @Override
    public boolean cancel() {
        _cancelled = true;
        return super.cancel();
    }

    /**
     * If non-null, will be closed when the timer expires.
     *
     * @param s the socket to close on expiry, or null to leave the socket alone
     */
    public void setSocket(Socket s) {
        _targetSocket = s;
    }

    /**
     * Call when there is activity.
     */
    public void resetTimer() {
        _lastActivity = System.currentTimeMillis();
    }

    /**
     * Changes the delay provided in the constructor.
     *
     * @param delay greater than zero
     */
    public void setInactivityTimeout(long delay) {
        _inactivityDelay = delay;
    }

    /**
     * If greater than zero, must be greater than the inactivity timeout.
     *
     * @param timeoutPeriod Time since constructed, or less than or equal to zero to disable
     */
    public void setTotalTimeoutPeriod(long timeoutPeriod) {
        if (timeoutPeriod > 0) _totalTimeoutTime = _startTime + timeoutPeriod;
        else _totalTimeoutTime = 0;
    }

    /**
     * If non-null, will be run when the timer expires.
     *
     * @param job the command to run on expiry, or null to leave the current command in place
     */
    public void setTimeoutCommand(Runnable job) {
        _command = job;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append("SocketTimeout started on ");
        buf.append(Instant.ofEpochMilli(_startTime));
        buf.append(" idle for ");
        buf.append(System.currentTimeMillis() - _lastActivity);
        buf.append("ms ");
        if (_totalTimeoutTime > 0) buf.append("total timeout at ").append(Instant.ofEpochMilli(_totalTimeoutTime));
        buf.append("cancelled? ").append(_cancelled);
        return buf.toString();
    }
}
