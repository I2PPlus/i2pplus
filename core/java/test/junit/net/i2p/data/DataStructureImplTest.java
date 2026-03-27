package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Comwiz
 */
public class DataStructureImplTest {
    DataStructure _struct;

    @Before
    public void setUp() {
        _struct = new DataStructureImpl() {
                    private int x = 0;

                    @Override
                    public void writeBytes(OutputStream out) throws IOException, DataFormatException {
                        if (x++ == 0) throw new DataFormatException("let it enfold you", new Exception());
                        else throw new IOException();
                    }

                    @Override
                    public void readBytes(InputStream in) throws IOException {
                        throw new IOException();
                    }
                };
    }

    @Test
    public void toBase64ReturnsEmpty() {
        assertNotNull(_struct.toBase64());
    }

    @Test
    public void fromBase64ThrowsOnNull() {
        try {
            _struct.fromBase64(null);
            fail("exception not thrown");
        } catch (DataFormatException expected) {
            assertEquals("Null data passed in", expected.getMessage());
        }
    }

    @Test
    public void calculateHashReturnsHashOfEmpty() {
        assertNotNull(_struct.calculateHash());
    }

    @Test
    public void fromByteArrayThrowsOnNull() {
        try {
            _struct.fromByteArray(null);
            fail("exception not thrown");
        } catch (DataFormatException expected) {
            assertEquals("Null data passed in", expected.getMessage());
        }
    }

    @Test
    public void fromByteArrayThrowsOnError() {
        try {
            _struct.fromByteArray(DataHelper.getASCII("water is poison"));
            fail("exception not thrown");
        } catch (DataFormatException expected) {
            assertEquals("Error reading the byte array", expected.getMessage());
        }
    }

    @Test
    public void toByteArrayReturnsEmptyOnError() {
        assertArrayEquals(new byte[0], _struct.toByteArray());
    }
}
