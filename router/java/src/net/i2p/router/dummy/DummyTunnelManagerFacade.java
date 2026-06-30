package net.i2p.router.dummy;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.pool.GhostPeerManager;
import net.i2p.router.tunnel.pool.TunnelPool;

/**
 * Build and maintain tunnels throughout the network.
 *
 */
public class DummyTunnelManagerFacade implements TunnelManagerFacade {

    /** @deprecated unused */
    @Override
    public TunnelInfo selectInboundTunnel() { return null; }
    public TunnelInfo selectInboundTunnel(Hash destination) { return null; }
    @Override
    public TunnelInfo selectOutboundTunnel() { return null; }
    public TunnelInfo selectOutboundTunnel(Hash destination) { return null; }
    @Override
    public TunnelInfo selectInboundExploratoryTunnel(Hash closestTo) { return null; }
    public TunnelInfo selectInboundTunnel(Hash destination, Hash closestTo) { return null; }
    @Override
    public TunnelInfo selectOutboundExploratoryTunnel(Hash closestTo) { return null; }
    public TunnelInfo selectOutboundTunnel(Hash destination, Hash closestTo) { return null; }
    @Override
    public TunnelInfo selectAnyOutboundTunnel() { return null; }
    @Override
    public TunnelInfo selectAnyInboundTunnel() { return null; }

    @Override
    public boolean isValidTunnel(Hash client, TunnelInfo tunnel) { return false; }
    public int getParticipatingCount() { return 0; }
    @Override
    public int getFreeTunnelCount() { return 0; }
    public int getOutboundTunnelCount() { return 0; }
    @Override
    public int getInboundClientTunnelCount() { return 0; }
    public double getShareRatio() { return 0d; }
    @Override
    public int getOutboundClientTunnelCount() { return 0; }
    public int getOutboundClientTunnelCount(Hash destination) { return 0; }
    public int getInboundClientTunnelCount(Hash destination) { return 0; }
    public long getLastParticipatingExpiration() { return -1; }
    public void buildTunnels(Destination client, ClientTunnelSettings settings) { /* Intentionally empty - dummy implementation */ }
    @Override
    public void removeTunnels(Destination client) { /* Intentionally empty - dummy implementation */ }
    public boolean addAlias(Destination dest, ClientTunnelSettings settings, Destination existingClient) { return false; }
    @Override
    public void removeAlias(Destination dest) { /* Intentionally empty - dummy implementation */ }
    public TunnelPoolSettings getInboundSettings() { return null; }
    public TunnelPoolSettings getOutboundSettings() { return null; }
    public TunnelPoolSettings getInboundSettings(Hash client) { return null; }
    public TunnelPoolSettings getOutboundSettings(Hash client) { return null; }
    public void setInboundSettings(TunnelPoolSettings settings) { /* Intentionally empty - dummy implementation */ }
    public void setOutboundSettings(TunnelPoolSettings settings) { /* Intentionally empty - dummy implementation */ }
    public void setInboundSettings(Hash client, TunnelPoolSettings settings) { /* Intentionally empty - dummy implementation */ }
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings) { /* Intentionally empty - dummy implementation */ }
    public int getInboundBuildQueueSize() { return 0; }
    public Set<Hash> selectPeersInTooManyTunnels() { return null; }

    public void renderStatusHTML(Writer out) throws IOException { /* Intentionally empty - dummy implementation */ }
    public void restart() { /* Intentionally empty - dummy implementation */ }
    public void shutdown() { /* Intentionally empty - dummy implementation */ }
    public void startup() { /* Intentionally empty - dummy implementation */ }

    public void listPools(List<TunnelPool> out) { /* Intentionally empty - dummy implementation */ }
    public Map<Hash, TunnelPool> getInboundClientPools() { return null; }
    public Map<Hash, TunnelPool> getOutboundClientPools() { return null; }
    public TunnelPool getInboundExploratoryPool() { return null; }
    public TunnelPool getOutboundExploratoryPool() { return null; }
    public void fail(Hash peer) { /* Intentionally empty - dummy implementation */ }

    public TunnelPool getInboundPool(Hash client) {
        return null;
    }

    public TunnelPool getOutboundPool(Hash client) {
        return null;
    }

    public GhostPeerManager getGhostPeerManager() {
        return null;
    }
}
