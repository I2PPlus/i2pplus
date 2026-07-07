/* PeerMonitorTasks - TimerTask that monitors the peers and total up/down speed
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

/**
 * TimerTask that monitors the peers and total up/download speeds. Works together with the main
 * Snark class to report periodical statistics.
 *
 * @deprecated unused, for command line client only, commented out in Snark.java
 */
@Deprecated
class PeerMonitorTask implements Runnable {
    static final long MONITOR_PERIOD = (long) 10 * 1000; // Ten seconds.


    PeerMonitorTask(PeerCoordinator coordinator) {
    }

    public void run() {
    }
}
