package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import net.i2p.data.Base64;
import net.i2p.data.router.RouterAddress;
import net.i2p.util.OrderedProperties;

import org.junit.Test;

/**
 * Reading the peer's SSU2 static public key out of the RouterAddress we hold for it.
 *
 * <p>The handshake cannot be built without it, and there are three ways the option
 * can be unusable - absent, not base64, or the wrong length - each of which used to
 * be reported as a corrupt packet, because the establishment catches cannot tell an
 * address we hold apart from a peer's junk on the wire unless the fault says which
 * it is. The option is written by the peer and read out of the NetDB, so an address
 * that lost it is our state, not a packet, and the attempt is not remote-paced.
 *
 * <p>No transport and no network: the option is set directly on an address.
 *
 * @since 0.9.72+
 */
public class RemoteStaticKeyDecisionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public RemoteStaticKeyDecisionTest() {}

    /**
     * A well-formed option yields the 32 bytes it carries.
     */
    @Test
    public void aUsableOptionYieldsItsKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++)
            key[i] = (byte) (i - 16);
        byte[] read = OutboundEstablishState2.remoteStaticKey(address(Base64.encode(key)));
        assertArrayEquals(key, read);
    }

    /**
     * An address with no SSU2 static key option at all - the form a RouterInfo
     * update leaves behind when the peer drops the option - faults by name.
     */
    @Test
    public void aMissingOptionFaultsByName() {
        assertFault("no SSU2 S", address(null));
    }

    /**
     * An option that is not base64 faults by name, rather than reaching the
     * handshake as a key of the wrong shape.
     */
    @Test
    public void anUnparseableOptionFaultsByName() {
        assertFault("BAD SSU2 S", address("not base64 !!"));
    }

    /**
     * An option that decodes to something other than a Curve25519 point faults by
     * name; a short one would otherwise be read past its end by the handshake.
     */
    @Test
    public void aWrongLengthOptionFaultsByName() {
        assertFault("BAD SSU2 S length", address(Base64.encode(new byte[16])));
        assertFault("BAD SSU2 S length", address(Base64.encode(new byte[33])));
    }

    /**
     * Every unusable option faults with its reason, as the type the establishment
     * reporting splits on, and still as an IllegalArgumentException for the callers
     * that catch that to mark the peer unreachable.
     *
     * @param reason the text the fault must carry into the log
     * @param addr the address to read the option from
     */
    private static void assertFault(String reason, RouterAddress addr) {
        try {
            OutboundEstablishState2.remoteStaticKey(addr);
            fail("an unusable SSU2 option was accepted: " + reason);
        } catch (EstablishFaultException e) {
            assertEquals(reason, e.getMessage());
            assertTrue("the fault is no longer an IllegalArgumentException",
                       e instanceof IllegalArgumentException);
        }
    }

    /**
     * An SSU2 RouterAddress carrying the given static key option.
     *
     * @param encoded the {@code s} option as written in the NetDB, null for no
     *                option at all
     * @return the address to read the key from
     */
    private static RouterAddress address(String encoded) {
        OrderedProperties opts = new OrderedProperties();
        opts.setProperty(RouterAddress.PROP_HOST, "9.9.9.9");
        opts.setProperty(RouterAddress.PROP_PORT, "12345");
        if (encoded != null)
            opts.setProperty("s", encoded);
        return new RouterAddress(UDPTransport.STYLE, opts, 5);
    }
}
