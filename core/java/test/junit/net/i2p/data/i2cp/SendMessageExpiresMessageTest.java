package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by str4d in 2012 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DateAndFlags;
import net.i2p.data.DateAndFlagsTest;
import net.i2p.data.Destination;
import net.i2p.data.DestinationTest;
import net.i2p.data.Payload;
import net.i2p.data.PayloadTest;

/**
 * Test harness for loading / storing SendMessageExpiresMessage objects.
 *
 */
public class SendMessageExpiresMessageTest extends I2CPTstBase {

    @Override
    public I2CPMessageImpl createDataStructure() throws DataFormatException {
        DateAndFlags daf = (DateAndFlags) (new DateAndFlagsTest()).createDataStructure();
        return new SendMessageExpiresMessage((SessionId) (new SessionIdTest()).createDataStructure(),
                                             (Destination) (new DestinationTest()).createDataStructure(),
                                             (Payload) (new PayloadTest()).createDataStructure(),
                                             1, daf);
    }

    @Override
    public I2CPMessageImpl createStructureToRead() {
        return new SendMessageExpiresMessage();
    }
}
