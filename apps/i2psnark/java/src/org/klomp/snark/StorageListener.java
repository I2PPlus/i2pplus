/* StorageListener.java - Interface used as callback when storage changes.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

/**
 * Callback interface for monitoring Storage state changes and progress.
 *
 * <p>This interface is used by Storage to report:
 *
 * <ul>
 *   <li>File creation and allocation events</li>
 *   <li>Piece verification progress and results</li>
 *   <li>Storage completion status</li>
 *   <li>Status messages for user display</li>
 * </ul>
 *
 * @see Storage
 * @since 0.1.0
 */
interface StorageListener {
    /** Called when the storage creates a new file of a given length. */
    void storageCreateFile(Storage storage, String name, long length);

    /** Called to indicate that length bytes have been allocated. */
    void storageAllocated(Storage storage, long length);

    /**
     * Called when storage is being checked and the num piece of that total pieces has been checked.
     * When the piece hash matches the expected piece hash checked will be true, otherwise it will
     * be false.
     */
    void storageChecked(Storage storage, int num, boolean checked);

    /**
     * Called when all pieces in the storage have been checked. Does not mean that the storage is
     * complete, just that the state of the storage is known.
     */
    void storageAllChecked(Storage storage);

    /** Called the one time when the data is completely received and checked. */
    void storageCompleted(Storage storage);

    /** Reset the peer's wanted pieces table Call after the storage double-check fails */
    void setWantedPieces(Storage storage);

    void addMessage(String message);
}
