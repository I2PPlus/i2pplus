package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import net.i2p.data.router.RouterAddress;
import net.i2p.util.OrderedProperties;

/**
 * The per-address introducer cap used by the store-time ban: a single SSU2
 * address may carry at most five introducers, and the count is never summed
 * across a router's addresses, because a dual-stack peer legitimately carries
 * the cap once per family.
 *
 * @since 0.9.72+
 */
public class IntroducerCapDecisionTest {

    @Test
    public void exactlyAtCapIsAccepted() {
        assertFalse(KademliaNetworkDatabaseFacade.tooManyIntroducers(
                Collections.singletonList(address(slots(5)))));
    }

    @Test
    public void oneOverCapIsRejected() {
        assertTrue(KademliaNetworkDatabaseFacade.tooManyIntroducers(
                Collections.singletonList(address(slots(6)))));
    }

    @Test
    public void manyOverCapIsRejected() {
        assertTrue(KademliaNetworkDatabaseFacade.tooManyIntroducers(
                Collections.singletonList(address(slots(14)))));
    }

    @Test
    public void capIsPerAddressNotTotal() {
        // A dual-stack router at the cap on v4 and again on v6 is in spec:
        // ten introducers in total, five on each address.
        List<RouterAddress> addrs = Arrays.asList(address(slots(5)), address(slots(5)));
        assertFalse(KademliaNetworkDatabaseFacade.tooManyIntroducers(addrs));
    }

    @Test
    public void overCapOnOneAddressOfSeveralIsRejected() {
        List<RouterAddress> addrs = Arrays.asList(address(slots(5)), address(slots(6)));
        assertTrue(KademliaNetworkDatabaseFacade.tooManyIntroducers(addrs));
    }

    @Test
    public void noAddressesIsAccepted() {
        assertFalse(KademliaNetworkDatabaseFacade.tooManyIntroducers(Collections.<RouterAddress>emptyList()));
        assertFalse(KademliaNetworkDatabaseFacade.tooManyIntroducers(null));
    }

    @Test
    public void missingTagSlotIsNotCounted() {
        // Every introducer slot carries a relay tag, so the tag at the cap index
        // is the marker; an address with an "ih" beyond the cap but no tag is
        // malformed rather than over-cap, and the transport check ignores it too.
        OrderedProperties opts = new OrderedProperties();
        for (int i = 0; i < 5; i++) {
            opts.setProperty("itag" + i, "0x1234");
            opts.setProperty("ih" + i, "AAAAAAAAAAAAAAAAAAAAAAAAAAA");
        }
        opts.setProperty("ih5", "AAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertFalse(KademliaNetworkDatabaseFacade.tooManyIntroducers(
                Collections.singletonList(new RouterAddress("SSU2", opts, 5))));
    }

    /** The itag/ih property pairs for n introducer slots. */
    private static String[] slots(int n) {
        String[] keys = new String[n * 2];
        for (int i = 0; i < n; i++) {
            keys[i * 2] = "itag" + i;
            keys[i * 2 + 1] = "ih" + i;
        }
        return keys;
    }

    /** An SSU2 address carrying the given property names, each with a dummy value. */
    private static RouterAddress address(String[] keys) {
        OrderedProperties opts = new OrderedProperties();
        for (String k : keys) {
            opts.setProperty(k, "0x1234");
        }
        return new RouterAddress("SSU2", opts, 5);
    }
}
