/* TrackerShutdown - Makes sure everything ends correctly when shutting down.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.IOException;
import net.i2p.util.I2PAppThread;

/**
 * Makes sure everything ends correctly when shutting down.
 *
 * @deprecated unused
 */
@Deprecated
public class SnarkShutdown extends I2PAppThread {
    private final Storage storage;
    private final PeerCoordinator coordinator;
    private final ConnectionAcceptor acceptor;
    private final TrackerClient trackerclient;

    private final ShutdownListener listener;

    /**
     * SnarkShutdown.
     *
     * @param storage the storage
     * @param coordinator the coordinator
     * @param acceptor the acceptor
     * @param trackerclient the tracker client
     * @param listener the shutdown listener
     */
    public SnarkShutdown(
            Storage storage,
            PeerCoordinator coordinator,
            ConnectionAcceptor acceptor,
            TrackerClient trackerclient,
            ShutdownListener listener) {
        this.storage = storage;
        this.coordinator = coordinator;
        this.acceptor = acceptor;
        this.trackerclient = trackerclient;
        this.listener = listener;
    }

    /**
     * Shutdown all components.
     */
    @Override
    public void run() {
        if (acceptor != null) acceptor.halt();

        if (trackerclient != null) trackerclient.halt(true);

        if (coordinator != null) coordinator.halt();

        if (storage != null) {
            try {
                storage.close();
            } catch (IOException ioe) {
                throw new RuntimeException("b0rking");
            }
        }

        try {
            Thread.sleep((long) 5 * 1000);
        } catch (InterruptedException ie) {
            /* ignored */
        }

        listener.shutdown();
    }
}
