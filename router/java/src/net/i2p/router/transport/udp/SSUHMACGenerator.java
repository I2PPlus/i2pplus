package net.i2p.router.transport.udp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;

// following are for main() tests
//import java.security.InvalidKeyException;
//import java.security.Key;
//import java.security.NoSuchAlgorithmException;
//import javax.crypto.spec.SecretKeySpec;
//import net.i2p.data.Base64;

import net.i2p.crypto.HMACGenerator;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.SimpleByteCache;

/**
 * Calculate the HMAC-MD5-128 of a key+message.  All the good stuff occurs
 * in {@link I2PHMac}
 *
 * Keys are always 32 bytes.
 * This is used only by UDP.
 * Use deprecated outside the router, this may move to router.jar.
 *
 * NOTE THIS IS NOT COMPATIBLE with javax.crypto.Mac.getInstance("HmacMD5")
 * as we tell I2PHMac that the digest length is 32 bytes, so it generates
 * a different result.
 *
 * Quote jrandom:
 * "The HMAC is hardcoded to use SHA256 digest size
 * for backwards compatability.  next time we have a backwards
 * incompatible change, we should update this."
 *
 * Does this mean he intended it to be compatible with MD5?
 * See also 2005-07-05 status notes.
 *
 * @since 0.9.42 moved from net.i2p.crypto.HMACGenerator
 */
class SSUHMACGenerator extends HMACGenerator {
    /** set of available HMAC instances for calculate */
    private final LinkedBlockingQueue<I2PHMac> _available;

    public SSUHMACGenerator() {
        super();
        _available = new LinkedBlockingQueue<I2PHMac>(32);
    }

    /**
     * Calculate the HMAC of the data with the given key
     *
     * @param target out parameter the first 16 bytes contain the HMAC, the last 16 bytes are zero
     * @param targetOffset offset into target to put the hmac
     * @throws IllegalArgumentException for bad key or target too small
     */
    public void calculate(SessionKey key, byte data[], int offset, int length, byte target[], int targetOffset) {
        if ((key == null) || (key.getData() == null) || (data == null))
            throw new NullPointerException("Null arguments for HMAC");

        I2PHMac mac = acquire();
        mac.init(key.getData());
        mac.update(data, offset, length);
        mac.doFinal(target, targetOffset);
        release(mac);
    }

    /**
     * Verify the MAC inline, reducing some unnecessary memory churn.
     *
     * @param key session key to verify the MAC with
     * @param curData MAC to verify
     * @param curOffset index into curData to MAC
     * @param curLength how much data in curData do we want to run the HMAC over
     * @param origMAC what do we expect the MAC of curData to equal
     * @param origMACOffset index into origMAC
     * @param origMACLength how much of the MAC do we want to verify
     * @throws IllegalArgumentException for bad key
     */
    public boolean verify(SessionKey key, byte curData[], int curOffset, int curLength,
                          byte origMAC[], int origMACOffset, int origMACLength) {
        if ((key == null) || (key.getData() == null) || (curData == null))
            throw new NullPointerException("Null arguments for HMAC");

        I2PHMac mac = acquire();
        mac.init(key.getData());
        mac.update(curData, curOffset, curLength);
        byte rv[] = acquireTmp();
        mac.doFinal(rv, 0);
        release(mac);

        boolean eq = DataHelper.eqCT(rv, 0, origMAC, origMACOffset, origMACLength);
        releaseTmp(rv);
        return eq;
    }

    private I2PHMac acquire() {
        I2PHMac rv = _available.poll();
        if (rv != null)
            return rv;
        // the HMAC is hardcoded to use SHA256 digest size
        // for backwards compatability.  next time we have a backwards
        // incompatible change, we should update this by removing ", 32"
        // SEE NOTES ABOVE
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return new I2PHMac(md, 32);
        } catch (NoSuchAlgorithmException nsae) {
            throw new UnsupportedOperationException("MD5");
        }
    }

    private void release(I2PHMac mac) {
        _available.offer(mac);
    }

    /**
     *  @since 0.9.42
     */
    public void clearCache() {
        _available.clear();
    }

    //private static final int RUNS = 100000;

    /**
     *  Test the BC and the JVM's implementations for speed
     */
/****  All this did was prove that we aren't compatible with standard HmacMD5
    public static void main(String args[]) {
        if (args.length != 2) {
            System.err.println("Usage: HMACGenerator keySeedString dataString");
            return;
        }

        byte[] rand = SHA256Generator.getInstance().calculateHash(args[0].getBytes()).getData();
        byte[] data = args[1].getBytes();
        Key keyObj = new SecretKeySpec(rand, "HmacMD5");

        byte[] keyBytes = keyObj.getEncoded();
        System.out.println("key bytes (" + keyBytes.length + ") is [" + Base64.encode(keyBytes) + "]");
        SessionKey key = new SessionKey(keyBytes);
        System.out.println("session key is [" + key);
        System.out.println("key object is [" + keyObj);

        HMACGenerator gen = new HMACGenerator(I2PAppContext.getGlobalContext());
        byte[] result = new byte[16];
        long start = System.currentTimeMillis();
        for (int i = 0; i < RUNS; i++) {
            gen.calculate(key, data, 0, data.length, result, 0);
            if (i == 0)
                System.out.println("MAC [" + Base64.encode(result) + "]");
        }
        long time = System.currentTimeMillis() - start;
        System.out.println("Time for " + RUNS + " HMAC-MD5 computations:");
        System.out.println("BC time (ms): " + time);

        start = System.currentTimeMillis();
        javax.crypto.Mac mac;
        try {
            mac = javax.crypto.Mac.getInstance("HmacMD5");
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Fatal: " + e);
            return;
        }
        for (int i = 0; i < RUNS; i++) {
            try {
                mac.init(keyObj);
            } catch (InvalidKeyException e) {
                System.err.println("Fatal: " + e);
            }
            byte[] sha = mac.doFinal(data);
            if (i == 0)
                System.out.println("MAC [" + Base64.encode(sha) + "]");
        }
        time = System.currentTimeMillis() - start;

        System.out.println("JVM time (ms): " + time);
    }
****/
}
