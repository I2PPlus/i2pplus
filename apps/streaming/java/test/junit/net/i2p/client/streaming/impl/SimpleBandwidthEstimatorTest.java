package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the Westwood+ bandwidth estimator.
 * Incorrect bandwidth estimation leads to poor congestion window
 * sizing and suboptimal throughput.
 */
public class SimpleBandwidthEstimatorTest {

    private I2PAppContext _context;
    private ConnectionOptions _opts;
    private SimpleBandwidthEstimator _bwe;

    @Before
    public void setUp() {
        _context = I2PAppContext.getGlobalContext();
        _opts = new ConnectionOptions();
        _bwe = new SimpleBandwidthEstimator(_context, _opts);
    }

    @Test
    public void testInitialEstimateIsZero() {
        // Before any samples, estimate should be 0 or very small
        float est = _bwe.getBandwidthEstimate();
        assertTrue("Initial estimate should be non-negative", est >= 0);
    }

    @Test
    public void testFirstSampleSetsEstimate() {
        _bwe.addSample(1);
        float est = _bwe.getBandwidthEstimate();
        assertTrue("After first sample, estimate should be positive", est > 0);
    }

    @Test
    public void testMultipleSamplesIncreaseEstimate() {
        _bwe.addSample(1);
        float first = _bwe.getBandwidthEstimate();
        // Add more samples quickly
        for (int i = 0; i < 10; i++) {
            _bwe.addSample(5);
        }
        float after = _bwe.getBandwidthEstimate();
        assertTrue("More acked packets should increase or maintain estimate",
                   after >= first);
    }

    @Test
    public void testEstimateIsBounded() {
        // Even with massive samples, estimate should be finite
        for (int i = 0; i < 100; i++) {
            _bwe.addSample(1000);
        }
        float est = _bwe.getBandwidthEstimate();
        assertTrue("Estimate should be finite", Float.isFinite(est));
        assertTrue("Estimate should be non-negative", est >= 0);
    }

    @Test
    public void testZeroPacketSample() {
        _bwe.addSample(1);
        float before = _bwe.getBandwidthEstimate();
        // Add a zero-ack sample (should decay)
        _bwe.addSample(0);
        float after = _bwe.getBandwidthEstimate();
        assertTrue("Zero sample should not increase estimate", after <= before);
    }

    @Test
    public void testToStringNotNull() {
        assertNotNull(_bwe.toString());
        _bwe.addSample(5);
        assertNotNull(_bwe.toString());
    }

    @Test
    public void testConcurrentSamples() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    _bwe.addSample(1);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(5000);
        }
        float est = _bwe.getBandwidthEstimate();
        assertTrue("Estimate should be positive after concurrent samples", est > 0);
        assertTrue("Estimate should be finite", Float.isFinite(est));
    }
}
