package net.i2p.router.web.helpers;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelServer;
import net.i2p.i2ptunnel.I2PTunnelTask;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;

/**
 * Tests {@link StreamHelper#countStreams}: open sockets are counted per
 * direction, closed sockets and closed tasks are skipped, and a null group
 * (router not yet initialized) yields null.
 *
 * @since 0.9.71
 */
public class StreamHelperCountTest {

    private I2PSocket socket(boolean closed) {
        I2PSocket sock = mock(I2PSocket.class);
        when(sock.isClosed()).thenReturn(closed);
        return sock;
    }

    private TunnelController controller(I2PTunnelTask task, I2PSocket... sockets) {
        TunnelController ctl = mock(TunnelController.class);
        I2PTunnel tunnel = mock(I2PTunnel.class);
        I2PSocketManager mgr = mock(I2PSocketManager.class);
        when(ctl.getTunnel()).thenReturn(tunnel);
        when(tunnel.getTasks()).thenReturn(Collections.singletonList(task));
        when(task.isOpen()).thenReturn(true);
        when(task.getSocketManager()).thenReturn(mgr);
        Set<I2PSocket> set = new HashSet<>(Arrays.asList(sockets));
        when(mgr.listSockets()).thenReturn(set);
        return ctl;
    }

    @Test
    public void nullGroupYieldsNull() {
        assertNull(StreamHelper.countStreams(null));
    }

    @Test
    public void countsOpenSocketsByDirection() {
        I2PTunnelServer inbound = mock(I2PTunnelServer.class);
        I2PTunnelTask outbound = mock(I2PTunnelTask.class);
        List<TunnelController> ctls = Arrays.asList(
                controller(inbound, socket(false), socket(true), socket(false)),
                controller(outbound, socket(false)));
        TunnelControllerGroup grp = mock(TunnelControllerGroup.class);
        when(grp.getControllers()).thenReturn(ctls);
        assertArrayEquals(new int[] {2, 1}, StreamHelper.countStreams(grp));
    }

    @Test
    public void closedTaskAndMissingTunnelSkipped() {
        TunnelController noTunnel = mock(TunnelController.class);
        when(noTunnel.getTunnel()).thenReturn(null);
        I2PTunnelTask task = mock(I2PTunnelTask.class);
        TunnelController closed = controller(task);
        when(task.isOpen()).thenReturn(false);
        TunnelControllerGroup grp = mock(TunnelControllerGroup.class);
        when(grp.getControllers()).thenReturn(Arrays.asList(noTunnel, closed));
        assertArrayEquals(new int[] {0, 0}, StreamHelper.countStreams(grp));
    }
}
