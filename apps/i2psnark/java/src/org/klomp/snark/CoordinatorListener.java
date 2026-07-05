/* CoordinatorListener.java - Callback when a peer changes state

   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

/**
 * Callback interface for monitoring PeerCoordinator state changes.
 *
 * <p>This interface is used to notify listeners of:
 *
 * <ul>
 *   <li>Peer state changes (connection, disconnection, choking, etc.)</li>
 *   <li>MetaInfo reception in magnet link mode</li>
 *   <li>Upload slot limit violations</li>
 *   <li>Status messages for user display</li>
 * </ul>
 *
 * @see PeerCoordinator
 * @since 0.1.0
 */
interface CoordinatorListener {
    /**
     * Called when the PeerCoordinator notices a change in the state of a peer.
     *
     * @param coordinator the PeerCoordinator reporting the change
     * @param peer the peer whose state changed
     */
    void peerChange(PeerCoordinator coordinator, Peer peer);

    /**
     * Called when the PeerCoordinator got the MetaInfo via magnet.
     *
     * @since 0.8.4
     */
    void gotMetaInfo(PeerCoordinator coordinator, MetaInfo metainfo);

    /**
     * Checks if the given number of uploaders exceeds the per-torrent upload limit.
     *
     * @param uploaders the current number of uploaders
     * @return true if over the limit, false otherwise
     */
    public boolean overUploadLimit(int uploaders);

    /**
     * Adds a status message for display to the user.
     *
     * @param message the message to display
     */
    public void addMessage(String message);
}
