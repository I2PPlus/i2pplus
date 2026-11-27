package net.i2p.i2ptunnel.socks;

import static org.junit.Assert.assertEquals;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.Test;

import net.i2p.data.DataHelper;
import net.i2p.socks.SOCKS5Constants.AddressType;
import net.i2p.util.Addresses;

/**
 * Tests for the address field of a SOCKS UDP datagram header.
 *
 * <p>The field starts at offset 4 - three reserved bytes and the address type
 * precede it - and is 4 bytes for type 1 and 16 for type 4, the same widths the
 * parser uses to size the header and {@code getPort()} uses to find the port
 * behind it. Both IP branches used to slice short of that: IPv4 asked for
 * {@code copyOfRange(header, 4, 4)}, an empty array, and IPv6 asked for 12
 * bytes. {@link Addresses#toString(byte[])} accepts only lengths 4 and 16, so a
 * datagram addressed to an IP literal reported its destination as
 * <code>Bad IP length 0</code> or <code>Bad IP length 12</code> - the address
 * was parsed away, not mis-rendered.
 *
 * <p>The domain-name case is pinned alongside them because it is the one branch
 * that was right, and a width fix aimed at the other two must not move it.
 */
public class SOCKSHeaderHostTest {

    /** The port both fixtures carry, behind the address field. */
    private static final int PORT = 0x1234;

    /**
     * A SOCKS UDP header for an IPv4 destination: three reserved bytes, the
     * address type, four address bytes, two port bytes.
     *
     * @param host the dotted-quad destination
     * @return the datagram header
     */
    private static byte[] v4Header(String host) {
        byte[] addr = Addresses.getIP(host);
        byte[] rv = new byte[4 + 4 + 2];
        rv[3] = AddressType.IPV4;
        System.arraycopy(addr, 0, rv, 4, 4);
        DataHelper.toLong(rv, 8, 2, PORT);
        return rv;
    }

    /**
     * A SOCKS UDP header for an IPv6 destination - same layout, 16 address
     * bytes.
     *
     * @param addr the destination in network order
     * @return the datagram header
     */
    private static byte[] v6Header(byte[] addr) {
        byte[] rv = new byte[4 + 16 + 2];
        rv[3] = AddressType.IPV6;
        System.arraycopy(addr, 0, rv, 4, 16);
        DataHelper.toLong(rv, 20, 2, PORT);
        return rv;
    }

    /**
     * An IPv4 destination comes back as the address it was, not as a complaint
     * about the length of what was sliced out of the header.
     */
    @Test
    public void testIPv4HostIsTheFourBytesInTheHeader() {
        SOCKSHeader h = new SOCKSHeader(v4Header("198.51.100.7"));
        assertEquals("198.51.100.7", h.getHost());
        assertEquals("the port follows the four address bytes", PORT, h.getPort());
    }

    /**
     * An IPv6 destination comes back as all sixteen bytes. Twelve bytes is not
     * a short address, it is a different address, and the parser rejects it.
     *
     * @throws UnknownHostException if the JDK rejects the fixture address, which
     *                              would be the fixture's fault
     */
    @Test
    public void testIPv6HostIsTheSixteenBytesInTheHeader() throws UnknownHostException {
        byte[] addr = new byte[16];
        addr[0] = 0x20;
        addr[1] = 0x01;
        addr[2] = (byte) 0x0d;
        addr[3] = (byte) 0xb8;
        addr[15] = 1;
        SOCKSHeader h = new SOCKSHeader(v6Header(addr));
        assertEquals(InetAddress.getByAddress(addr).getHostAddress(), h.getHost());
        assertEquals("the port follows the sixteen address bytes", PORT, h.getPort());
    }

    /**
     * A domain name is unchanged by the IP widths - it is the branch that was
     * already reading the right field.
     */
    @Test
    public void testDomainHostIsUnchanged() {
        String name = "egs.i2p";
        byte[] rv = new byte[5 + name.length() + 2];
        rv[3] = AddressType.DOMAINNAME;
        rv[4] = (byte) name.length();
        System.arraycopy(DataHelper.getASCII(name), 0, rv, 5, name.length());
        DataHelper.toLong(rv, 5 + name.length(), 2, PORT);
        SOCKSHeader h = new SOCKSHeader(rv);
        assertEquals(name, h.getHost());
        assertEquals(PORT, h.getPort());
    }
}
