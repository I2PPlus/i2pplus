package net.i2p.client.datagram;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

/**
 * Class for creating and loading I2P repliable datagrams version 2.
 * Ref: Proposal 163
 *
 *<pre>
 * +----+----+----+----+----+----+----+----+
 * |                                       |
 * ~            from                       ~
 * ~                                       ~
 * |                                       |
 * +----+----+----+----+----+----+----+----+
 * |  flags  |     options (optional)      |
 * +----+----+                             +
 * ~                                       ~
 * ~                                       ~
 * +----+----+----+----+----+----+----+----+
 * |                                       |
 * ~     offline_signature (optional)      ~
 * ~   expires, sigtype, pubkey, offsig    ~
 * |                                       |
 * +----+----+----+----+----+----+----+----+
 * |                                       |
 * ~            payload                    ~
 * ~                                       ~
 * |                                       |
 * +----+----+----+----+----+----+----+----+
 * |                                       |
 * ~            signature                  ~
 * ~                                       ~
 * |                                       |
 * +----+----+----+----+----+----+----+----+
 *</pre>
 *
 * @since 0.9.66
 */
public class Datagram2 {

    private final Destination _from;
    private final byte[] _payload;
    private final Properties _options;

    private static final int INIT_DGRAM_BUFSIZE = 2 * 1024;
    private static final int MIN_DGRAM_SIZE = 387 + 2 + 40;
    private static final int MAX_DGRAM_BUFSIZE = 61 * 1024;
    private static final byte VERSION_MASK = 0x0f;
    private static final byte OPTIONS = 0x10;
    private static final byte OFFLINE = 0x20;
    private static final byte VERSION = 2;

    /**
     * As returned from load()
     */
    private Datagram2(Destination dest, byte[] data, Properties options) {
        _from = dest;
        _payload = data;
        _options = options;
    }

    /**
     * Make a repliable I2P datagram2 containing the specified payload.
     *
     * @param payload non-null Bytes to be contained in the I2P datagram.
     * @return non-null, throws on all errors
     * @throws DataFormatException if payload is too big
     */
    public static byte[] make(I2PAppContext ctx, I2PSession session, byte[] payload, Hash tohash) throws DataFormatException {
        return make(ctx, session, payload, tohash, null);
    }

