package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.util.Clock;

/**
 * Tests for BuildRequestor.preConnectFallback (approach 2): when a
 * first-hop build fails due to no transport connection, attempt to
 * establish the session and retry the build once.
 *
 * @since 0.9.71+
 */
public class BuildRequestorPreConnectTest {

    private static final long NOW = 2_000_000_000L;
    private static final String TRUE_STR = "true";
    private static final String FALSE_STR = "false";

    private RouterContext _ctx;

    @Before
    public void setUp() {
        ClientPeerSelector.clearPreConnectHistory();
        _ctx = mock(RouterContext.class);
        when(_ctx.routerHash()).thenReturn(hash(9));

        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);

        Router _router = mock(Router.class);
        when(_ctx.router()).thenReturn(_router);
        when(_router.getUptime()).thenReturn(60 * 60 * 1000L);
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    /** Set the pre-connect enabled property. */
    private void setPreConnectEnabled(boolean enabled) {
        when(_ctx.getProperty(eq(BuildRequestor.PROP_PRECONNECT_ENABLED), any())).thenReturn(enabled ? TRUE_STR : FALSE_STR);
    }

    /** Set commSystem state. */
    private void setCommState(boolean established, boolean connecting) {
        CommSystemFacade cs = mock(CommSystemFacade.class);
        when(cs.isEstablished(any(Hash.class))).thenReturn(established);
        when(cs.isConnecting(any(Hash.class))).thenReturn(connecting);
        when(cs.isBacklogged(any(Hash.class))).thenReturn(false);
        when(cs.getEstablished()).thenReturn(java.util.Collections.emptyList());
        when(_ctx.commSystem()).thenReturn(cs);
    }

    @Test
    public void testPreConnectFallback_disabled_returnsFalse() {
        // Approach 3: property toggle disabled
        setPreConnectEnabled(false);
        setCommState(false, false);

        assertFalse(BuildRequestor.preConnectFallback(_ctx, hash(1), null, null));
        // preConnectTo was not called
        verify(_ctx.commSystem(), never()).getTransports();
    }

    @Test
    public void testPreConnectFallback_enabled_callsPreConnectTo() {
        // Approach 2: enabled and unconnected peer triggers preConnectTo
        setPreConnectEnabled(true);
        setCommState(false, false);
        // Setup netDb to return RouterInfo for preConnectTo
        RouterInfo ri = mock(RouterInfo.class);
        NetworkDatabaseFacade ndb = mock(NetworkDatabaseFacade.class);
        when(ndb.lookupRouterInfoLocally(any(Hash.class))).thenReturn(ri);
        when(ndb.lookupLocallyWithoutValidation(any(Hash.class))).thenReturn(ri);
        when(_ctx.netDb()).thenReturn(ndb);
        // Provide valid addresses so preConnectTo doesn't skip
        RouterAddress ra = mock(RouterAddress.class);
        when(ra.getTransportStyle()).thenReturn("SSU");
        when(ra.getOption("v")).thenReturn("2");
        when(ra.getIP()).thenReturn(new byte[]{1,2,3,4});
        when(ra.getPort()).thenReturn(1234);
        when(ri.getAddresses()).thenReturn(java.util.Collections.singletonList(ra));
        when(ri.getCapabilities()).thenReturn("SSU");
        when(ri.getBandwidthTier()).thenReturn("H");
        when(ri.getPublished()).thenReturn(NOW);

        try {
            BuildRequestor.preConnectFallback(_ctx, hash(1), null, null);
        } catch (Exception e) {
            // request() may fail with null cfg, but preConnectTo
            // should have been called
        }
        // Verify preConnectTo was called via netDb lookup
        verify(ndb, atLeastOnce()).lookupRouterInfoLocally(any(Hash.class));
    }

    @Test
    public void testPreConnectFallback_noRouterInfo_returnsEarly() {
        // If RouterInfo is null, preConnectTo returns early
        setPreConnectEnabled(true);
        setCommState(false, false);
        NetworkDatabaseFacade ndb = mock(NetworkDatabaseFacade.class);
        when(ndb.lookupRouterInfoLocally(any(Hash.class))).thenReturn(null);
        when(_ctx.netDb()).thenReturn(ndb);

        try {
            BuildRequestor.preConnectFallback(_ctx, hash(1), null, null);
        } catch (Exception e) {
            // Expected with partially mocked context
        }
        // getTransports should NOT be called since preConnectTo
        // returned early due to null RouterInfo
        verify(_ctx.commSystem(), never()).getTransports();
    }
}
