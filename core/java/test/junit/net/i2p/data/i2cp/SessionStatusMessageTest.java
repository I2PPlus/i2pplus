package net.i2p.data.i2cp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;

/**
 * Test harness for loading / storing SessionStatusMessage objects
 *
 * @author jrandom
 */
public class SessionStatusMessageTest extends I2CPTstBase {
    public I2CPMessageImpl createDataStructure() throws DataFormatException {
        SessionStatusMessage msg = new SessionStatusMessage();
        msg.setSessionId((SessionId)(new SessionIdTest()).createDataStructure());
        msg.setStatus(SessionStatusMessage.STATUS_CREATED);
        return msg; 
    }
    public I2CPMessageImpl createStructureToRead() { return new SessionStatusMessage(); }
}
