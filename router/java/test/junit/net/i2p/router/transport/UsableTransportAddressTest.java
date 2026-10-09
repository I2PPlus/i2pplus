package net.i2p.router.transport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.util.OrderedProperties;

import org.junit.Test;

/**
 * The transport-address-validity gate on tier promotion.
 *
 * <p>{@code ProfileOrganizer.lockedPromoteProfileToTiers} refuses to promote a peer whose
 * RouterInfo has no usable address, because such a peer cannot be selected for a build or
 * pre-connected to. Without that gate the router promotes it, the pre-connect probe finds no
 * address, the peer is demoted, and {@code promoteToFillTiers} promotes the next
 * equally-stale peer — a treadmill that erodes the candidate pool.
 *
 * <p>The decision itself lives in {@link TransportUtil} rather than {@code ProfileOrganizer}
 * so it is reachable from both {@code peermanager} and {@code tunnel.pool} without closing a
 * package cycle, and so it can be tested without a {@code RouterContext}.
 *
 * @since 0.9.71+
 */
public class UsableTransportAddressTest {

    private static RouterAddress address(String style, String host, String port, String introducer, String version) {
        OrderedProperties opt = new OrderedProperties();
        if (host != null) { opt.setProperty("host", host); }
        if (port != null) { opt.setProperty("port", port); }
        if (introducer != null) { opt.setProperty("itag0", introducer); }
        if (version != null) { opt.setProperty("v", version); }
        return new RouterAddress(style, opt, 10);
    }

    private static RouterInfo infoWith(RouterAddress... addresses) {
        RouterInfo ri = new RouterInfo();
        List<RouterAddress> list = new ArrayList<>();
        for (RouterAddress ra : addresses) { list.add(ra); }
        ri.setAddresses(list);
        return ri;
    }

    // ---- per-address rules --------------------------------------------------

    /** SSU v2 with a reachable IP/port is the normal case and must stay usable. */
    @Test
    public void ssu2WithIpAndPortIsUsable() {
        assertTrue(TransportUtil.isUsableRouterAddress(
            address("SSU", "1.2.3.4", "4567", null, "2")));
    }

    /** SSU v1 is retired; promoting a peer we can only reach over SSU1 wastes a slot. */
    @Test
    public void ssu1IsNotUsable() {
        assertFalse(TransportUtil.isUsableRouterAddress(
            address("SSU", "1.2.3.4", "4567", null, "1")));
    }

    @Test
    public void ssu2WithoutVersionIsNotUsable() {
        assertFalse(TransportUtil.isUsableRouterAddress(
            address("SSU", "1.2.3.4", "4567", null, null)));
    }

    /** An introducer is a legitimate way to reach a peer behind NAT. */
    @Test
    public void ssu2WithIntroducerIsUsable() {
        assertTrue(TransportUtil.isUsableRouterAddress(
            address("SSU", null, null, "itag=123", "2")));
    }

    @Test
    public void ssu2WithNeitherIpNorIntroducerIsNotUsable() {
        assertFalse(TransportUtil.isUsableRouterAddress(
            address("SSU", null, null, null, "2")));
    }

    @Test
    public void ssu2NeedsNoVersionOption() {
        assertTrue(TransportUtil.isUsableRouterAddress(
            address("SSU2", "1.2.3.4", "4567", null, null)));
    }

    @Test
    public void ntcpNeedsIpAndPort() {
        assertTrue(TransportUtil.isUsableRouterAddress(address("NTCP", "1.2.3.4", "4567", null, null)));
        assertFalse(TransportUtil.isUsableRouterAddress(address("NTCP", null, null, "itag=1", null)));
    }

    @Test
    public void ntcp2NeedsIpAndPort() {
        assertTrue(TransportUtil.isUsableRouterAddress(address("NTCP2", "1.2.3.4", "4567", null, null)));
        assertFalse(TransportUtil.isUsableRouterAddress(address("NTCP2", null, null, null, null)));
    }

    /** An out-of-range port is not a usable address even with a host. */
    @Test
    public void invalidPortIsNotUsable() {
        assertFalse(TransportUtil.isUsableRouterAddress(
            address("NTCP", "1.2.3.4", "99999", null, null)));
    }

    @Test
    public void unknownTransportStyleIsNotUsable() {
        assertFalse(TransportUtil.isUsableRouterAddress(
            address("SSU3", "1.2.3.4", "4567", null, "2")));
    }

    // ---- the RouterInfo-level decision the promotion gate uses ----------------

    /**
     * The treadmill case: every address expired or unusable, so the peer must be held out
     * of the tiers rather than promoted and then demoted by the pre-connect probe.
     */
    @Test
    public void routerInfoWithNoUsableAddressIsHeld() {
        RouterInfo ri = infoWith(address("SSU", "1.2.3.4", "4567", null, "1"),
                                 address("NTCP", null, null, "itag=1", null));
        assertFalse(TransportUtil.hasUsableTransportAddress(ri));
    }

    @Test
    public void routerInfoWithOneUsableAddressIsPromotable() {
        RouterInfo ri = infoWith(address("SSU", "1.2.3.4", "4567", null, "1"),
                                 address("SSU2", "5.6.7.8", "1234", null, null));
        assertTrue(TransportUtil.hasUsableTransportAddress(ri));
    }

    /**
     * An introducer-only peer must not be swept up by this gate: it genuinely is reachable,
     * and rejecting it would shrink the pool for the wrong reason.
     */
    @Test
    public void introducerOnlyRouterInfoIsPromotable() {
        RouterInfo ri = infoWith(address("SSU", null, null, "itag=123", "2"));
        assertTrue(TransportUtil.hasUsableTransportAddress(ri));
    }

    /** A RouterInfo with no addresses at all is the purest form of the treadmill case. */
    @Test
    public void emptyRouterInfoIsHeld() {
        assertFalse(TransportUtil.hasUsableTransportAddress(infoWith()));
    }

    /** Mixed transports: one good address is enough, we pick one and use it. */
    @Test
    public void anyUsableTransportSuffices() {
        // A bad address of a different transport style must not veto a good one of another.
        RouterInfo ri = infoWith(address("NTCP2", "5.5.5.5", "65536", null, null),
                                 address("SSU2", "9.9.9.9", "18472", null, null));
        assertTrue(TransportUtil.hasUsableTransportAddress(ri));
    }
}