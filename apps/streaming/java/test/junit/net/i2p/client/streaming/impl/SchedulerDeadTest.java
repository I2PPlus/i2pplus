package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;
import org.mockito.Mock;

public class SchedulerDeadTest extends TaskSchedulerTestBase {

    @Mock private Connection con;
    @Mock private ConnectionOptions opts;

    protected TaskScheduler createScheduler() {
        return new SchedulerDead(context);
    }

    @SuppressWarnings("unchecked")
    private void setMocks(int now, int discSchOn, int connTimeout, int lifetime, int sendStreamId) {
        doReturn((long) now).when(clock).now();
        doReturn((long) discSchOn).when(con).getDisconnectScheduledOn();
        doReturn(opts).when(con).getOptions();
        doReturn((long) connTimeout).when(opts).getConnectTimeout();
        doReturn((long) lifetime).when(con).getLifetime();
        doReturn((long) sendStreamId).when(con).getSendStreamId();
    }

    @Test
    public void testAccept_nothingLeftToDo() {
        setMocks(10 * 60 * 1000, 9 * 60 * 1000 - Connection.getDisconnectTimeout(), 0, 0, 0);
        assertTrue(scheduler.accept(con));
    }

    @Test
    public void testAccept_noDisconnectScheduled() {
        setMocks(10 * 60 * 1000, 0, 0, 0, 0);
        assertFalse(scheduler.accept(con));
    }

    @Test
    public void testAccept_timedOut() {
        setMocks(0, 0, Connection.getDisconnectTimeout() / 2, Connection.getDisconnectTimeout(), 0);
        assertTrue(scheduler.accept(con));
    }

    @Test
    public void testEventOccurred() {
        scheduler.eventOccurred(con);
        verify(con).disconnectComplete();
    }
}
