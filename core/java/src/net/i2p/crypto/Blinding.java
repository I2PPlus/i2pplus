package net.i2p.crypto;

import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.Checksum;
import java.util.zip.CRC32;

import net.i2p.I2PAppContext;
import net.i2p.crypto.eddsa.EdDSABlinding;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.data.Base32;
import net.i2p.data.BlindData;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;


/**
 * Utilities for Blinding EdDSA keys.
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * @since 0.9.38
 */
public final class Blinding {

    private static final SigType TYPE = SigType.EdDSA_SHA512_Ed25519;
    private static final SigType TYPER = SigType.RedDSA_SHA512_Ed25519;
    private static final String INFO = "i2pblinding1";
    private static final byte[] INFO_ALPHA = DataHelper.getASCII("I2PGenerateAlpha");

    private static final byte FLAG_TWOBYTE = 0x01;
    private static final byte FLAG_SECRET = 0x02;
    private static final byte FLAG_AUTH = 0x04;

    // following copied from RouterKeyGenerator
    private static final String FORMAT = "yyyyMMdd";
    private static final int LENGTH = FORMAT.length();
    private static final SimpleDateFormat _fmt = new SimpleDateFormat(FORMAT, Locale.US);
    static {
        _fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    private Blinding() {}

    /**
     *  Only for SigTypes EdDSA_SHA512_Ed25519 and RedDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519 or RedDSA_SHA512_Ed25519
     *  @param alpha must be SigType RedDSA_SHA512_Ed25519
     *  @return SigType RedDSA_SHA512_Ed25519
     *  @throws IllegalArgumentException on bad inputs or unsupported SigTypes
     */
    public static SigningPublicKey blind(SigningPublicKey key, SigningPrivateKey alpha) {
        SigType type = key.getType();
        if ((type != TYPE && type != TYPER) ||
            alpha.getType() != TYPER)
            throw new IllegalArgumentException("Unsupported blinding from " + type + " to " + alpha.getType());
        try {
            EdDSAPublicKey jk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey ajk = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPublicKey bjk = EdDSABlinding.blind(jk, ajk);
            return SigUtil.fromJavaKey(bjk, TYPER);
        } catch (GeneralSecurityException gse) {
            throw new IllegalArgumentException(gse);
        }
    }

    /**
     *  Only for SigTypes EdDSA_SHA512_Ed25519 and RedDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519 or RedDSA_SHA512_Ed25519
     *  @param alpha must be SigType RedDSA_SHA512_Ed25519
     *  @return SigType RedDSA_SHA512_Ed25519
     *  @throws IllegalArgumentException on bad inputs or unsupported SigTypes
     */
    public static SigningPrivateKey blind(SigningPrivateKey key, SigningPrivateKey alpha) {
        SigType type = key.getType();
        if ((type != TYPE && type != TYPER) ||
            alpha.getType() != TYPER)
            throw new IllegalArgumentException("Unsupported blinding from " + type + " to " + alpha.getType());
        try {
            EdDSAPrivateKey jk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey ajk = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPrivateKey bjk = EdDSABlinding.blind(jk, ajk);
            return SigUtil.fromJavaKey(bjk, TYPER);
        } catch (GeneralSecurityException gse) {
            throw new IllegalArgumentException(gse);
        }
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType RedDSA_SHA512_Ed25519
     *  @param alpha must be SigType RedDSA_SHA512_Ed25519
     *  @return SigType EdDSA_SHA512_Ed25519
     *  @throws IllegalArgumentException on bad inputs or unsupported SigTypes
     */
    public static SigningPrivateKey unblind(SigningPrivateKey key, SigningPrivateKey alpha) {
        if (key.getType() != TYPER || alpha.getType() != TYPER)
            throw new IllegalArgumentException("Unsupported blinding from " + key.getType() + " / " + alpha.getType());
        try {
            EdDSAPrivateKey bjk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey ajk = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPrivateKey jk = EdDSABlinding.unblind(bjk, ajk);
            return SigUtil.fromJavaKey(jk, TYPE);
        } catch (GeneralSecurityException gse) {
            throw new IllegalArgumentException(gse);
        }
    }

    /**
     *  Generate alpha for current time.
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param destspk must be SigType EdDSA_SHA512_Ed25519
     *  @param secret may be null or zero-length
     *  @return SigType RedDSA_SHA512_Ed25519
     *  @throws IllegalArgumentException on bad inputs or unsupported SigTypes
     *  @since 0.9.39
     */
    public static SigningPrivateKey generateAlpha(I2PAppContext ctx, SigningPublicKey destspk, String secret) {
        long now = ctx.clock().now();
        return generateAlpha(ctx, destspk, secret, now);
    }

    /**
     *  Generate alpha for the given time.
     *  Only for SigType EdDSA_SHA512_Ed25519 or RedDSA_SHA512_Ed25519.
     *
     *  @param destspk must be SigType EdDSA_SHA512_Ed25519 or RedDSA_SHA512_Ed25519
     *  @param secret may be null or zero-length
     *  @param now for what time?
     *  @return SigType RedDSA_SHA512_Ed25519
     *  @throws IllegalArgumentException on bad inputs or unsupported SigTypes
     *  @since 0.9.39
     */
    public static SigningPrivateKey generateAlpha(I2PAppContext ctx, SigningPublicKey destspk,
                                                  String secret, long now) {
        SigType type = destspk.getType();
        if (type != TYPE && type != TYPER)
            throw new IllegalArgumentException("Unsupported blinding from " + type);
        String modVal;
        synchronized(_fmt) {
            modVal = _fmt.format(now);
        }
        if (modVal.length() != LENGTH)
            throw new IllegalStateException();
        byte[] mod = DataHelper.getASCII(modVal);
        byte[] data;
        if (secret != null && secret.length() > 0) {
            byte[] sb = DataHelper.getUTF8(secret);
            data = new byte[LENGTH + sb.length];
            System.arraycopy(mod, 0, data, 0, LENGTH);
            System.arraycopy(sb, 0, data, LENGTH, sb.length);
        } else {
            data = mod;
        }
        HKDF hkdf = new HKDF(ctx);
        byte[] out = new byte[64];
        int stoff = INFO_ALPHA.length + destspk.length();
        byte[] in = new byte[stoff + 4];
        // SHA256("I2PGenerateAlpha" || spk || sigtypein || sigtypeout)
        System.arraycopy(INFO_ALPHA, 0, in, 0, INFO_ALPHA.length);
        System.arraycopy(destspk.getData(), 0, in, INFO_ALPHA.length, destspk.length());
        DataHelper.toLong(in, stoff, 2, type.getCode());
        DataHelper.toLong(in, stoff + 2, 2, TYPER.getCode());
        Hash salt = ctx.sha().calculateHash(in);
        hkdf.calculate(salt.getData(), data, INFO, out, out, 32);
        byte[] b = EdDSABlinding.reduce(out);
        //net.i2p.util.Log log = ctx.logManager().getLog(Blinding.class);
        //log.debug("Input to salt sha256:\n" + net.i2p.util.HexDump.dump(in));
        //log.debug("salt:\n" + net.i2p.util.HexDump.dump(salt.getData()));
        //log.debug("data:\n" + net.i2p.util.HexDump.dump(data));
        //log.debug("hkdf output (seed):\n" + net.i2p.util.HexDump.dump(out));
        //log.debug("alpha (seed mod l):\n" + net.i2p.util.HexDump.dump(b));
        return new SigningPrivateKey(TYPER, b);
    }

    /**
     *  What's the default blinded type for a given unblinded type?
     *
     *  @return non-null
     *  @since 0.9.40
     */
    public static SigType getDefaultBlindedType(SigType unblindedType) {
        if (unblindedType == TYPE)
            return TYPER;
        return unblindedType;
    }

    /**
     *  Decode a new-format b32 address.
     *  See proposal 149.
     *
     *  @param address ending with ".b32.i2p"
     *  @return BlindData structure, use getUnblindedPubKey() for the result
     *  @throws IllegalArgumentException on bad inputs or unsupported SigTypes
     *  @since 0.9.40
     */
    public static BlindData decode(I2PAppContext ctx, String address) throws IllegalArgumentException {
        address = address.toLowerCase(Locale.US);
        if (!address.endsWith(".b32.i2p"))
            throw new IllegalArgumentException("Not a .b32.i2p address");
        byte[] b = Base32.decode(address.substring(0, address.length() - 8));
        if (b == null)
            throw new IllegalArgumentException("Corrupt b32 address");
        if (b.length < 35)
            throw new IllegalArgumentException("Not a new-format address");
        return decode(ctx, b);
    }

    /**
     *  Decode a new-format b32 address.
     *  See proposal 149.
     *  NOTE: Not for external use, use decode(String)
     *
     *  @param b 35+ bytes
     *  @return BlindData structure, use getUnblindedPubKey() for the result
     *  @throws IllegalArgumentException on bad inputs or unsupported SigTypes
     *  @since 0.9.40
     */
    public static BlindData decode(I2PAppContext ctx, byte[] b) throws IllegalArgumentException {
        Checksum crc = new CRC32();
        crc.update(b, 3, b.length - 3);
        long check = crc.getValue();
        b[0] ^= (byte) check;
        b[1] ^= (byte) (check >> 8);
        b[2] ^= (byte) (check >> 16);
        int flag = b[0] & 0xff;
        if ((flag & 0xf8) != 0)
            throw new IllegalArgumentException("Corrupt b32 address (or unsupported options)");
        if ((flag & FLAG_TWOBYTE) != 0)
            throw new IllegalArgumentException("Two byte signature types unsupported");
        // TODO two-byte sigtypes
        int st1 = b[1] & 0xff;
        int st2 = b[2] & 0xff;
        SigType sigt1 = SigType.getByCode(st1);
        SigType sigt2 = SigType.getByCode(st2);
        if (sigt1 == null)
            throw new IllegalArgumentException("Unsupported signature type " + st1);
        if (!sigt1.isAvailable())
            throw new IllegalArgumentException("Unavailable signature type " + sigt1);
        if (sigt2 == null)
            throw new IllegalArgumentException("Unsupported blinded signature type " + st2);
        if (!sigt2.isAvailable())
            throw new IllegalArgumentException("Unavailable blinded signature type " + sigt2);
        // todo secret/privkey
        int spkLen = sigt1.getPubkeyLen();
        if (3 + spkLen > b.length)
            throw new IllegalArgumentException("b32 too short");
        byte[] spkData = new byte[spkLen];
        System.arraycopy(b, 3, spkData, 0, spkLen);
        SigningPublicKey spk = new SigningPublicKey(sigt1, spkData);
        if (3 + spkLen != b.length)
            throw new IllegalArgumentException("b32 too long");
        BlindData rv = new BlindData(ctx, spk, sigt2, null);
        if ((flag & FLAG_SECRET) != 0)
            rv.setSecretRequired();
        if ((flag & FLAG_AUTH) != 0)
            rv.setAuthRequired();
        return rv;
    }

    /**
     *  Encode a public key as a new-format b32 address.
     *  See proposal 149.
     *
     *  @return (56 chars).b32.i2p
     *  @throws IllegalArgumentException on bad inputs or unsupported SigTypes
     *  @since 0.9.40
     */
    public static String encode(SigningPublicKey key) throws IllegalArgumentException {
        return encode(key, false, false);
    }

    /**
     *  Encode a public key as a new-format b32 address.
     *  See proposal 149.
     *
     *  @return (56 chars).b32.i2p
     *  @throws IllegalArgumentException on bad inputs or unsupported SigTypes
     *  @since 0.9.40
     */
    public static String encode(SigningPublicKey key,
                                boolean requireSecret, boolean requireAuth) throws IllegalArgumentException {
        SigType type = key.getType();
        if (type != TYPE && type != TYPER)
            throw new IllegalArgumentException("Unsupported blinding from " + type);
        byte[] d = key.getData();
        byte[] b = new byte[d.length + 3];
        System.arraycopy(d, 0, b, 3, d.length);
        Checksum crc = new CRC32();
        crc.update(b, 3, b.length - 3);
        long check = crc.getValue();
        // TODO two-byte sigtypes
        if (requireSecret)
            b[0] = FLAG_SECRET;
        if (requireAuth)
            b[0] |= FLAG_AUTH;
        b[1] = (byte) (type.getCode() & 0xff);
        b[2] = (byte) (TYPER.getCode() & 0xff);
        b[0] ^= (byte) check;
        b[1] ^= (byte) (check >> 8);
        b[2] ^= (byte) (check >> 16);
        // todo privkey
        return Base32.encode(b) + ".b32.i2p";
    }

    public static void main(String args[]) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: blinding {56 chars}.b32.i2p");
            System.exit(1);
        }
        System.out.println("Blinded B32: " + args[0]);
        System.out.println(decode(I2PAppContext.getGlobalContext(), args[0]).toString());
    }

/******
    public static void main(String args[]) throws Exception {
        net.i2p.data.SimpleDataStructure[] keys = KeyGenerator.getInstance().generateSigningKeys(TYPE);
        SigningPublicKey pub = (SigningPublicKey) keys[0];
        SigningPrivateKey priv = (SigningPrivateKey) keys[1];
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        //String b32 = encode(pub, null);
        String b32 = encode(pub, true, false);
        System.out.println("pub b32 is " + b32);
        BlindData bd = decode(ctx, b32);
        if (bd.getBlindedPubKey().equals(pub))
            System.out.println("B32 test failed");
        else
            System.out.println("B32 test passed");
        byte[] b = new byte[64];
        ctx.random().nextBytes(b);
        b = EdDSABlinding.reduce(b);
        SigningPrivateKey alpha = new SigningPrivateKey(TYPER, b);
        SigningPublicKey bpub = null;
        try {
            bpub = blind(pub, alpha);
        } catch (Exception e) {
            System.out.println("Blinding pubkey test failed");
            e.printStackTrace();
        }
        SigningPrivateKey bpriv = null;
        try {
            bpriv = blind(priv, alpha);
        } catch (Exception e) {
            System.out.println("Blinding privkey test failed");
            e.printStackTrace();
        }
        if (bpub != null && bpriv != null) {
            SigningPublicKey bpub2 = bpriv.toPublic();
            boolean ok = bpub2.equals(bpub);
            System.out.println("Blinding test passed?   " + ok);
            // unimplemented
            //SigningPrivateKey priv2 = unblind(bpriv, alpha);
            //ok = priv2.equals(priv);
            //System.out.println("Unblinding test passed? " + ok);
        }
    }
******/
}
