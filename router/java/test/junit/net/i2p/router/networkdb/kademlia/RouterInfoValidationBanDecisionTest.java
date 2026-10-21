package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Properties;
import org.junit.Test;

import net.i2p.data.router.RouterInfo;

/**
 * The RouterInfo validation rules the netdb purge sweep applies: a version
 * above every released version, an impossible publication date, and both the
 * reachable and the unreachable flag at once. Rule ordering and the reason
 * strings are pinned here, because the transports record the same reasons at
 * the handshake and a peer should look the same in the banlist however we
 * learned of it.
 *
 * @since 0.9.72+
 */
public class RouterInfoValidationBanDecisionTest {

    private static final long VALID_DATE = 1700000000000L;

    @Test
    public void validRouterInfoIsNotBanned() {
        assertNull(KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(
                ri("0.9.70", "PDXU", VALID_DATE), true, true, true));
    }

    @Test
    public void fabricatedVersionIsBanned() {
        assertEquals("Invalid Router version (3.0)",
                     KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(
                             ri("3.0", "PDXU", VALID_DATE), true, true, true));
    }

    @Test
    public void negativePublicationDateIsBanned() {
        assertEquals("Invalid publication date",
                     KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(
                             ri("0.9.70", "PDXU", -1L), true, true, true));
    }

    @Test
    public void contradictoryCapsAreBanned() {
        assertEquals("Invalid published capabilities (RU)",
                     KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(
                             ri("0.9.70", "PDXRU", VALID_DATE), true, true, true));
    }

    @Test
    public void eachRuleIsIndividuallySwitchable() {
        RouterInfo broken = ri("3.0", "PDXRU", -1L);
        // With only one rule on, that is the rule reported.
        assertEquals("Invalid Router version (3.0)",
                     KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(broken, true, false, false));
        assertEquals("Invalid publication date",
                     KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(broken, false, true, false));
        assertEquals("Invalid published capabilities (RU)",
                     KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(broken, false, false, true));
        // Nothing on, nothing banned.
        assertNull(KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(broken, false, false, false));
    }

    @Test
    public void firstRuleWinsWhenSeveralBreak() {
        // The order is version, then date, then caps: the reason a purge sweep
        // records must be stable, so it is pinned here.
        RouterInfo broken = ri("99.0", "RU", -5L);
        assertEquals("Invalid Router version (99.0)",
                     KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(broken, true, true, true));
        assertEquals("Invalid publication date",
                     KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(
                             ri("0.9.70", "RU", -5L), true, true, true));
    }

    @Test
    public void nullRouterInfoIsNotBanned() {
        assertNull(KademliaNetworkDatabaseFacade.invalidRouterInfoBanReason(null, true, true, true));
    }

    /** A RouterInfo carrying the given version and capability options. */
    private static RouterInfo ri(String version, String caps, long published) {
        Properties opts = new Properties();
        opts.setProperty("router.version", version);
        opts.setProperty(RouterInfo.PROP_CAPABILITIES, caps);
        RouterInfo ri = new RouterInfo();
        ri.setOptions(opts);
        ri.setPublished(published);
        return ri;
    }
}
