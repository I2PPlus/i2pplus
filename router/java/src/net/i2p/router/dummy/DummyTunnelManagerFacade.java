package net.i2p.router.dummy;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.pool.GhostPeerManager;
import net.i2p.router.tunnel.pool.TunnelPool;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build and maintain tunnels throughout the network.
 *
 */
public class DummyTunnelManagerFacade implements TunnelManagerFacade {

    /** @deprecated unused */
    @Override
    public TunnelInfo selectInboundTunnel() {
        return null;
    }

    @Override
    public TunnelInfo selectInboundTunnel(Hash destination) {
        return null;
    }

    @Override
    public TunnelInfo selectOutboundTunnel() {
        return null;
    }

    @Override
    public TunnelInfo selectOutboundTunnel(Hash destination) {
        return null;
    }

    @Override
    public TunnelInfo selectInboundExploratoryTunnel(Hash closestTo) {
        return null;
    }

    @Override
    public TunnelInfo selectInboundTunnel(Hash destination, Hash closestTo) {
        return null;
    }

    @Override
    public TunnelInfo selectOutboundExploratoryTunnel(Hash closestTo) {
        return null;
    }

    @Override
    public TunnelInfo selectOutboundTunnel(Hash destination, Hash closestTo) {
        return null;
    }

    @Override
    public boolean isValidTunnel(Hash client, TunnelInfo tunnel) {
        return false;
    }

    @Override
    public int getParticipatingCount() {
        return 0;
    }

    @Override
    public int getFreeTunnelCount() {
        return 0;
    }

    @Override
    public int getOutboundTunnelCount() {
        return 0;
    }

    @Override
    public int getInboundClientTunnelCount() {
        return 0;
    }

    @Override
    public double getShareRatio() {
        return 0d;
    }

    @Override
    public int getOutboundClientTunnelCount() {
        return 0;
    }

    @Override
    public int getOutboundClientTunnelCount(Hash destination) {
        return 0;
    }

    @Override
    public int getInboundClientTunnelCount(Hash destination) {
        return 0;
    }

    @Override
    public long getLastParticipatingExpiration() {
        return -1;
    }

    @Override
    public void buildTunnels(Destination client, ClientTunnelSettings settings) {}

    @Override
    public void removeTunnels(Destination client) {}

    @Override
    public boolean addAlias(Destination dest, ClientTunnelSettings settings, Destination existingClient) {
        return false;
    }

    @Override
    public void removeAlias(Destination dest) {}

    @Override
    public TunnelPoolSettings getInboundSettings() {
        return null;
    }

    @Override
    public TunnelPoolSettings getOutboundSettings() {
        return null;
    }

    @Override
    public TunnelPoolSettings getInboundSettings(Hash client) {
        return null;
    }

    @Override
    public TunnelPoolSettings getOutboundSettings(Hash client) {
        return null;
    }

    @Override
    public void setInboundSettings(TunnelPoolSettings settings) {}

    @Override
    public void setOutboundSettings(TunnelPoolSettings settings) {}

    @Override
    public void setInboundSettings(Hash client, TunnelPoolSettings settings) {}

    @Override
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings) {}

    @Override
    public int getInboundBuildQueueSize() {
        return 0;
    }

    @Override
    public Set<Hash> selectPeersInTooManyTunnels() {
        return Collections.emptySet();
    }

    @Override
    public void renderStatusHTML(Writer out) throws IOException {}

    @Override
    public void restart() {}

    @Override
    public void shutdown() {}

    @Override
    public void startup() {}

    @Override
    public void listPools(List<TunnelPool> out) {}

    @Override
    public Map<Hash, TunnelPool> getInboundClientPools() {
        return Collections.emptyMap();
    }

    @Override
    public Map<Hash, TunnelPool> getOutboundClientPools() {
        return Collections.emptyMap();
    }

    @Override
    public TunnelPool getInboundExploratoryPool() {
        return null;
    }

    @Override
    public TunnelPool getOutboundExploratoryPool() {
        return null;
    }

    @Override
    public void fail(Hash peer) {}

    @Override
    public TunnelPool getInboundPool(Hash client) {
        return null;
    }

    @Override
    public TunnelPool getOutboundPool(Hash client) {
        return null;
    }

    @Override
    public GhostPeerManager getGhostPeerManager() {
        return null;
    }
}
