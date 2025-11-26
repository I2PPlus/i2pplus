package net.i2p.data.router;

import static org.junit.Assert.*;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.StructureTest;
import net.i2p.util.OrderedProperties;

/**
 * Test harness for loading / storing RouterAddress objects.
 * Verifies proper handling of options, writes, equality, and string representation.
 */
public class RouterAddressTest extends StructureTest {

    @Override
    public DataStructure createDataStructure() throws DataFormatException {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        return new RouterAddress("Blah", options, 42);
    }

    @Override
    public DataStructure createStructureToRead() {
        return new RouterAddress();
    }

    @Test(expected = NullPointerException.class)
    public void testSetNullOptionsThrows() {
        RouterAddress addr = new RouterAddress();
        addr.setOptions(null);
    }

    @Test(expected = IllegalStateException.class)
    public void testSetOptionsAgainThrows() {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        RouterAddress addr = new RouterAddress("Blah", options, 42);

        // Attempt to set options again should throw
        options.setProperty("portnum", "2345");
        addr.setOptions(options);
    }

    @Test
    public void testBadWriteThrows() {
        RouterAddress addr = new RouterAddress();

        try {
            addr.writeBytes(new ByteArrayOutputStream());
            fail("Expected DataFormatException not thrown");
        } catch (DataFormatException e) {
            assertEquals("uninitialized", e.getMessage());
        } catch (IOException e) {
            fail("Unexpected IOException: " + e.getMessage());
        }
    }

    @Test
    public void testEqualsWithNullAndOtherObjects() {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        RouterAddress addr = new RouterAddress("Blah", options, 42);

        assertFalse(addr.equals(null));
        assertFalse(addr.equals("some string"));
    }

    @Test
    public void testToStringFormat() {
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        RouterAddress addr = new RouterAddress("Blah", options, 42);

        String expected = "Blah:\n" +
                          "\t* hostname: localhost\n" +
                          "\t* portnum: 1234\n" +
                          "\t* cost: 42";

        assertEquals(expected, addr.toString());
    }
}
