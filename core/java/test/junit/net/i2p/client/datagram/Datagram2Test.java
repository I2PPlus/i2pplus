package net.i2p.client.datagram;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.ByteArrayStream;

import org.junit.Test;

/**
 * Round-trip tests for Datagram2, including the offline (transient key) path.
 * Regression test for upstream b24aae44c7 (ported as dd35346915): the transient
 * public key and the main signature must use the transient sig type, not the
 * destination sig type.
 *
 * @since 2.13.1
 */
public class Datagram2Test {
    private static final SigType DEST_TYPE = SigType.EdDSA_SHA512_Ed25519;
    // different from DEST_TYPE on purpose, to catch type mixups
    private static final SigType TRANSIENT_TYPE = SigType.DSA_SHA1;

    @Test
    public void onlineRoundTrip() throws Exception {
        I2PAppContext ctx = new I2PAppContext();
        I2PClient cl = I2PClientFactory.createClient();
        ByteArrayStream bas1 = new ByteArrayStream(800);
        cl.createDestination(bas1, DEST_TYPE);
        ByteArrayStream bas2 = new ByteArrayStream(800);
        cl.createDestination(bas2, DEST_TYPE);
        Properties p = new Properties();
        I2PSession s1 = cl.createSession(bas1.asInputStream(), p);
        I2PSession s2 = cl.createSession(bas2.asInputStream(), p);
        Destination d1 = s1.getMyDestination();
        Destination d2 = s2.getMyDestination();

        Properties opts = new Properties();
        opts.setProperty("a", "b");
        byte[] payload = new byte[1024];
        ctx.random().nextBytes(payload);
        byte[] dg = Datagram2.make(ctx, s1, payload, d2.calculateHash(), opts);
        Datagram2 datag = Datagram2.load(ctx, s2, dg);
        assertEquals(d1, datag.getSender());
        assertTrue(DataHelper.eq(payload, datag.getPayload()));
        assertEquals("b", datag.getOptions().getProperty("a"));
    }

    @Test
    public void offlineRoundTrip() throws Exception {
        // build an offline keyfile: dest + encryption key + zeroed signing key
        // + offline section (expiration, transient type, transient pubkey,
        // offline sig, transient privkey) - same layout as PrivateKeyFile.write()
        I2PAppContext ctx = new I2PAppContext();
        I2PClient cl = I2PClientFactory.createClient();
        ByteArrayStream bas1 = new ByteArrayStream(800);
        cl.createDestination(bas1, DEST_TYPE);
        ByteArrayStream bas2 = new ByteArrayStream(800);
        cl.createDestination(bas2, DEST_TYPE);
        byte[] b2 = bas2.toByteArray();
        int pklen = DEST_TYPE.getPrivkeyLen();
        int tocopy = b2.length - pklen;
        ByteArrayStream bas3 = new ByteArrayStream(800);
        bas3.write(b2, 0, tocopy);
        for (int i = 0; i < pklen; i++) {
            bas3.write((byte) 0);
        }
        byte[] oprivb = new byte[pklen];
        System.arraycopy(b2, tocopy, oprivb, 0, pklen);
        SigningPrivateKey opriv = new SigningPrivateKey(DEST_TYPE, oprivb);
        SimpleDataStructure[] tr = ctx.keyGenerator().generateSigningKeys(TRANSIENT_TYPE);
        SigningPublicKey tpub = (SigningPublicKey) tr[0];
        SigningPrivateKey tpriv = (SigningPrivateKey) tr[1];
        ByteArrayOutputStream baos = new ByteArrayOutputStream(70);
        DataHelper.writeLong(bas3, 4, 0x7fffffff);
        DataHelper.writeLong(baos, 4, 0x7fffffff);
        DataHelper.writeLong(bas3, 2, TRANSIENT_TYPE.getCode());
        DataHelper.writeLong(baos, 2, TRANSIENT_TYPE.getCode());
        byte[] tpubb = tpub.getData();
        bas3.write(tpubb);
        baos.write(tpubb);
        Signature sig = ctx.dsa().sign(baos.toByteArray(), opriv);
        assertNotNull("offline sig failed", sig);
        bas3.write(sig.getData());
        bas3.write(tpriv.getData());

        Properties p = new Properties();
        I2PSession s1 = cl.createSession(bas1.asInputStream(), p);
        I2PSession s2 = cl.createSession(bas3.asInputStream(), p);
        assertTrue("session should be offline", s2.isOffline());
        Destination d1 = s1.getMyDestination();
        Destination d2 = s2.getMyDestination();

        byte[] payload = new byte[1024];
        ctx.random().nextBytes(payload);
        byte[] dg = Datagram2.make(ctx, s2, payload, d1.calculateHash(), null);
        Datagram2 datag = Datagram2.load(ctx, s1, dg);
        assertEquals(d2, datag.getSender());
        assertTrue(DataHelper.eq(payload, datag.getPayload()));
    }
}
