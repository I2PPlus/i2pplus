package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;
import net.i2p.data.Hash;
import net.i2p.router.peermanager.PeerTestJob;

/**
 * Manage peer references and keep them up to date so that when asked for peers,
 * it can provide appropriate peers according to the criteria provided.  This
 * includes periodically queueing up outbound messages to the peers to test them.
 *
 */
public interface PeerManagerFacade extends Service {
    /**
     * All peers with the given capability.
     *
     * @param capability the capability
     * @return the peers by capability
     */
    public Set<Hash> getPeersByCapability(char capability);
    /**
     * Count of peers with the given capability.
     *
     * @param capability the capability
     * @return the count
     */
    public int countPeersByCapability(char capability);
/**
     * The capabilities for a peer.
     *
     * @param peer the peer
     */
    public void setCapabilities(Hash peer, String caps);
    /**
     * Remove all capabilities from a peer.
     *
     * @param peer the peer
     */
    public void removeCapabilities(Hash peer);
    /**
     * The peer test job for testing peers.
     *
     * @return the peer test job
     */
    public PeerTestJob getPeerTestJob();
}
