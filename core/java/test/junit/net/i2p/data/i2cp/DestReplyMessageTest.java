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

/**
 * Test harness for loading / storing DestReplyMessage objects
 *
 * @author str4d
 */
public class DestReplyMessageTest extends I2CPTstBase {
    public I2CPMessageImpl createDataStructure() throws DataFormatException {
        DestReplyMessage msg = new DestReplyMessage();
        return msg;
    }
    public I2CPMessageImpl createStructureToRead() { return new DestReplyMessage(); }
}
