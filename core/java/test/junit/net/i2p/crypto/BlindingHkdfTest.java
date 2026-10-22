package net.i2p.crypto;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * The shared HKDF that every blinding operation goes through (A-3). The cache
 * is keyed on the context object and the method promises a non-null result, but
 * a null context matched the never-built cache slot (null == null), skipped the
 * build, and handed back null - the caller then failed lines later on an
 * unrelated line, with nothing pointing at the context.
 */
public class BlindingHkdfTest {

    /**
     * A context gets an HKDF, and the same context gets the same one back.
     */
    @Test
    public void testTheSameContextGetsTheSameHkdf() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        HKDF first = Blinding.hkdf(ctx);
        assertNotNull("no HKDF was built for a live context", first);
        assertSame("the HKDF was rebuilt for the same context", first, Blinding.hkdf(ctx));
    }

    /**
     * A second context is not served the first context's instance - the cache
     * is keyed on the context, not global.
     */
    @Test
    public void testADifferentContextGetsItsOwnHkdf() {
        I2PAppContext one = I2PAppContext.getGlobalContext();
        I2PAppContext two = new I2PAppContext();
        HKDF first = Blinding.hkdf(one);
        HKDF second = Blinding.hkdf(two);
        assertNotNull("no HKDF was built for the second context", second);
        assertNotSame("the second context was served the first context's HKDF", first, second);
    }

    /**
     * A null context fails at the entry point, not somewhere inside the caller.
     */
    @Test
    public void testANullContextIsRejectedAtTheEntry() {
        try {
            Blinding.hkdf(null);
            fail("a null context was accepted");
        } catch (NullPointerException e) {
            // The point of the check is where the failure is reported: this
            // method naming the context, not a field dereference somewhere
            // inside the HKDF it was about to build.
            assertTrue("the null context surfaced somewhere downstream: " + e,
                       e.getMessage() != null && e.getMessage().contains("context"));
        }
    }
}
