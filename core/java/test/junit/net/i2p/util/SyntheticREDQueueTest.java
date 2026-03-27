package net.i2p.util;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import org.junit.Test;

public class SyntheticREDQueueTest {

    private static final I2PAppContext ctx = I2PAppContext.getGlobalContext();

    @Test
    public void testConstructor() {
        SyntheticREDQueue q = new SyntheticREDQueue(ctx, 10000);
        assertEquals(10000, q.getMaxBandwidth());
    }

    @Test
    public void testConstructorWithThresholds() {
        SyntheticREDQueue q = new SyntheticREDQueue(ctx, 10000, 2500, 5000);
        assertEquals(10000, q.getMaxBandwidth());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorInvalidThresholds() {
        new SyntheticREDQueue(ctx, 10000, 5000, 2500);
    }

    @Test
    public void testOfferBelowThreshold() {
        SyntheticREDQueue q = new SyntheticREDQueue(ctx, 10000);
        // Small size should always be accepted
        for (int i = 0; i < 10; i++) {
            assertTrue(q.offer(10, 1.0f));
        }
    }

    @Test
    public void testOfferWithZeroFactor() {
        SyntheticREDQueue q = new SyntheticREDQueue(ctx, 10000);
        // factor=0 should never drop
        for (int i = 0; i < 100; i++) {
            assertTrue(q.offer(100000, 0f));
        }
    }

    @Test
    public void testAddSample() {
        SyntheticREDQueue q = new SyntheticREDQueue(ctx, 10000);
        for (int i = 0; i < 10; i++) {
            q.addSample(1000);
        }
        assertTrue(q.getBandwidthEstimate() > 0);
    }

    @Test
    public void testQueueSizeEstimate() {
        SyntheticREDQueue q = new SyntheticREDQueue(ctx, 10000);
        q.addSample(500);
        assertTrue(q.getQueueSizeEstimate() >= 0);
    }

    @Test
    public void testToString() {
        SyntheticREDQueue q = new SyntheticREDQueue(ctx, 10000);
        assertNotNull(q.toString());
    }
}
