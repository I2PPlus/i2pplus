package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import net.i2p.I2PAppContext;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.TunnelId;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.data.i2cp.RequestVariableLeaseSetMessage;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;
import net.i2p.util.RandomSource;
import net.i2p.util.SimpleTimer2;

/**
 * Mutation-ordering guards for the LeaseSet request handlers: an empty or
 * rejected request must not advance the monotonic LS2 publish floor, a
 * valid request must advance the floor only after signing succeeds, and
 * the lease end is floored against both the published stamp and the
 * current time.
 */
public class RequestLeaseSetHandlerTest {

    /** Just past a second boundary so rounding and the floor are both visible. */
    private static final long T0 = 1_700_000_000_456L;

    private I2PAppContext _ctx;
    private FakeSession _session;
    private SimpleTimer2 _timer;
    private File _tmpDir;

    @Before
    public void setUp() {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-ls2order-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());
        _ctx = mock(I2PAppContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString()))
                .thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);
        when(_ctx.statManager()).thenReturn(mock(StatManager.class));
        _timer = mock(SimpleTimer2.class);
        when(_ctx.simpleTimer2()).thenReturn(_timer);
        RandomSource rnd = mock(RandomSource.class);
        when(_ctx.random()).thenReturn(rnd);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(T0);
        when(_ctx.clock()).thenReturn(clock);
        _session = new FakeSession(_ctx);
    }

    @After
    public void tearDown() {
        deleteRecursively(_tmpDir);
    }

    /**
     * An empty request fails before any mutation: lastLS2SignTime stays 0
     * and the error is surfaced to the session.
     */
    @Test
    public void emptyRequestDoesNotAdvancePublishFloor() {
        RequestLeaseSetMessageHandler handler = new RequestLeaseSetMessageHandler(_ctx);
        handler.handleMessage(new RequestLeaseSetMessage(), _session);

        assertEquals(0, _session.getLastLS2SignTime());
        assertEquals(1, _session.errors.size());
        assertTrue(_session.errors.get(0).contains("no leases"));
    }

    /** Same guard in the variable-endpoint subclass. */
    @Test
    public void emptyVariableRequestDoesNotAdvancePublishFloor() {
        RequestVariableLeaseSetMessageHandler handler = new RequestVariableLeaseSetMessageHandler(_ctx);
        handler.handleMessage(new RequestVariableLeaseSetMessage(), _session);

        assertEquals(0, _session.getLastLS2SignTime());
        assertEquals(1, _session.errors.size());
        assertTrue(_session.errors.get(0).contains("no leases"));
    }

    /**
     * A valid request publishes the rounded stamp before signing, but the
     * monotonic floor advances only after signLeaseSet() succeeds; the
     * lease end is floored against published and now.
     */
    @Test
    public void validRequestPublishesAndStampsFloorAfterSigning() {
        CapturingHandler handler = new CapturingHandler(_ctx);
        RequestLeaseSetMessage msg = new RequestLeaseSetMessage();
        msg.setEndDate(new Date(T0 - 5_000L));
        msg.addEndpoint(Hash.FAKE_HASH, new TunnelId(1));
        handler.handleMessage(msg, _session);

        assertTrue(_session.errors.toString(), _session.errors.isEmpty());
        long expectedPublished = ((T0 + 500) / 1000) * 1000;
        LeaseSet2 ls2 = (LeaseSet2) handler.captured;
        assertEquals(expectedPublished, ls2.getPublished());
        assertEquals(0, handler.capturedSignTimeAtSign);
        assertEquals(T0, _session.getLastLS2SignTime());

        Lease lease = ls2.getLease(0);
        long expectedEnd = RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(
                T0 - 5_000L, expectedPublished, T0);
        assertEquals(expectedEnd, lease.getEndTime());
        assertTrue(lease.getEndTime() > ls2.getPublished());
    }

    /**
     * The monotonic floor from a previous sign is respected: the next
     * published stamp is at least one second past the stored sign time,
     * rounded to the second.
     */
    @Test
    public void monotonicFloorFromPreviousSignTimeIsRespected() {
        _session.setLastLS2SignTime(T0 + 30_000L);
        CapturingHandler handler = new CapturingHandler(_ctx);
        RequestLeaseSetMessage msg = new RequestLeaseSetMessage();
        msg.setEndDate(new Date(T0 + 300_000L));
        msg.addEndpoint(Hash.FAKE_HASH, new TunnelId(2));
        handler.handleMessage(msg, _session);

        long rawNext = T0 + 31_000L;
        long expectedPublished = ((rawNext + 500) / 1000) * 1000;
        LeaseSet2 ls2 = (LeaseSet2) handler.captured;
        assertEquals(expectedPublished, ls2.getPublished());
        assertTrue(ls2.getPublished() > T0);
        assertEquals(rawNext, _session.getLastLS2SignTime());
        assertTrue(_session.errors.isEmpty());
    }

    /**
     * More than MAX_LEASES endpoints would throw from addLease() mid-build;
     * validation must reject them before any state or floor is touched.
     */
    @Test
    public void overCapacityRequestDoesNotAdvancePublishFloor() {
        RequestLeaseSetMessageHandler handler = new RequestLeaseSetMessageHandler(_ctx);
        RequestLeaseSetMessage msg = new RequestLeaseSetMessage();
        msg.setEndDate(new Date(T0 + 300_000L));
        for (int i = 0; i < LeaseSet.MAX_LEASES + 1; i++) {
            msg.addEndpoint(Hash.FAKE_HASH, new TunnelId(i + 1));
        }
        handler.handleMessage(msg, _session);

        assertEquals(0, _session.getLastLS2SignTime());
        assertEquals(1, _session.errors.size());
        assertTrue(_session.errors.get(0).contains("too many leases"));
    }

    /** Same over-capacity guard in the variable-endpoint subclass. */
    @Test
    public void overCapacityVariableRequestDoesNotAdvancePublishFloor() {
        RequestVariableLeaseSetMessageHandler handler = new RequestVariableLeaseSetMessageHandler(_ctx);
        RequestVariableLeaseSetMessage msg = new RequestVariableLeaseSetMessage();
        for (int i = 0; i < LeaseSet.MAX_LEASES + 1; i++) {
            msg.addEndpoint(sampleLease(i + 1));
        }
        handler.handleMessage(msg, _session);

        assertEquals(0, _session.getLastLS2SignTime());
        assertEquals(1, _session.errors.size());
        assertTrue(_session.errors.get(0).contains("too many leases"));
    }

    /**
     * A wire request with no end date (0 on the wire reads as null) would
     * NPE at msg.getEndDate().getTime() during the lease loop; validation
     * must reject it first.
     */
    @Test
    public void requestWithNoEndDateDoesNotAdvancePublishFloor() {
        RequestLeaseSetMessageHandler handler = new RequestLeaseSetMessageHandler(_ctx);
        RequestLeaseSetMessage msg = new RequestLeaseSetMessage();
        msg.addEndpoint(Hash.FAKE_HASH, new TunnelId(1));
        handler.handleMessage(msg, _session);

        assertEquals(0, _session.getLastLS2SignTime());
        assertEquals(1, _session.errors.size());
        assertTrue(_session.errors.get(0).contains("no end date"));
    }

    /**
     * The floor commit is conditional on a successful sign: a handler whose
     * signLeaseSet() fails must leave lastLS2SignTime untouched.
     */
    @Test
    public void signFailureDoesNotAdvancePublishFloor() {
        FailingHandler handler = new FailingHandler(_ctx);
        RequestLeaseSetMessage msg = new RequestLeaseSetMessage();
        msg.setEndDate(new Date(T0 + 300_000L));
        msg.addEndpoint(Hash.FAKE_HASH, new TunnelId(4));
        handler.handleMessage(msg, _session);

        assertEquals(0, _session.getLastLS2SignTime());
        assertEquals(1, _session.errors.size());
        assertTrue(_session.errors.get(0).contains("sign failed"));
    }

    /**
     * An encrypted LS2 reports 0 leases before decryption; the signability
     * guard must still route it to signLeaseSet() and, on success, stamp
     * the floor.
     */
    @Test
    public void encryptedLs2RequestReachesSignAndStampsFloor() {
        _session.getOptions().setProperty(RequestLeaseSetMessageHandler.PROP_LS_TYPE,
                Integer.toString(DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2));
        CapturingHandler handler = new CapturingHandler(_ctx);
        RequestLeaseSetMessage msg = new RequestLeaseSetMessage();
        msg.setEndDate(new Date(T0 + 300_000L));
        msg.addEndpoint(Hash.FAKE_HASH, new TunnelId(5));
        handler.handleMessage(msg, _session);

        assertTrue(_session.errors.toString(), _session.errors.isEmpty());
        assertTrue(handler.captured instanceof EncryptedLeaseSet);
        assertEquals(0, handler.capturedSignTimeAtSign);
        assertEquals(T0, _session.getLastLS2SignTime());
    }

    /**
     * Transient classification: expiry and empty-request messages are
     * re-runnable; permanent key/config errors and null are not.
     */
    @Test
    public void transientSignFailureClassification() {
        assertTrue(RequestLeaseSetMessageHandler.isTransientSignFailure("LeaseSet expired 5 seconds ago"));
        assertTrue(RequestLeaseSetMessageHandler.isTransientSignFailure("no leases"));
        assertFalse(RequestLeaseSetMessageHandler.isTransientSignFailure("bad key material"));
        assertFalse(RequestLeaseSetMessageHandler.isTransientSignFailure(null));
    }

    /**
     * A scheduled transient retry re-runs the same request once the timer
     * fires; nothing happens before the timer, and the re-run surfaces the
     * same validation error instead of being silently dropped.
     */
    @Test
    public void transientSignRetryRerunsTheRequest() {
        RequestLeaseSetMessageHandler handler = new RequestLeaseSetMessageHandler(_ctx);
        RequestLeaseSetMessage msg = new RequestLeaseSetMessage();
        handler.scheduleSignRetry(msg, _session, 0);

        ArgumentCaptor<SimpleTimer2.TimedEvent> captor = ArgumentCaptor.forClass(SimpleTimer2.TimedEvent.class);
        verify(_timer).addEvent(captor.capture(), eq(RequestLeaseSetMessageHandler.TRANSIENT_SIGN_RETRY_DELAY_MS));
        assertTrue(_session.errors.isEmpty());
        captor.getValue().timeReached();

        assertEquals(1, _session.errors.size());
        assertTrue(_session.errors.get(0).contains("no leases"));
    }

    /**
     * The bound: once MAX_TRANSIENT_SIGN_RETRIES attempts are exhausted no
     * further timer is scheduled; the router's check timeout remains the fallback.
     */
    @Test
    public void transientSignRetryStopsAtTheBound() {
        RequestLeaseSetMessageHandler handler = new RequestLeaseSetMessageHandler(_ctx);
        handler.scheduleSignRetry(new RequestLeaseSetMessage(), _session,
                RequestLeaseSetMessageHandler.MAX_TRANSIENT_SIGN_RETRIES);
        verify(_timer, never()).addEvent(any(SimpleTimer2.TimedEvent.class), anyLong());
    }

    /**
     * A session closed while the retry was pending must not be re-entered.
     */
    @Test
    public void transientSignRetrySkipsClosedSession() {
        RequestLeaseSetMessageHandler handler = new RequestLeaseSetMessageHandler(_ctx);
        _session.closed = true;
        handler.scheduleSignRetry(new RequestLeaseSetMessage(), _session, 0);

        ArgumentCaptor<SimpleTimer2.TimedEvent> captor = ArgumentCaptor.forClass(SimpleTimer2.TimedEvent.class);
        verify(_timer).addEvent(captor.capture(), eq(RequestLeaseSetMessageHandler.TRANSIENT_SIGN_RETRY_DELAY_MS));
        captor.getValue().timeReached();

        assertTrue(_session.errors.isEmpty());
    }

    private static Lease sampleLease(int id) {
        Lease lease = new Lease();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(id));
        lease.setEndDate(new Date(T0 + 300_000L));
        return lease;
    }

    /** Session double: real sign-time state, recorded error surface. */
    private static class FakeSession extends I2PSessionImpl2 {
        final List<String> errors = new ArrayList<>();
        /** Fresh sessions are INIT (closed); tests flip this to model a live session. */
        boolean closed;

        FakeSession(I2PAppContext ctx) {
            super(ctx, new Properties(), null);
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public boolean supportsLS2() {
            return true;
        }

        @Override
        void propagateError(String msg, Throwable error) {
            errors.add(msg);
        }

        @Override
        public void destroySession() {
            errors.add("destroySession");
        }
    }

    /** Captures the set at sign time instead of running real crypto. */
    private static class CapturingHandler extends RequestLeaseSetMessageHandler {
        LeaseSet captured;
        long capturedSignTimeAtSign;

        CapturingHandler(I2PAppContext ctx) {
            super(ctx);
        }

        @Override
        protected synchronized boolean signLeaseSet(LeaseSet leaseSet, boolean isLS2, I2PSessionImpl session,
                                                    I2CPMessage message, int attempt) {
            captured = leaseSet;
            capturedSignTimeAtSign = session.getLastLS2SignTime();
            return true;
        }
    }

    /** Fails the sign instead of running it, to test the floor commit. */
    private static class FailingHandler extends RequestLeaseSetMessageHandler {
        FailingHandler(I2PAppContext ctx) {
            super(ctx);
        }

        @Override
        protected synchronized boolean signLeaseSet(LeaseSet leaseSet, boolean isLS2, I2PSessionImpl session,
                                                    I2CPMessage message, int attempt) {
            session.propagateError("sign failed", new IllegalStateException("sign failed"));
            return false;
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
