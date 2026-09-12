package net.i2p.router;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;

import net.i2p.router.networkdb.kademlia.IterativeSearchJob;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.transport.udp.EstablishmentManager;
import net.i2p.router.transport.udp.PeerState;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.router.tunnel.pool.BuildExecutor;
import net.i2p.router.tunnel.pool.BuildHandler;
import net.i2p.router.tunnel.pool.BuildRequestor;
import net.i2p.router.tunnel.pool.TestJob;
import net.i2p.util.Clock;
import net.i2p.util.SystemVersion;

/**
 * Contract tests for the "tuned value overrides config" statics added so the
 * Tuner no longer writes live-settable params back into router.config.
 *
 * <p>Each consumer exposes a {@code setX}/{@code getX(ctx, def)} pair that
 * returns the tuned value once set, and otherwise falls back to
 * {@code ctx.getProperty(name, def)}. This keeps a first boot identical to the
 * old config-driven behavior while giving the Tuner a config-free write path.
 *
 * <p>Coverage: tuned-overrides-config, unset-falls-back-to-config, and
 * default passthrough for every Batch 1 consumer.
 *
 * @since 0.9.72+
 */
public class TunerStaticOverrideTest {

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        // The cached-config consumers read ctx.clock().now() on first refresh;
        // a fresh Clock mock keeps refresh deterministic and off the real clock.
        when(_ctx.clock()).thenReturn(mock(Clock.class));
        // The -1 sentinel must be restored before each test so a leaked tuned
        // value from an earlier case can't bleed into a fallback assertion.
        PeerState.setOutboundQueueSize(-1);
        RouterThrottleImpl.setMaxProcessingTime(-1);
        BuildExecutor.setTunnelTargetBuffer(-1);
        BuildExecutor.setGoodDeficitThrottle(-1L);
        IterativeSearchJob.setSearchLimit(-1);
        IterativeSearchJob.setSingleSearchTime(-1);
        EstablishmentManager.setMaxConcurrentEstablish(-1);
        TunnelDispatcher.setTransitThrottleFactor(-1.0f);
        RouterThrottleImpl.setTunnelGrowthFactor(-1.0d);
        ProfileOrganizer.setLossyThreshold(-1.0f);
        BuildHandler.setNextHopLookupTimeout(-1);
        BuildHandler.setMaxLookupLimit(-1);
        BuildHandler.setPercentLookupLimit(-1);
        BuildRequestor.setRequestTimeout(-1);
        BuildRequestor.setFirstHopTimeout(-1);
        TestJob.setMinTestPeriod(-1);
        TestJob.setMaxTestPeriod(-1);
        TestJob.setMinTestDelay(-1);
        TestJob.setMaxTestDelay(-1);
    }

    // =====================================================================
    // PeerState.router.peerOutboundQueueSize
    // =====================================================================

    @Test
    public void peerStateReturnsTunedOverConfig() {
        PeerState.setOutboundQueueSize(700);
        assertEquals(700, PeerState.getOutboundQueueSize(_ctx, 64));
    }

    @Test
    public void peerStateUnsetFallsBackToConfig() {
        when(_ctx.getProperty("router.peerOutboundQueueSize", 64)).thenReturn(128);
        assertEquals(128, PeerState.getOutboundQueueSize(_ctx, 64));
    }

    @Test
    public void peerStateUnsetPassthroughDefault() {
        when(_ctx.getProperty("router.peerOutboundQueueSize", 64)).thenReturn(64);
        assertEquals(64, PeerState.getOutboundQueueSize(_ctx, 64));
    }

    // =====================================================================
    // RouterThrottleImpl.router.defaultProcessingTimeThrottle
    // =====================================================================

    @Test
    public void throttleReturnsTunedOverConfig() {
        RouterThrottleImpl.setMaxProcessingTime(5000);
        assertEquals(5000, RouterThrottleImpl.getMaxProcessingTimeTuned(_ctx));
    }

    @Test
    public void throttleUnsetFallsBackToConfig() {
        int def = SystemVersion.isSlow() ? 3000 : 2000;
        when(_ctx.getProperty("router.defaultProcessingTimeThrottle", def)).thenReturn(4000);
        assertEquals(4000, RouterThrottleImpl.getMaxProcessingTimeTuned(_ctx));
    }

    // =====================================================================
    // BuildExecutor.i2p.tunnel.targetBuffer / goodDeficitThrottle
    // =====================================================================

    @Test
    public void targetBufferReturnsTunedOverConfig() {
        BuildExecutor.setTunnelTargetBuffer(3);
        assertEquals(3, BuildExecutor.getTunnelTargetBuffer(_ctx));
    }

    @Test
    public void targetBufferUnsetFallsBackToConfig() {
        when(_ctx.getProperty("i2p.tunnel.targetBuffer", 0)).thenReturn(2);
        assertEquals(2, BuildExecutor.getTunnelTargetBuffer(_ctx));
    }

    @Test
    public void targetBufferUnsetPassthroughDefault() {
        assertEquals(0, BuildExecutor.getTunnelTargetBuffer(_ctx));
    }

    @Test
    public void goodDeficitReturnsTunedOverConfig() {
        BuildExecutor.setGoodDeficitThrottle(60000L);
        assertEquals(60000L, BuildExecutor.getGoodDeficitThrottle(_ctx));
    }

    @Test
    public void goodDeficitUnsetFallsBackToConfig() {
        when(_ctx.getProperty("i2p.tunnel.goodDeficitThrottle", 30000)).thenReturn(45000);
        assertEquals(45000L, BuildExecutor.getGoodDeficitThrottle(_ctx));
    }

    @Test
    public void goodDeficitUnsetPassthroughDefault() {
        when(_ctx.getProperty("i2p.tunnel.goodDeficitThrottle", 30000)).thenReturn(30000);
        assertEquals(30000L, BuildExecutor.getGoodDeficitThrottle(_ctx));
    }

    // =====================================================================
    // IterativeSearchJob.netdb.searchLimit / singleSearchTime
    // =====================================================================

    @Test
    public void searchLimitReturnsTunedOverConfig() {
        IterativeSearchJob.setSearchLimit(12);
        assertEquals(12, IterativeSearchJob.getSearchLimit(_ctx, 16));
    }

    @Test
    public void searchLimitUnsetFallsBackToConfig() {
        when(_ctx.getProperty("netdb.searchLimit", 16)).thenReturn(20);
        assertEquals(20, IterativeSearchJob.getSearchLimit(_ctx, 16));
    }

    @Test
    public void searchLimitUnsetPassthroughDefault() {
        when(_ctx.getProperty("netdb.searchLimit", 16)).thenReturn(16);
        assertEquals(16, IterativeSearchJob.getSearchLimit(_ctx, 16));
    }

    @Test
    public void singleSearchTimeReturnsTunedOverConfig() {
        IterativeSearchJob.setSingleSearchTime(12000);
        assertEquals(12000, IterativeSearchJob.getSingleSearchTime(_ctx, 6000));
    }

    @Test
    public void singleSearchTimeUnsetFallsBackToConfig() {
        when(_ctx.getProperty("netdb.singleSearchTime", 6000)).thenReturn(8000);
        assertEquals(8000, IterativeSearchJob.getSingleSearchTime(_ctx, 6000));
    }

    @Test
    public void singleSearchTimeUnsetPassthroughDefault() {
        when(_ctx.getProperty("netdb.singleSearchTime", 6000)).thenReturn(6000);
        assertEquals(6000, IterativeSearchJob.getSingleSearchTime(_ctx, 6000));
    }

    // =====================================================================
    // EstablishmentManager.i2np.udp.maxConcurrentEstablish
    // =====================================================================

    @Test
    public void maxConcurrentEstablishReturnsTunedOverConfig() {
        EstablishmentManager.setMaxConcurrentEstablish(128);
        assertEquals(128, EstablishmentManager.getMaxConcurrentEstablishTuned(_ctx));
    }

    @Test
    public void maxConcurrentEstablishUnsetFallsBackToConfig() {
        int def = SystemVersion.isSlow() ? 128 : 512;
        when(_ctx.getProperty("i2np.udp.maxConcurrentEstablish", def)).thenReturn(96);
        assertEquals(96, EstablishmentManager.getMaxConcurrentEstablishTuned(_ctx));
    }

    // =====================================================================
    // TunnelDispatcher.router.transitThrottleFactor (float)
    // =====================================================================

    @Test
    public void transitThrottleReturnsTunedOverConfig() {
        TunnelDispatcher.setTransitThrottleFactor(0.75f);
        assertEquals(0.75f, TunnelDispatcher.getTransitThrottleFactor(_ctx, 0.95f), 0.0001f);
    }

    @Test
    public void transitThrottleUnsetFallsBackToConfig() {
        when(_ctx.getProperty("router.transitThrottleFactor")).thenReturn("0.80");
        assertEquals(0.80f, TunnelDispatcher.getTransitThrottleFactor(_ctx, 0.95f), 0.0001f);
    }

    @Test
    public void transitThrottleUnsetPassthroughDefault() {
        when(_ctx.getProperty("router.transitThrottleFactor")).thenReturn("0.95");
        assertEquals(0.95f, TunnelDispatcher.getTransitThrottleFactor(_ctx, 0.95f), 0.0001f);
    }

    @Test
    public void transitThrottleTunedOverridesInboundDefault() {
        TunnelDispatcher.setTransitThrottleFactor(0.60f);
        assertEquals(0.60f, TunnelDispatcher.getTransitThrottleFactor(_ctx, 0.0f), 0.0001f);
    }

    @Test
    public void transitThrottleInboundUnsetDefaultZero() {
        when(_ctx.getProperty("router.transitThrottleFactor")).thenReturn("0.0");
        assertEquals(0.0f, TunnelDispatcher.getTransitThrottleFactor(_ctx, 0.0f), 0.0001f);
    }

    // =====================================================================
    // RouterThrottleImpl.router.tunnelGrowthFactor (double)
    // =====================================================================

    @Test
    public void tunnelGrowthReturnsTunedOverConfig() {
        RouterThrottleImpl.setTunnelGrowthFactor(1.5d);
        assertEquals(1.5d, RouterThrottleImpl.getTunnelGrowthFactorTuned(_ctx), 0.0001d);
    }

    @Test
    public void tunnelGrowthUnsetFallsBackToConfig() {
        when(_ctx.getProperty("router.tunnelGrowthFactor")).thenReturn("3.0");
        assertEquals(3.0d, RouterThrottleImpl.getTunnelGrowthFactorTuned(_ctx), 0.0001d);
    }

    @Test
    public void tunnelGrowthUnsetPassthroughDefault() {
        assertEquals(2.0d, RouterThrottleImpl.getTunnelGrowthFactorTuned(_ctx), 0.0001d);
    }

    @Test
    public void tunnelGrowthConfigGarbageFallsBackToDefault() {
        when(_ctx.getProperty("router.tunnelGrowthFactor")).thenReturn("banana");
        assertEquals(2.0d, RouterThrottleImpl.getTunnelGrowthFactorTuned(_ctx), 0.0001d);
    }

    // =====================================================================
    // ProfileOrganizer.profileOrganizer.lossyThreshold (float)
    // =====================================================================

    @Test
    public void lossyThresholdReturnsTunedOverConfig() {
        ProfileOrganizer.setLossyThreshold(0.10f);
        assertEquals(0.10f, ProfileOrganizer.getLossyThreshold(_ctx), 0.0001f);
    }

    @Test
    public void lossyThresholdUnsetFallsBackToConfig() {
        when(_ctx.getProperty("profileOrganizer.lossyThreshold")).thenReturn("0.30");
        assertEquals(0.30f, ProfileOrganizer.getLossyThreshold(_ctx), 0.0001f);
    }

    @Test
    public void lossyThresholdUnsetPassthroughDefault() {
        when(_ctx.getProperty("profileOrganizer.lossyThreshold")).thenReturn("0.20");
        assertEquals(0.20f, ProfileOrganizer.getLossyThreshold(_ctx), 0.0001f);
    }

    // =====================================================================
    // BuildHandler.i2p.tunnel.build.nextHopLookupTimeout (cached consumer)
    // =====================================================================

    @Test
    public void nextHopLookupTimeoutReturnsTunedOverConfig() {
        BuildHandler.setNextHopLookupTimeout(5000);
        assertEquals(5000, BuildHandler.getNextHopLookupTimeout(_ctx));
    }

    @Test
    public void nextHopLookupTimeoutUnsetFallsBackToConfig() {
        when(_ctx.getProperty("i2p.tunnel.build.nextHopLookupTimeout", 3000)).thenReturn(4000);
        assertEquals(4000, BuildHandler.getNextHopLookupTimeout(_ctx));
    }

    @Test
    public void nextHopLookupTimeoutUnsetPassthroughDefault() {
        when(_ctx.getProperty("i2p.tunnel.build.nextHopLookupTimeout", 3000)).thenReturn(3000);
        assertEquals(3000, BuildHandler.getNextHopLookupTimeout(_ctx));
    }

    // =====================================================================
    // BuildHandler.i2p.tunnel.build.maxLookupLimit (cached consumer)
    // =====================================================================

    @Test
    public void maxLookupLimitReturnsTunedOverConfig() {
        BuildHandler.setMaxLookupLimit(40);
        assertEquals(40, BuildHandler.getMaxLookupLimit(_ctx));
    }

    @Test
    public void maxLookupLimitUnsetFallsBackToConfig() {
        int def = SystemVersion.isSlow() ? 32 : 64;
        when(_ctx.getProperty("i2p.tunnel.build.maxLookupLimit", def)).thenReturn(48);
        assertEquals(48, BuildHandler.getMaxLookupLimit(_ctx));
    }

    @Test
    public void maxLookupLimitUnsetPassthroughDefault() {
        int def = SystemVersion.isSlow() ? 32 : 64;
        when(_ctx.getProperty("i2p.tunnel.build.maxLookupLimit", def)).thenReturn(def);
        assertEquals(def, BuildHandler.getMaxLookupLimit(_ctx));
    }

    // =====================================================================
    // BuildHandler.i2p.tunnel.build.percentLookupLimit (cached consumer)
    // =====================================================================

    @Test
    public void percentLookupLimitReturnsTunedOverConfig() {
        BuildHandler.setPercentLookupLimit(30);
        assertEquals(30, BuildHandler.getPercentLookupLimit(_ctx));
    }

    @Test
    public void percentLookupLimitUnsetFallsBackToConfig() {
        int def = SystemVersion.isSlow() ? 15 : 40;
        when(_ctx.getProperty("i2p.tunnel.build.percentLookupLimit", def)).thenReturn(25);
        assertEquals(25, BuildHandler.getPercentLookupLimit(_ctx));
    }

    @Test
    public void percentLookupLimitUnsetPassthroughDefault() {
        int def = SystemVersion.isSlow() ? 15 : 40;
        when(_ctx.getProperty("i2p.tunnel.build.percentLookupLimit", def)).thenReturn(def);
        assertEquals(def, BuildHandler.getPercentLookupLimit(_ctx));
    }

    // =====================================================================
    // BuildRequestor.i2p.tunnel.build.requestTimeout / firstHopTimeout
    // =====================================================================

    @Test
    public void requestTimeoutReturnsTunedOverConfig() {
        BuildRequestor.setRequestTimeout(12000);
        assertEquals(12000, BuildRequestor.getRequestTimeout(_ctx));
    }

    @Test
    public void requestTimeoutUnsetFallsBackToConfig() {
        when(_ctx.getProperty("i2p.tunnel.build.requestTimeout", 15000)).thenReturn(11000);
        assertEquals(11000, BuildRequestor.getRequestTimeout(_ctx));
    }

    @Test
    public void requestTimeoutUnsetPassthroughDefault() {
        when(_ctx.getProperty("i2p.tunnel.build.requestTimeout", 15000)).thenReturn(15000);
        assertEquals(15000, BuildRequestor.getRequestTimeout(_ctx));
    }

    @Test
    public void firstHopTimeoutReturnsTunedOverConfig() {
        BuildRequestor.setFirstHopTimeout(8000);
        assertEquals(8000, BuildRequestor.getFirstHopTimeout(_ctx));
    }

    @Test
    public void firstHopTimeoutUnsetFallsBackToConfig() {
        when(_ctx.getProperty("i2p.tunnel.build.firstHopTimeout", 10000)).thenReturn(7000);
        assertEquals(7000, BuildRequestor.getFirstHopTimeout(_ctx));
    }

    @Test
    public void firstHopTimeoutUnsetPassthroughDefault() {
        when(_ctx.getProperty("i2p.tunnel.build.firstHopTimeout", 10000)).thenReturn(10000);
        assertEquals(10000, BuildRequestor.getFirstHopTimeout(_ctx));
    }

    // =====================================================================
    // TestJob.i2p.tunnel.testJob.{min,max}TestPeriod / {min,max}TestDelay
    // =====================================================================

    @Test
    public void minTestPeriodReturnsTunedOverConfig() {
        TestJob.setMinTestPeriod(5000);
        assertEquals(5000, TestJob.getMinTestPeriod(_ctx));
    }

    @Test
    public void minTestPeriodUnsetFallsBackToConfig() {
        when(_ctx.getProperty("i2p.tunnel.testJob.minTestPeriod", 3000)).thenReturn(4000);
        assertEquals(4000, TestJob.getMinTestPeriod(_ctx));
    }

    @Test
    public void maxTestPeriodReturnsTunedOverConfig() {
        TestJob.setMaxTestPeriod(12000);
        assertEquals(12000, TestJob.getMaxTestPeriod(_ctx));
    }

    @Test
    public void maxTestPeriodUnsetFallsBackToConfig() {
        when(_ctx.getProperty("i2p.tunnel.testJob.maxTestPeriod", 15000)).thenReturn(14000);
        assertEquals(14000, TestJob.getMaxTestPeriod(_ctx));
    }

    @Test
    public void minTestDelayReturnsTunedOverConfig() {
        TestJob.setMinTestDelay(60000);
        assertEquals(60000, TestJob.getMinTestDelay(_ctx));
    }

    @Test
    public void minTestDelayUnsetFallsBackToConfig() {
        when(_ctx.getProperty("i2p.tunnel.testJob.minTestDelay", 30000)).thenReturn(45000);
        assertEquals(45000, TestJob.getMinTestDelay(_ctx));
    }

    @Test
    public void maxTestDelayReturnsTunedOverConfig() {
        TestJob.setMaxTestDelay(120000);
        assertEquals(120000, TestJob.getMaxTestDelay(_ctx));
    }

    @Test
    public void maxTestDelayUnsetFallsBackToConfig() {
        when(_ctx.getProperty("i2p.tunnel.testJob.maxTestDelay", 90000)).thenReturn(100000);
        assertEquals(100000, TestJob.getMaxTestDelay(_ctx));
    }
}