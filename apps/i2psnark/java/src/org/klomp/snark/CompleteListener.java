/* CompleteListener - Callback for Snark events

   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import org.klomp.snark.comments.CommentSet;

/**
 * Callback for Snark events.
 *
 * @since 0.9.4 moved from Snark.java
 */
public interface CompleteListener {
    /**
     * Called when all pieces of the torrent have been downloaded and verified.
     *
     * @param snark the Snark instance that completed
     */
    public void torrentComplete(Snark snark);

    /**
     * Called when the torrent status should be updated (e.g., peer count changed).
     *
     * @param snark the Snark instance to update status for
     */
    public void updateStatus(Snark snark);

    /**
     * We transitioned from magnet mode, we have now initialized our metainfo and storage. The
     * listener should now call getMetaInfo() and save the data to disk.
     *
     * @return the new name for the torrent or null on error
     * @since 0.8.4
     */
    public String gotMetaInfo(Snark snark);

    /**
     * @since 0.9
     */
    public void fatal(Snark snark, String error);

    /**
     * @since 0.9.2
     */
    public void addMessage(Snark snark, String message);

    /**
     * @since 0.9.4
     */
    public void gotPiece(Snark snark);

    /**
     * Returns the saved time for the torrent's last modification.
     *
     * @param snark the Snark instance
     * @return the saved torrent time in milliseconds
     */
    public long getSavedTorrentTime(Snark snark);

    /**
     * Returns the saved bitfield for the torrent.
     *
     * @param snark the Snark instance
     * @return the saved bitfield, or null if none
     */
    public BitField getSavedTorrentBitField(Snark snark);

    /**
     * @since 0.9.15
     */
    public boolean getSavedPreserveNamesSetting(Snark snark);

    /**
     * @since 0.9.15
     */
    public long getSavedUploaded(Snark snark);

    /**
     * @since 0.9.31
     */
    public CommentSet getSavedComments(Snark snark);

    /**
     * @since 0.9.31
     */
    public void locked_saveComments(Snark snark, CommentSet comments);

    /**
     * @since 0.9.42
     */
    public boolean shouldAutoStart();

    /**
     * @since 0.9.62
     */
    public BandwidthListener getBandwidthListener();
}
