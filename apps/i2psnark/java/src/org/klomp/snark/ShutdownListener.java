/* ShutdownListener - Callback for end of shutdown sequence

   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

/** Callback for end of shutdown sequence. */
interface ShutdownListener {
    /** Called when the SnarkShutdown hook has finished shutting down all subcomponents. */
    void shutdown();
}
