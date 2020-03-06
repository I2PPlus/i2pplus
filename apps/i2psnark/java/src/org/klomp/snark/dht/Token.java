package org.klomp.snark.dht;
/*
 *  GPLv2
 */

import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

/**
 *  Used for Both outgoing and incoming tokens
 *
 * @since 0.9.2
 * @author zzz
 */
class Token extends ByteArray {

    private static final int MY_TOK_LEN = 8;
    private static final int MAX_TOK_LEN = 64;
    private final long lastSeen;

    /** outgoing - generate a random token */
    public Token(I2PAppContext ctx) {
        super(null);
        byte[] data = new byte[MY_TOK_LEN];
        ctx.random().nextBytes(data);
        setData(data);
        setValid(MY_TOK_LEN);
        lastSeen = ctx.clock().now();
    }

    /** incoming  - save the token (arbitrary length) */
    public Token(I2PAppContext ctx, byte[] data) {
        super(data);
        // lets not get carried away
        if (data.length > MAX_TOK_LEN)
            throw new IllegalArgumentException();
        lastSeen = ctx.clock().now();
    }

    /** incoming  - for lookup only, not storage, lastSeen is 0 */
    public Token(byte[] data) {
        super(data);
        lastSeen = 0;
    }

    public long lastSeen() {
        return lastSeen;
    }

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
                if (b < 16)
                    buf.append('0');
                buf.append(Integer.toHexString(b));
            }
        }
        buf.append("]");
        if (lastSeen > 0)
            buf.append("\n* Created: ").append((new Date(lastSeen)).toString());
//        buf.append(']');
        return buf.toString();
    }
}
