/* TrackerShutdown - Makes sure everything ends correctly when shutting down.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import net.i2p.util.I2PAppThread;

import java.io.IOException;

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

    /* FIXME Exporting non-public type through public API FIXME */
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

    @Override
    public void run() {
        // Snark.debug("Shutting down...", Snark.NOTICE);

        // Snark.debug("Halting ConnectionAcceptor...", Snark.INFO);
        if (acceptor != null) acceptor.halt();

        // Snark.debug("Halting TrackerClient...", Snark.INFO);
        if (trackerclient != null) trackerclient.halt(true);

        // Snark.debug("Halting PeerCoordinator...", Snark.INFO);
        if (coordinator != null) coordinator.halt();

        // Snark.debug("Closing Storage...", Snark.INFO);
        if (storage != null) {
            try {
                storage.close();
            } catch (IOException ioe) {
                // I2PSnarkUtil.instance().debug("Couldn't properly close storage", Snark.ERROR,
                // ioe);
                throw new RuntimeException("b0rking");
            }
        }

        // XXX - Should actually wait till done...
        try {
            // Snark.debug("Waiting 5 seconds...", Snark.INFO);
            Thread.sleep(5 * 1000);
        } catch (InterruptedException ie) {
            /* ignored */
        }

        listener.shutdown();
    }
}
