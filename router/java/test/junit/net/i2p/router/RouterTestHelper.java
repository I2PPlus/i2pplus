package net.i2p.router;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.Properties;

import net.i2p.data.Hash;

import org.junit.Assume;

/**
 * Shared utility for router tests that may need a RouterContext.
 * Uses an existing context if available (e.g. router already running),
 * otherwise creates a lightweight one with dummy sub-systems and a
 * mocked Router so router()-dependent code paths work deterministically.
 * Skips tests if neither is possible.
 */
public class RouterTestHelper {

    public static RouterContext getContext() {
        List<RouterContext> ctxs = RouterContext.listContexts();
        if (!ctxs.isEmpty() && ctxs.get(0).isRouterContext()) {
            return ctxs.get(0);
        }
        return newContext();
    }

    /**
     *  A brand-new context with dummy sub-systems and a mocked Router,
     *  never reusing an existing one. Use when a test needs an isolated
     *  StatManager so other tests' rate data cannot leak in.
     *
     *  @return the new context, or null if creation failed
     */
    public static RouterContext newContext() {
        try {
            Properties props = new Properties();
            props.setProperty("i2p.dummyClientFacade", "true");
            props.setProperty("i2p.dummyNetDb", "true");
            props.setProperty("i2p.dummyPeerManager", "true");
            props.setProperty("i2p.dummyTunnelManager", "true");
            props.setProperty("i2p.vmCommSystem", "true");
            // Keep every data file the context writes (autotune.config,
            // sessionbans/, netDb/, peerProfiles/, ...) out of the source
            // tree. Defaults to the ant build.root; the junit target passes
            // i2p.build.root explicitly.
            String buildRoot = System.getProperty("i2p.build.root",
                                                  System.getProperty("java.io.tmpdir") + File.separator + "build-i2p" + File.separator);
            String dataDir = new File(buildRoot, "test-router").getAbsolutePath();
            props.setProperty("i2p.dir.config", dataDir);
            props.setProperty("i2p.dir.router", dataDir);
            props.setProperty("i2p.dir.log", dataDir);
            props.setProperty("i2p.dir.temp", dataDir);
            props.setProperty("i2p.dir.pid", dataDir);
            props.setProperty("i2p.dir.app", dataDir);
            RouterContext ctx = new RouterContext(mockRouter(), props);
            ctx.initAll();
            return ctx;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     *  A mocked Router that answers the router-state queries the
     *  subsystems and tests rely on without a live router instance.
     *
     *  @return the mock
     */
    private static Router mockRouter() {
        Router router = mock(Router.class);
        // past the startup-grace window used by Tuner/JobQueueScaler
        when(router.getUptime()).thenReturn(10 * 60 * 1000L);
        when(router.getRouterHash()).thenReturn(Hash.create(new byte[Hash.HASH_LENGTH]));
        when(router.getSharePercentage()).thenReturn(0.9d);
        return router;
    }

    static void assumeContext() {
        Assume.assumeTrue("No RouterContext available", getContext() != null);
    }
}
