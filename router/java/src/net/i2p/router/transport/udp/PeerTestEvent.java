package net.i2p.router.transport.udp;

import static net.i2p.router.transport.TransportUtil.IPv6Config.*;
import static net.i2p.router.transport.udp.PeerTestState.Role.*;

import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

    /**
     *  Initiate a test (we are Alice)
     *
     *  @since 0.9.30 moved out of UDPTransport
     */
    class PeerTestEvent extends SimpleTimer2.TimedEvent {
    /** Router context. */
    private final RouterContext _context;
    /** Logger. */
    private final Log _log;
    /** UDP transport. */
    private final UDPTransport _transport;
    /** Peer test manager. */
    private final PeerTestManager _testManager;

    /** Whether this event is active. */
    private boolean _alive;
    /** when did we last test our reachability */
    private final AtomicLong _lastTested = new AtomicLong();
    /** when did we last test our IPv6 reachability */
    private final AtomicLong _lastTestedV6 = new AtomicLong();
    /** no force run */
    private static final int NO_FORCE = 0;
    /** force IPv4 test */
    private static final int FORCE_IPV4 = 1;
    /** force IPv6 test */
    private static final int FORCE_IPV6 = 2;
    /** force run flags */
    private int _forceRun;
    /** whether last test was IPv6 */
    private boolean _lastTestIPv6 = true;
    /** test frequency in ms */
    private static final int TEST_FREQUENCY = 5*60*1000;
    /** must be greater than PeerTestManager.MAX_TEST_TIME */
    private static final int MIN_TEST_FREQUENCY = 45*1000;

    /** property to disable peer test */
    private static final String PROP_DISABLE_PEER_TEST = "i2np.udp.disablePeerTest";

    /** Peer test event. */
    PeerTestEvent(RouterContext ctx, UDPTransport udp, PeerTestManager ptmgr) {
        super(ctx.simpleTimer2());
        _context = ctx;
        _log = ctx.logManager().getLog(PeerTestEvent.class);
        _transport = udp;
        _testManager = ptmgr;
    }

    /** timeReached. */
    @Override
    public synchronized void timeReached() {
        if (shouldTest()) {
            long now = _context.clock().now();
            long sinceRunV4 = now - _lastTested.get();
            long sinceRunV6 = now - _lastTestedV6.get();
            boolean configV4fw = _transport.isIPv4Firewalled();
            boolean configV6fw = _transport.isIPv6Firewalled();
            boolean preferV4 = _lastTestIPv6;
            if (!configV4fw && (_forceRun & FORCE_IPV4) != 0 && sinceRunV4 >= MIN_TEST_FREQUENCY) {
                locked_runTest(false);
            } else if (!configV6fw && (_forceRun & FORCE_IPV6) != 0 && _transport.hasIPv6Address() && sinceRunV6 >= MIN_TEST_FREQUENCY) {
                locked_runTest(true);
            } else if (preferV4 && !configV4fw && sinceRunV4 >= TEST_FREQUENCY && _transport.getIPv6Config() != IPV6_ONLY) {
                locked_runTest(false);
            } else if (!configV6fw && _transport.hasIPv6Address() && sinceRunV6 >= TEST_FREQUENCY) {
                locked_runTest(true);
            } else if (!preferV4 && !configV4fw && sinceRunV4 >= TEST_FREQUENCY && _transport.getIPv6Config() != IPV6_ONLY) {
                locked_runTest(false);
            } else {
                if (_log.shouldDebug())
                    _log.debug("PeerTestEvent timeReached(), no test run" +
                              "\n* Last v4 test: " + new Date(_lastTested.get()) +
                              "\n* Last v6 test: " + new Date(_lastTestedV6.get()));
            }
        }
        if (_alive) {
            long delay;
            if (_forceRun != NO_FORCE) {
                // we still have the other once v4/v6 to test
                delay = MIN_TEST_FREQUENCY;
            } else {
                delay = (TEST_FREQUENCY * 3L / 4) + _context.random().nextInt(TEST_FREQUENCY / 4);
                // if we have 2 addresses, give IPv6 a chance also
                if (_transport.hasIPv6Address() && _transport.getIPv6Config() != IPV6_ONLY)
                    delay /= 2;
            }
            if (_log.shouldDebug())
                _log.debug("Rescheduling test to run in " + net.i2p.data.DataHelper.formatDuration(delay) + "...");
            schedule(delay);
        }
    }

    /**
     *  Just to consolidate the logging
     *  @since 0.9.57
     */
    @Override
    public void reschedule(long delay) {
        if (_log.shouldDebug())
            _log.debug("Test force? " + _forceRun + " reschedule for " + net.i2p.data.DataHelper.formatDuration(delay), new Exception());
        super.reschedule(delay);
    }

    /**
     *  Run a test with Bob.
     *  @param isIPv6 true for IPv6
     */
    private void locked_runTest(boolean isIPv6) {
        _lastTestIPv6 = isIPv6;
        PeerState bob = _transport.pickTestPeer(BOB, 0, isIPv6, null);
        if (bob != null) {
            if (_log.shouldInfo())
                _log.info("Running periodic test with Bob: " + bob);
            boolean started = _testManager.runTest(bob);
            if (started)
                setLastTested(isIPv6);
        } else {
            if (_log.shouldWarn())
                _log.warn("Unable to run Peer Test, no peers available - v6? " + isIPv6);
        }
        // We switch to NO_FORCE even if no peers,
        // so we don't get stuck running the same test over and over
        _forceRun &= ~(isIPv6 ? FORCE_IPV6 : FORCE_IPV4);
    }

    /**
     *  Run within the next 45 seconds at the latest
     *  @since 0.9.13
     */
    public synchronized void forceRunSoon(boolean isIPv6) {
        forceRunSoon(isIPv6, MIN_TEST_FREQUENCY);
    }

    /**
     *  Run within the specified time at the latest
     *  @since 0.9.39
     */
    public synchronized void forceRunSoon(boolean isIPv6, long delay) {
        if (!isIPv6 && _transport.isIPv4Firewalled())
            return;
        if (isIPv6 && _transport.isIPv6Firewalled())
            return;
        _forceRun |= isIPv6 ? FORCE_IPV6 : FORCE_IPV4;
        reschedule(delay);
    }

    /**
     *  Run within the next 5 seconds at the latest
     *  @since 0.9.13
     */
    public synchronized void forceRunImmediately(boolean isIPv6) {
        forceRunSoon(isIPv6, 5*1000L);
    }

    /**
     *  Caller MUST also call schedule(), reschedule(),
     *  forceRunSoon(), or forceRunImmediately()
     */
    public synchronized void setIsAlive(boolean isAlive) {
        _alive = isAlive;
        if (!isAlive)
            cancel();
    }

    /**
     *  Set the last-tested timer to now
     *  @since 0.9.13
     */
    public void setLastTested(boolean isIPv6) {
        // do not synchronize - deadlock with PeerTestManager
        long now = _context.clock().now();
        if (isIPv6)
            _lastTestedV6.set(now);
        else
            _lastTested.set(now);
    }

    /**
     *  Whether we should run a test now.
     *  @return true if we should test
     */
    private boolean shouldTest() {
        String override = _context.getProperty(PROP_DISABLE_PEER_TEST);
        if ("true".equalsIgnoreCase(override))
            return false;
        return ! (_context.router().isHidden() ||
                  _context.router().gracefulShutdownInProgress() ||
                  (_transport.isIPv4Firewalled() && _transport.isIPv6Firewalled()));
    }
}
