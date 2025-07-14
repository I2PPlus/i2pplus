/* CoordinatorListener.java - Callback when a peer changes state

   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

/**
 * Callback used when some peer changes state.
 */
interface CoordinatorListener
{
  /**
   * Called when the PeerCoordinator notices a change in the state of a peer.
   */
  void peerChange(PeerCoordinator coordinator, Peer peer);

  /**
   * Called when the PeerCoordinator got the MetaInfo via magnet.
   * @since 0.8.4
   */
  void gotMetaInfo(PeerCoordinator coordinator, MetaInfo metainfo);

  /**
   * Is this number of uploaders over the per-torrent limit?
   */
  public boolean overUploadLimit(int uploaders);

  public void addMessage(String message);
}
