package net.i2p.router;

import org.junit.Assume;

import java.util.List;
import java.util.Properties;

/**
 * Shared utility for router tests that may need a RouterContext.
 * Uses an existing context if available (e.g. router already running),
 * otherwise creates a lightweight one with dummy sub-systems.
 * Skips tests if neither is possible.
 */
public class RouterTestHelper {

    public static RouterContext getContext() {
        List<RouterContext> ctxs = RouterContext.listContexts();
        if (!ctxs.isEmpty() && ctxs.get(0).isRouterContext()) {
            return ctxs.get(0);
        }
        try {
            Properties props = new Properties();
            props.setProperty("i2p.dummyClientFacade", "true");
            props.setProperty("i2p.dummyNetDb", "true");
            props.setProperty("i2p.dummyPeerManager", "true");
            props.setProperty("i2p.dummyTunnelManager", "true");
            props.setProperty("i2p.vmCommSystem", "true");
            RouterContext ctx = new RouterContext(null, props);
            ctx.initAll();
            return ctx;
        } catch (Exception e) {
            return null;
        }
    }

    static void assumeContext() {
        Assume.assumeTrue("No RouterContext available", getContext() != null);
    }
}
