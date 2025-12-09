package org.klomp.snark.dht;

/*
 *  GPLv2
 */

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

import java.util.Date;

/**
 * DHT token management for secure peer verification.
 *
 * <p>Tokens are used in the BitTorrent DHT protocol to prevent certain types of attacks. When a
 * peer announces itself to a DHT node, it receives a token that must be included in subsequent
 * get_peer requests. This verifies that the requesting peer has previously communicated with the
 * DHT node from the same IP address.
 *
 * <p>This class handles both outgoing tokens (generated locally for sending to peers) and incoming
 * tokens (received from remote DHT nodes). Tokens have limited lifetimes and are automatically
 * expired to prevent replay attacks.
 *
 * <p>Security considerations:<br>
 * - Outgoing tokens are 8 bytes of cryptographically secure random data<br>
 * - Incoming tokens may be up to 64 bytes (to accommodate various implementations)<br>
 * - Tokens are timestamped for automatic expiration<br>
 * - Lookup-only tokens don't store timestamps to save memory
 *
 * @since 0.9.2
 * @author zzz
 */
class Token extends ByteArray {

    /** Default token length for outgoing tokens (8 bytes) */
    private static final int MY_TOK_LEN = 8;

    /** Maximum allowed token length for incoming tokens (64 bytes) */
    private static final int MAX_TOK_LEN = 64;

    /** Timestamp when this token was created or last seen */
    private final long lastSeen;

    /**
     * Creates a new outgoing token for sending to remote peers.
     *
     * <p>Generates 8 bytes of cryptographically secure random data that will be used as a token.
     * The token is timestamped for expiration tracking. This constructor is used when we are the
     * DHT node responding to a get_peer request.
     *
     * @param ctx the I2P application context for random number generation and clock access
     */
    public Token(I2PAppContext ctx) {
        super(null);
        byte[] data = new byte[MY_TOK_LEN];
        ctx.random().nextBytes(data);
        setData(data);
        setValid(MY_TOK_LEN);
        lastSeen = ctx.clock().now();
    }

    /**
     * Creates an incoming token received from a remote DHT node.
     *
     * <p>Stores a token received from a remote DHT node for later use in announce_peer requests.
     * The token is timestamped for expiration tracking. Tokens longer than 64 bytes are rejected to
     * prevent memory exhaustion attacks.
     *
     * @param ctx the I2P application context for clock access
     * @param data the token data received from the remote DHT node
     * @throws IllegalArgumentException if the token data exceeds 64 bytes
     */
    public Token(I2PAppContext ctx, byte[] data) {
        super(data);
        // lets not get carried away
        if (data.length > MAX_TOK_LEN) throw new IllegalArgumentException();
        lastSeen = ctx.clock().now();
    }

    /**
     * Creates a lookup-only token without timestamp.
     *
     * <p>Creates a token instance for comparison purposes only, without storing a timestamp. This
     * is useful when checking if a received token matches an expected value without needing to
     * track its age for expiration.
     *
     * @param data the token data to use for lookup comparison
     */
    public Token(byte[] data) {
        super(data);
        lastSeen = 0;
    }

    /**
     * Returns the timestamp when this token was created or last seen.
     *
     * @return the timestamp in milliseconds since epoch, or 0 for lookup-only tokens
     */
    public long lastSeen() {
        return lastSeen;
    }

    /**
     * Returns a string representation of the token for debugging and logging.
     *
     * <p>Formats the token data as hexadecimal and includes the creation timestamp if available.
     * The format is consistent with other DHT debugging output.
     *
     * @return a formatted string containing token length, hex data, and creation time
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        //        buf.append("[Token: ");
        buf.append("[");
        byte[] bs = getData();
        if (bs.length == 0) {
            buf.append("0 bytes");
        } else {
            buf.append(bs.length).append(" bytes: 0x");
            // backwards, but the same way BEValue does it
            for (int i = 0; i < bs.length; i++) {
                int b = bs[i] & 0xff;
                if (b < 16) buf.append('0');
                buf.append(Integer.toHexString(b));
            }
        }
        buf.append("]");
        if (lastSeen > 0) buf.append("\n* Created: ").append((new Date(lastSeen)).toString());
        //        buf.append(']');
        return buf.toString();
    }
}
