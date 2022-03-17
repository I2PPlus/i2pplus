package net.i2p.data.i2cp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.StructureTest;
import net.i2p.data.DataStructure;
import net.i2p.data.DataFormatException;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class AbuseSeverityTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        AbuseSeverity sev = new AbuseSeverity();
        sev.setSeverity(64);
        return sev;
    }
    public DataStructure createStructureToRead() { return new AbuseSeverity(); }
}
