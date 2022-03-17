package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;

import net.i2p.data.Destination;

/**
 * Interface for sending raw data to a SAM client
 */
interface SAMDatagramReceiver {

    /**
     * Send a byte array to a SAM client.
     *
     * @param sender Destination
     * @param data Byte array to be received
     * @param proto I2CP protocol
     * @param fromPort I2CP from port
     * @param toPort I2CP to port
     * @throws IOException
     */
    public void receiveDatagramBytes(Destination sender, byte data[], int proto, int fromPort, int toPort) throws IOException;

    /**
     * Stop receiving data.
     *
     */
    public void stopDatagramReceiving();
}