    /**
     * Make a repliable I2P datagram2 containing the specified payload.
     *
     * @param payload non-null Bytes to be contained in the I2P datagram.
     * @param options may be null
     * @return non-null, throws on all errors
     * @throws DataFormatException if payload is too big
     */
    public static byte[] make(I2PAppContext ctx, I2PSession session, byte[] payload, Hash tohash, Properties options) throws DataFormatException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 512);
        try {
            Destination dest = session.getMyDestination();
            dest.writeBytes(out);
            // start of signed data
            int off = out.size();
            out.write(tohash.getData());
            out.write((byte) 0); // high byte flags
            byte flags = VERSION;
            if (options != null && !options.isEmpty()) flags |= OPTIONS;
            if (session.isOffline()) flags |= OFFLINE;
            out.write(flags); // low byte flags
            if (options != null && !options.isEmpty()) DataHelper.writeProperties(out, options);
            if (session.isOffline()) {
                DataHelper.writeLong(out, 4, session.getOfflineExpiration() / 1000);
                SigningPublicKey tspk = session.getTransientSigningPublicKey();
                DataHelper.writeLong(out, 2, tspk.getType().getCode());
                tspk.writeBytes(out);
                Signature tsig = session.getOfflineSignature();
                tsig.writeBytes(out);
            }
            out.write(payload);
            // end of signed data
            byte[] data = out.toByteArray();
            SigningPrivateKey sxPrivKey = session.getPrivateKey();
            Signature sig = ctx.dsa().sign(data, off, out.size() - off, sxPrivKey);
            if (sig == null) throw new IllegalArgumentException("Sig fail");
            sig.writeBytes(out);
            if (out.size() - Hash.HASH_LENGTH > MAX_DGRAM_BUFSIZE) throw new DataFormatException("Too big");
            byte[] rv = out.toByteArray();
            // remove hash
            System.arraycopy(rv, off + Hash.HASH_LENGTH, rv, off, rv.length - (off + Hash.HASH_LENGTH));
            return Arrays.copyOfRange(rv, 0, rv.length - Hash.HASH_LENGTH);
        } catch (IOException e) {
            throw new DataFormatException("DG2 maker error", e);
        }
    }

    /**
     * Load an I2P repliable datagram and verify the signature.
     *
     * @param dgram non-null I2P repliable datagram to be loaded
     * @return non-null, throws on all errors
     * @throws DataFormatException If there is an error in the datagram format
     * @throws I2PInvalidDatagramException If the signature fails
     */
    public static Datagram2 load(I2PAppContext ctx, I2PSession session, byte[] dgram) throws DataFormatException, I2PInvalidDatagramException {
        if (dgram.length < MIN_DGRAM_SIZE) throw new DataFormatException("Datagram2 too small: " + dgram.length);
        ByteArrayInputStream in = new ByteArrayInputStream(dgram);
        try {
            Destination rxDest = Destination.create(in);
            // start of signed data
            int off = dgram.length - in.available();
            in.read(); // ignore high byte of flags
            int flags = in.read();
            int version = flags & VERSION_MASK;
            if (version != VERSION) throw new DataFormatException("Bad version " + version);
            SigningPublicKey spk = rxDest.getSigningPublicKey();
            SigType type = spk.getType();
            if (type == null) throw new DataFormatException("unsupported sig type");
            int optlen = 0;
            Properties options = null;
            if ((flags & OPTIONS) != 0) {
                in.mark(0);
                optlen = (int) DataHelper.readLong(in, 2);
                if (optlen > 0) {
                    in.reset();
                    if (in.available() < optlen) throw new DataFormatException("too small for options: " + dgram.length);
                    options = DataHelper.readProperties(in, null, true);
                }
                optlen += 2;
            }
            int offlinelen = 0;
            if ((flags & OFFLINE) != 0) {
                offlinelen = 6;
                int off2 = dgram.length - in.available();
                long transientExpires = DataHelper.readLong(in, 4) * 1000;
                if (transientExpires < ctx.clock().now()) throw new I2PInvalidDatagramException("Offline signature expired");
                int itype = (int) DataHelper.readLong(in, 2);
                SigType ttype = SigType.getByCode(itype);
                if (ttype == null || !ttype.isAvailable()) throw new I2PInvalidDatagramException("Unsupported transient sig type: " + itype);
                SigningPublicKey transientSigningPublicKey = new SigningPublicKey(ttype);
                byte[] buf = new byte[transientSigningPublicKey.length()];
                offlinelen += buf.length;
                in.read(buf);
                transientSigningPublicKey.setData(buf);
                SigType otype = rxDest.getSigningPublicKey().getType();
                Signature offlineSignature = new Signature(otype);
                buf = new byte[offlineSignature.length()];
                offlinelen += buf.length;
                in.read(buf);
                offlineSignature.setData(buf);
                if (!ctx.dsa().verifySignature(offlineSignature, dgram, off2, 6 + transientSigningPublicKey.length(), spk)) throw new I2PInvalidDatagramException("Bad offline signature");
                type = ttype;
                spk = transientSigningPublicKey;
            }
            int siglen = type.getSigLen();
            // end of signed data
            Signature sig = new Signature(type);
            sig.readBytes(in);
            byte[] buf = new byte[dgram.length + Hash.HASH_LENGTH - (off + siglen)];
            System.arraycopy(session.getMyDestination().calculateHash().getData(), 0, buf, 0, Hash.HASH_LENGTH);
            System.arraycopy(dgram, off, buf, Hash.HASH_LENGTH, dgram.length - (off + siglen));
            if (!ctx.dsa().verifySignature(sig, buf, spk)) {
                throw new I2PInvalidDatagramException("Bad signature " + type);
            }
            if (offlinelen > 0) off += offlinelen;
            int datalen = dgram.length - (off + 2 + optlen + siglen);
            byte[] payload = new byte[datalen];
            System.arraycopy(dgram, off + 2 + optlen, payload, 0, datalen);
            return new Datagram2(rxDest, payload, options);
        } catch (IOException e) {
            throw new DataFormatException("Error loading datagram", e);
        }
    }

    /**
     * Get the payload carried by an I2P repliable datagram (previously loaded
     * with the load() method)
     *
     * @return A byte array containing the datagram payload
     */
    public byte[] getPayload() {
        return _payload;
    }

    /**
     * Get the sender of an I2P repliable datagram (previously loaded with the
     * load() method)
     *
     * @return The Destination of the I2P repliable datagram sender
     */
    public Destination getSender() {
        return _from;
    }

    /**
     * Get the options of an I2P repliable datagram (previously loaded with the
     * load() method), if any
     *
     * @return options or null
     */
    public Properties getOptions() {
        return _options;
    }
}
