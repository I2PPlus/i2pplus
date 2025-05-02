package net.i2p.data;


import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException; 
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import com.nettgryppa.security.HashCash;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.naming.HostTxtEntry;
import net.i2p.crypto.Blinding;
import net.i2p.crypto.CertUtil;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SelfSignedGenerator;
import net.i2p.crypto.SigAlgo;
import net.i2p.crypto.SigType;
import net.i2p.crypto.SigUtil;
import net.i2p.util.OrderedProperties;
import net.i2p.util.RandomSource;
import net.i2p.util.SecureFileOutputStream;

/**
 * This helper class reads and writes files in the
 * same "eepPriv.dat" format used by the client code.
 * The format is:
 *<pre>
 *  - Destination (387 bytes if no certificate, otherwise longer)
 *     - Public key (256 bytes), random data as of 0.9.57 (except for RouterPrivateKeyFile)
 *     - Signing Public key (128 bytes)
 *     - Cert. type (1 byte)
 *     - Cert. length (2 bytes)
 *     - Certificate if length != 0
 *  - Private key (256 bytes for ElGamal, or length specified by key certificate)
 *     -          All zeros as of 0.9.57 (except for RouterPrivateKeyFile)
 *  - Signing Private key (20 bytes, or length specified by key certificate)
 *  - As of 0.9.38, if the Signing Private Key is all zeros,
 *    the offline signature section (see proposal 123):
 *     - Expires timestamp (4 bytes, seconds since epoch, rolls over in 2106)
 *     - Sig type of transient public key (2 bytes)
 *     - Transient Signing Public key (length as specified by transient sig type)
 *     - Signature of Signed Public key by offline key (length as specified by destination sig type)
 *     - Transient Signing Private key (length as specified by transient sig type)
 *
 * Total: 663 or more bytes for ElGamal, may be smaller for other enc. types
 *</pre>
 *
 * Destination encryption keys have been unused since 0.6 (2005).
 * As of 0.9.57, new Destination encryption public keys are simply random data,
 * and encryption private keys may be random data or all zeros.
 *
 * This class is extended by net.i2p.data.router.RouterPrivateKeyFile.
 * RouterIdentity encryption keys ARE used and must be valid.
 *
 * @author welterde, zzz
 */

public class PrivateKeyFile {
    
    private static final int HASH_EFFORT = VerifiedDestination.MIN_HASHCASH_EFFORT;
    
    protected final File file;
    private final I2PClient client;
    protected Destination dest;
    protected PrivateKey privKey;
    protected SigningPrivateKey signingPrivKey; 
    private long _offlineExpiration;
    private Signature _offlineSignature;
    private SigningPrivateKey _transientSigningPrivKey; 
    private SigningPublicKey _transientSigningPubKey; 

    private static final int PADDING_ENTROPY = 32;

    /**
     *  Create a new PrivateKeyFile, or modify an existing one, with various
     *  types of Certificates.
     *  
     *  Changing a Certificate does not change the public or private keys.
     *  But it does change the Destination Hash, which effectively makes it
     *  a new Destination. In other words, don't change the Certificate on
     *  a Destination you've already registered in a hosts.txt key add form.
     *  
     *  Copied and expanded from that in Destination.java
     */
    public static void main(String args[]) {
        int hashEffort = HASH_EFFORT;
        String stype = null;
        String etype = null;
        String ttype = null;
        String hostname = null;
        String offline = null;
        String signer = null;
        String signername = null;
        String signaction = null;
        String certfile = null;
        double days = 365;
        int mode = 0;
        boolean error = false;
        Getopt g = new Getopt("PrivateKeyFile", args, "t:nuxhse:c:a:o:d:r:p:b:y:z:w:v:V:");
        int c;
        while ((c = g.getopt()) != -1) {
          switch (c) {
            case 'c':
                stype = g.getOptarg();
                break;

            case 'p':
                etype = g.getOptarg();
                break;

            case 't':
                stype = g.getOptarg();
                // fall thru...

            case 'n':
            case 'u':
            case 'x':
            case 'h':
            case 's':
                if (mode == 0)
                    mode = c;
                else
                    error = true;
                break;

            case 'a':
                hostname = g.getOptarg();
                if (mode == 0)
                    mode = c;
                else
                    error = true;
                break;

            case 'b':
                signername = g.getOptarg();
                break;

            case 'w':
                certfile = g.getOptarg();
                if (mode == 0)
                    mode = c;
                else
                    error = true;
                break;

            case 'y':
                signer = g.getOptarg();
                break;

            case 'z':
                signaction = g.getOptarg();
                break;

            case 'o':
                offline = g.getOptarg();
                if (mode == 0)
                    mode = c;
                else
                    error = true;
                break;

            case 'e':
                hashEffort = Integer.parseInt(g.getOptarg());
                break;

            case 'd':
                days = Double.parseDouble(g.getOptarg());
                break;

            case 'r':
                ttype = g.getOptarg();
                break;

            case '?':
            case ':':
            default:
                error = true;
                break;
          }  // switch
        } // while

        int remaining = args.length - g.getOptind();
        int reqd = mode == 's' ? 2 : 1;
        if (error || remaining != reqd) {
            usage();
            System.exit(1);
        }
        String filearg = args[g.getOptind()];

        I2PClient client = I2PClientFactory.createClient();

        try {
            String orig = offline != null ? offline : filearg;
            File f = new File(orig);
            boolean exists = f.exists();
            if (mode == 'a' && !exists) {
                throw new I2PException("File for authentication does not exist: " + orig);
            }
            PrivateKeyFile pkf = new PrivateKeyFile(f, client);
            Destination d;
            if (etype != null && !exists) {
                // create router.keys.dat format, no support in I2PClient
                SigType type;
                if (stype == null) {
                    type = SigType.EdDSA_SHA512_Ed25519;
                } else {
                    type = SigType.parseSigType(stype);
                    if (type == null)
                        throw new IllegalArgumentException("Signature type " + stype + " is not supported");
                }
                EncType ptype = EncType.parseEncType(etype);
                if (ptype == null)
                    throw new IllegalArgumentException("Encryption type " + etype + " is not supported");
                d = pkf.createIfAbsent(type, ptype);
            } else if (stype != null) {
                SigType type = SigType.parseSigType(stype);
                if (type == null)
                    throw new IllegalArgumentException("Signature type " + stype + " is not supported");
                d = pkf.createIfAbsent(type);
            } else {
                d = pkf.createIfAbsent();
            }
            if (exists)
                System.out.println("Original Destination:");
            else
                System.out.println("Created Destination:");
            System.out.println(pkf);
            verifySignature(d);
            switch (mode) {
              case 0:
                // we are done
                break;

              case 'n':
                // Cert constructor generates a null cert
                pkf.setCertType(Certificate.CERTIFICATE_TYPE_NULL);
                System.out.println("New destination with null cert is:");
                break;

              case 'u':
                pkf.setCertType(99);
                System.out.println("New destination with unknown cert is:");
                break;

              case 'x':
                pkf.setCertType(Certificate.CERTIFICATE_TYPE_HIDDEN);
                System.out.println("New destination with hidden cert is:");
                break;

              case 'h':
                System.out.println("Estimating hashcash generation time, stand by...");
                System.out.println(estimateHashCashTime(hashEffort));
                pkf.setHashCashCert(hashEffort);
                System.out.println("New destination with hashcash cert is:");
                break;

              case 's':
              {
                // Sign dest1 with dest2's Signing Private Key
                PrivateKeyFile pkf2 = new PrivateKeyFile(args[g.getOptind() + 1]);
                pkf.setSignedCert(pkf2);
                System.out.println("New destination with signed cert is:");
                break;
              }

              case 't':
              {
                // KeyCert
                SigType type = SigType.parseSigType(stype);
                if (type == null)
                    throw new IllegalArgumentException("Signature type " + stype + " is not supported");
                pkf.setKeyCert(type);
                System.out.println("New destination with key cert is:");
                break;
              }

              case 'a':
              {
                // addressbook auth
                OrderedProperties props = new OrderedProperties();
                HostTxtEntry he = new HostTxtEntry(hostname, d.toBase64(), props);
                if (signer != null && signername != null && signaction != null) {
                    File fsigner = new File(signer);
                    if (!fsigner.exists())
                        throw new I2PException("Signing file does not exist: " + signer);
                    if (!signaction.equals(HostTxtEntry.ACTION_ADDSUBDOMAIN))
                        throw new I2PException("Unsupported action: " + signaction);
                    if (!hostname.endsWith('.' + signername))
                        throw new I2PException(hostname + " is not a subdomain of " + signername);
                    PrivateKeyFile pkf2 = new PrivateKeyFile(fsigner);
                    props.setProperty(HostTxtEntry.PROP_ACTION, signaction);
                    props.setProperty(HostTxtEntry.PROP_OLDNAME, signername);
                    props.setProperty(HostTxtEntry.PROP_OLDDEST, pkf2.getDestination().toBase64());
                    he.signInner(pkf2.getSigningPrivKey());
                } else if (signer != null || signername != null || signaction != null) {
                    usage();
                    return;
                }
                he.sign(pkf.getSigningPrivKey());
                System.out.println("\nAddressbook Authentication String:");
                OutputStreamWriter out = new OutputStreamWriter(System.out);
                he.write(out); 
                out.flush();
                System.out.println("");
                return;
              }

              case 'o':
              {
                // Sign dest1 with dest2's Signing Private Key
                File f3 = new File(filearg);
                // set dummy SPK
                SigType type = pkf.getSigningPrivKey().getType();
                byte[] dbytes = new byte[type.getPrivkeyLen()];
                SigningPrivateKey dummy = new SigningPrivateKey(type, dbytes);
                PrivateKeyFile pkf2 = new PrivateKeyFile(f3, pkf.getDestination(), pkf.getPrivKey(), dummy);
                // keygen transient
                SigType tstype = SigType.EdDSA_SHA512_Ed25519;
                if (ttype != null) {
                    tstype = SigType.parseSigType(ttype);
                    if (tstype == null)
                        throw new I2PException("Bad or unsupported -r option: " + ttype);
                }
                SimpleDataStructure signingKeys[];
                try {
                    signingKeys = KeyGenerator.getInstance().generateSigningKeys(tstype);
                } catch (GeneralSecurityException gse) {
                    throw new RuntimeException("keygen fail", gse);
                }
                SigningPublicKey tSigningPubKey = (SigningPublicKey) signingKeys[0];
                SigningPrivateKey tSigningPrivKey = (SigningPrivateKey) signingKeys[1];
                // set expires
                long expires = System.currentTimeMillis() + (long) (days * 24*60*60*1000L);
                // sign
                byte[] data = new byte[4 + 2 + tSigningPubKey.length()];
                DataHelper.toLong(data, 0, 4, expires / 1000);
                DataHelper.toLong(data, 4, 2, tstype.getCode());
                System.arraycopy(tSigningPubKey.getData(), 0, data, 6, tSigningPubKey.length());
                Signature sign = DSAEngine.getInstance().sign(data, pkf.getSigningPrivKey());
                if (sign == null)
                    throw new I2PException("Sig fail");
                // set the offline block
                pkf2.setOfflineData(expires, tSigningPubKey, sign, tSigningPrivKey);
                // write it
                System.out.println("New destination with offline signature is:");
                System.out.println(pkf2);
                pkf2.write();
                return;
              }

              case 'w':
              {
                if (pkf.isOffline()) {
                    System.out.println("Private key is offline, not present in file, export failed");
                    return;
                }
                OutputStream out = null;
                try {
                    SigningPrivateKey priv = pkf.getSigningPrivKey();
                    java.security.PrivateKey jpriv = SigUtil.toJavaKey(priv);
                    if (signername == null)
                        signername = "example.i2p";
                    X509Certificate cert = SelfSignedGenerator.generate(priv, signername, (int) days);
                    java.security.cert.Certificate[] certs = { cert };
                    out = new FileOutputStream(certfile);
                    CertUtil.exportPrivateKey(jpriv, certs, out);
                    System.out.println("Private key and self-signed certificate exported to " + certfile);
                } catch (IOException ioe) {
                    System.out.println("Private key export failed");
                    ioe.printStackTrace();
                } catch (GeneralSecurityException gse) {
                    System.out.println("Private key export failed");
                    gse.printStackTrace();
                } finally {
                    if (out != null) try { out.close(); } catch (IOException ioe) {}
                }
                return;
              }


              default:
                // shouldn't happen
                usage();
                return;
            }
            if (mode != 0) {
                System.out.println(pkf);
                pkf.write();
                verifySignature(pkf.getDestination());
            }
        } catch (I2PException e) {
            String orig = offline != null ? offline : filearg;
            System.out.println("Error processing file: " + orig);
            e.printStackTrace();
            System.exit(1);
        } catch (IOException e) {
            String orig = offline != null ? offline : filearg;
            System.out.println("Error processing file: " + orig);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("Usage: PrivateKeyFile filename (generates if nonexistent, then prints)\n" +
                           "\ncertificate options:\n" +
                           "      -h                   (generates if nonexistent, adds hashcash cert)\n" +
                           "      -n                   (changes to null cert)\n" +
                           "      -s signwithdestfile  (generates if nonexistent, adds cert signed by 2nd dest)\n" +
                           "      -u                   (changes to unknown cert)\n" +
                           "      -x                   (changes to hidden cert)\n" +
                           "\nother options:\n" +
                           "      -a example.i2p       (generate addressbook authentication string)\n" +
                           "      -b example.i2p       (hostname of the 2LD dest for signing)\n" +
                           "      -c sigtype           (specify sig type of destination)\n" +
                           "      -d days              (specify expiration in days of offline sig, default 365)\n" +
                           "      -e effort            (specify HashCash effort instead of default " + HASH_EFFORT + ")\n" +
                           "      -o offlinedestfile   (generate the online key file using the offline key file specified)\n" +
                           "      -p enctype           (specify enc type of destination)\n" +
                           "      -r sigtype           (specify sig type of transient key, default Ed25519)\n" +
                           "      -t sigtype           (changes to KeyCertificate of the given sig type)\n" +
                           "      -w file.key          (export the private signing key to the file specified, also uses -d and -b options)\n" +
                           "      -y 2lddestfile       (sign the authentication string with the 2LD key file specified)\n" +
                           "      -z signaction        (authentication string command, must be \"addsubdomain\"\n" +
                           "");
        StringBuilder buf = new StringBuilder(256);
        buf.append("Available signature types:\n");
        for (SigType t : EnumSet.allOf(SigType.class)) {
            if (!t.isAvailable())
                continue;
            if (t.getBaseAlgorithm().equals(SigAlgo.RSA))
                continue;
            int code = t.getCode();
            if (code == 8)
                continue;
            buf.append("      ").append(t).append("\t(code: ").append(t.getCode()).append(')');
            if (code == 0)
                buf.append(" DEFAULT");
            if (code < 7)
                buf.append(" DEPRECATED");
            buf.append('\n');
        }
        buf.append("\nAvailable encryption types:\n");
        for (EncType t : EnumSet.allOf(EncType.class)) {
            if (!t.isAvailable())
                continue;
            buf.append("      ").append(t).append("\t(code: ").append(t.getCode()).append(')');
            int code = t.getCode();
            if (code == 0)
                buf.append(" DEFAULT");
            if (code < 4)
                buf.append(" DEPRECATED");
            buf.append('\n');
        }
        System.out.println(buf.toString());
    }
    
    public PrivateKeyFile(String file) {
        this(new File(file), I2PClientFactory.createClient());
    }

    public PrivateKeyFile(File file) {
        this(file, I2PClientFactory.createClient());
    }

    public PrivateKeyFile(File file, I2PClient client) {
        this.file = file;
        this.client = client;
    }
    
    /** @since 0.8.9 */
    public PrivateKeyFile(File file, I2PSession session) {
        this(file, session.getMyDestination(), session.getDecryptionKey(), session.getPrivateKey());
    }
    
    /**
     *  @throws IllegalArgumentException on mismatch of spubkey and spk types
     *  @since 0.8.9
     */
    public PrivateKeyFile(File file, Destination dest, PrivateKey pk, SigningPrivateKey spk) {
        if (dest.getSigningPublicKey().getType() != spk.getType())
            throw new IllegalArgumentException("Signing key type mismatch");
        this.file = file;
        this.client = null;
        this.dest = dest;
        this.privKey = pk;
        this.signingPrivKey = spk;
    }
    
    /**
     *  @throws IllegalArgumentException on mismatch of spubkey and spk types
     *  @since 0.8.9
     */
    public PrivateKeyFile(File file, PublicKey pubkey, SigningPublicKey spubkey, Certificate cert,
                          PrivateKey pk, SigningPrivateKey spk) {
        this(file, pubkey, spubkey, cert, pk, spk, null);
    }
    
    /**
     *  @param padding null OK, must be non-null if spubkey length &lt; 128
     *  @throws IllegalArgumentException on mismatch of spubkey and spk types
     *  @since 0.9.16
     */
    public PrivateKeyFile(File file, PublicKey pubkey, SigningPublicKey spubkey, Certificate cert,
                          PrivateKey pk, SigningPrivateKey spk, byte[] padding) {
        if (spubkey.getType() != spk.getType())
            throw new IllegalArgumentException("Signing key type mismatch");
        this.file = file;
        this.client = null;
        this.dest = new Destination();
        this.dest.setPublicKey(pubkey);
        this.dest.setSigningPublicKey(spubkey);
        this.dest.setCertificate(cert);
        if (padding != null)
            this.dest.setPadding(padding);
        this.privKey = pk;
        this.signingPrivKey = spk;
    }
    
    /**
     *  Can't be used for writing
     *  @since 0.9.26
     */
    public PrivateKeyFile(InputStream in) throws I2PSessionException {
        this("/dev/null");
        I2PSession s = this.client.createSession(in, new Properties());
        this.dest = s.getMyDestination();
        this.privKey = s.getDecryptionKey();
        this.signingPrivKey = s.getPrivateKey();
    }
    
    /**
     *  Create with the default signature type if nonexistent.
     *
     *  Also reads in the file to get the privKey and signingPrivKey, 
     *  which aren't available from I2PClient.
     */
    public Destination createIfAbsent() throws I2PException, IOException, DataFormatException {
        return createIfAbsent(I2PClient.DEFAULT_SIGTYPE);
    }

    /**
     *  Create with the specified signature type if nonexistent.
     *
     *  Also reads in the file to get the privKey and signingPrivKey, 
     *  which aren't available from I2PClient.
     *
     *  @since 0.9.26
     */
    public Destination createIfAbsent(SigType type) throws I2PException, IOException, DataFormatException {
        if(!this.file.exists()) {
            OutputStream out = null;
            try {
                if (this.client != null) {
                    out = new SecureFileOutputStream(this.file);
                    this.client.createDestination(out, type);
                } else {
                    write();
                }
            } finally {
                if (out != null) {
                    try { out.close(); } catch (IOException ioe) {}
                }
            }
        }
        return getDestination();
    }

    /**
     *  Create with the specified signature and encryption types if nonexistent.
     *  Private for now, only for router.keys.dat testing.
     *
     *  Also reads in the file to get the privKey and signingPrivKey, 
     *  which aren't available from I2PClient.
     *
     *  @since 0.9.42
     */
    private Destination createIfAbsent(SigType type, EncType ptype) throws I2PException, IOException, DataFormatException {
        if(!this.file.exists()) {
            OutputStream out = null;
            try {
                if (this.client != null) {
                    out = new SecureFileOutputStream(this.file);
                    // no support for this in I2PClient,
                    // so we modify code from CreateRouterInfoJob.createRouterInfo()
                    I2PAppContext ctx = I2PAppContext.getGlobalContext();
                    byte[] rand = new byte[PADDING_ENTROPY];
                    ctx.random().nextBytes(rand);
                    PublicKey pub;
                    PrivateKey priv;
                    if (getClass().equals(PrivateKeyFile.class)) {
                        // destinations don't use the encryption key
                        byte[] bpub = new byte[ptype.getPubkeyLen()];
                        for (int i = 0; i < bpub.length; i += PADDING_ENTROPY) {
                             System.arraycopy(rand, 0, bpub, i, Math.min(PADDING_ENTROPY, bpub.length - i));
                        }
                        pub = new PublicKey(ptype, bpub);
                        byte[] bpriv = new byte[ptype.getPrivkeyLen()];
                        priv = new PrivateKey(ptype, bpriv);
                    } else {
                        // routers use the encryption key
                        KeyPair keypair = ctx.keyGenerator().generatePKIKeys(ptype);
                        pub = keypair.getPublic();
                        priv = keypair.getPrivate();
                    }
                    SimpleDataStructure signingKeypair[] = ctx.keyGenerator().generateSigningKeys(type);
                    SigningPublicKey spub = (SigningPublicKey)signingKeypair[0];
                    SigningPrivateKey spriv = (SigningPrivateKey)signingKeypair[1];
                    Certificate cert;
                    if (type != SigType.DSA_SHA1 || ptype != EncType.ELGAMAL_2048) {
                        // TODO long sig types
                        if (type.getPubkeyLen() > 128)
                            throw new UnsupportedOperationException("TODO");
                        cert = new KeyCertificate(type, ptype);
                    } else {
                        cert = Certificate.NULL_CERT;
                    }
                    byte[] padding;
                    int padLen = (SigningPublicKey.KEYSIZE_BYTES - spub.length()) +
                                 (PublicKey.KEYSIZE_BYTES - pub.length());
                    if (padLen > 0) {
                        padding = new byte[padLen];
                        for (int i = 0; i < padLen; i += PADDING_ENTROPY) {
                            System.arraycopy(rand, 0, padding, i, Math.min(PADDING_ENTROPY, padLen - i));
                        }
                    } else {
                        padding = null;
                    }
                    pub.writeBytes(out);
                    if (padding != null)
                        out.write(padding);
                    spub.writeBytes(out);
                    cert.writeBytes(out);
                    priv.writeBytes(out);
                    spriv.writeBytes(out);
                } else {
                    write();
                }
            } catch (GeneralSecurityException gse) {
                throw new RuntimeException("keygen fail", gse);
            } finally {
                if (out != null) {
                    try { out.close(); } catch (IOException ioe) {}
                }
            }
        }
        return getDestination();
    }

    /**
     *  If the destination is not set, read it in from the file.
     *  Also sets the local privKey and signingPrivKey.
     */
    public Destination getDestination() throws I2PSessionException, IOException, DataFormatException {
        if (dest == null) {
            I2PSession s = open();
            if (s != null) {
                this.dest = new VerifiedDestination(s.getMyDestination());
                this.privKey = s.getDecryptionKey();
                this.signingPrivKey = s.getPrivateKey();
                if (s.isOffline()) {
                    _offlineExpiration = s.getOfflineExpiration();
                    _transientSigningPubKey = s.getTransientSigningPublicKey();
                    _offlineSignature = s.getOfflineSignature();
                    _transientSigningPrivKey = signingPrivKey;
                    // set dummy SPK
                    SigType type = dest.getSigningPublicKey().getType();
                    byte[] dbytes = new byte[type.getPrivkeyLen()];
                    signingPrivKey = new SigningPrivateKey(type, dbytes);
                }
            }
        }
        return this.dest;
    }

    public void setDestination(Destination d) {
        this.dest = d;
    }
    
    /**
     * Change cert type - caller must also call write().
     * Side effect - creates new Destination object.
     */
    public Certificate setCertType(int t) {
        if (this.dest == null)
            throw new IllegalArgumentException("Dest is null");
        Certificate c = new Certificate();
        c.setCertificateType(t);
        // dests now immutable, must create new
        Destination newdest = new Destination();
        newdest.setPublicKey(dest.getPublicKey());
        newdest.setSigningPublicKey(dest.getSigningPublicKey());
        newdest.setCertificate(c);
        dest = newdest;
        return c;
    }
    
    /**
     * Change cert type - caller must also call write().
     * Side effect - creates new Destination object.
     * @since 0.9.12
     */
    public Certificate setKeyCert(SigType type) {
        if (type == SigType.DSA_SHA1)
            return setCertType(Certificate.CERTIFICATE_TYPE_NULL);
        if (dest == null)
            throw new IllegalArgumentException("Dest is null");
        KeyCertificate c = new KeyCertificate(type);
        SimpleDataStructure signingKeys[];
        try {
            signingKeys = KeyGenerator.getInstance().generateSigningKeys(type);
        } catch (GeneralSecurityException gse) {
            throw new RuntimeException("keygen fail", gse);
        }
        SigningPublicKey signingPubKey = (SigningPublicKey) signingKeys[0];
        signingPrivKey = (SigningPrivateKey) signingKeys[1];
        // dests now immutable, must create new
        Destination newdest = new Destination();
        newdest.setPublicKey(dest.getPublicKey());
        newdest.setSigningPublicKey(signingPubKey);
        // fix up key certificate or padding
        int len = type.getPubkeyLen();
        if (len < 128) {
            byte[] pad = new byte[128 - len];
            RandomSource.getInstance().nextBytes(pad);
            newdest.setPadding(pad);
        } else if (len > 128) {
            System.arraycopy(signingPubKey.getData(), 128, c.getPayload(), KeyCertificate.HEADER_LENGTH, len - 128);
        }
        newdest.setCertificate(c);
        dest = newdest;
        return c;
    }
    
    /** change to hashcash cert - caller must also call write() */
    public Certificate setHashCashCert(int effort) {
        Certificate c = setCertType(Certificate.CERTIFICATE_TYPE_HASHCASH);
        long begin = System.currentTimeMillis();
        System.out.println("Starting hashcash generation now...");
        String resource = this.dest.getPublicKey().toBase64() + this.dest.getSigningPublicKey().toBase64();
        HashCash hc;
        try {
            hc = HashCash.mintCash(resource, effort);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        System.out.println("Generation took: " + DataHelper.formatDuration(System.currentTimeMillis() - begin));
        System.out.println("Full Hashcash is: " + hc);
        // Take the resource out of the stamp
        String hcs = hc.toString();
        int end1 = 0;
        for (int i = 0; i < 3; i++) {
            end1 = 1 + hcs.indexOf(':', end1);
            if (end1 < 0) {
                System.out.println("Bad hashcash");
                return null;
            }
        }
        int start2 = hcs.indexOf(':', end1);
        if (start2 < 0) {
            System.out.println("Bad hashcash");
            return null;
        }
        hcs = hcs.substring(0, end1) + hcs.substring(start2);
        System.out.println("Short Hashcash is: " + hcs);

        c.setPayload(DataHelper.getUTF8(hcs));
        return c;
    }
    
    /** sign this dest by dest found in pkf2 - caller must also call write() */
    public Certificate setSignedCert(PrivateKeyFile pkf2) {
        Certificate c = setCertType(Certificate.CERTIFICATE_TYPE_SIGNED);
        Destination d2;
        try {
            d2 = pkf2.getDestination();
        } catch (I2PException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
        if (d2 == null)
            return null;
        SigningPrivateKey spk2 = pkf2.getSigningPrivKey();
        System.out.println("Signing With Dest:");
        System.out.println(pkf2.toString());

        int len = PublicKey.KEYSIZE_BYTES + SigningPublicKey.KEYSIZE_BYTES; // no cert 
        byte[] data = new byte[len];
        System.arraycopy(this.dest.getPublicKey().getData(), 0, data, 0, PublicKey.KEYSIZE_BYTES);
        System.arraycopy(this.dest.getSigningPublicKey().getData(), 0, data, PublicKey.KEYSIZE_BYTES, SigningPublicKey.KEYSIZE_BYTES);
        byte[] payload = new byte[Hash.HASH_LENGTH + Signature.SIGNATURE_BYTES];
        Signature sign = DSAEngine.getInstance().sign(data, spk2);
        if (sign == null)
            return null;
        byte[] sig = sign.getData();
        System.arraycopy(sig, 0, payload, 0, Signature.SIGNATURE_BYTES);
        // Add dest2's Hash for reference
        byte[] h2 = d2.calculateHash().getData();
        System.arraycopy(h2, 0, payload, Signature.SIGNATURE_BYTES, Hash.HASH_LENGTH);
        c.setCertificateType(Certificate.CERTIFICATE_TYPE_SIGNED);
        c.setPayload(payload);
        return c;
    }
    
    /**
     *  Private key may be random data or all zeros for Destinations as of 0.9.57
     *
     *  @return null on error or if not initialized
     *  @deprecated this key is unused
     */
    @Deprecated
    public PrivateKey getPrivKey() {
        try {
            // call this to force initialization
            getDestination();
        } catch (Exception e) {
            return null;
        }
        return this.privKey;
    }

    /**
     *  @return null on error or if not initialized
     */
    public SigningPrivateKey getSigningPrivKey() {
        try {
            // call this to force initialization
            getDestination();
        } catch (Exception e) {
            return null;
        }
        return this.signingPrivKey;
    }
    
    //// offline methods

    /**
     *  Does this session have offline and transient keys?
     *  @since 0.9.38
     */
    public boolean isOffline() {
        try {
            // call this to force initialization
            getDestination();
        } catch (Exception e) {
            return false;
        }
        return _offlineSignature != null;
    }

    /**
     *  Side effect - zeroes out the current signing private key
     *  @since 0.9.38
     */
    public void setOfflineData(long expires, SigningPublicKey transientPub, Signature sig, SigningPrivateKey transientPriv) {
        if (!signingPrivKey.isOffline()) {
            SigType type = getSigningPrivKey().getType();
            byte[] dbytes = new byte[type.getPrivkeyLen()];
            signingPrivKey = new SigningPrivateKey(type, dbytes);
        }
        _offlineExpiration = expires;
        _transientSigningPubKey = transientPub;
        _offlineSignature = sig;
        _transientSigningPrivKey = transientPriv;
    }

    /**
     *  @return Java time (ms) or 0 if not initialized or does not have offline keys
     *  @since 0.9.38
     */
    public long getOfflineExpiration() {
        return _offlineExpiration;
    }

    /**
     *  @since 0.9.38
     */
    public Signature getOfflineSignature() {
        return _offlineSignature;
    }

    /**
     *  @return null on error or if not initialized or does not have offline keys
     *  @since 0.9.38
     */
    public SigningPublicKey getTransientSigningPubKey() {
        try {
            // call this to force initialization
            getDestination();
        } catch (Exception e) {
            return null;
        }
        return _transientSigningPubKey;
    }

    /**
     *  @return null on error or if not initialized or does not have offline keys
     *  @since 0.9.38
     */
    public SigningPrivateKey getTransientSigningPrivKey() {
        try {
            // call this to force initialization
            getDestination();
        } catch (Exception e) {
            return null;
        }
        return _transientSigningPrivKey;
    }

    //// end offline methods


    public I2PSession open() throws I2PSessionException, IOException {
        return this.open(new Properties());
    }

    public I2PSession open(Properties opts) throws I2PSessionException, IOException {
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(this.file));
            I2PSession s = this.client.createSession(in, opts);
            return s;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    /**
     *  Copied from I2PClientImpl.createDestination()
     */
    public void write() throws IOException, DataFormatException {
        OutputStream out = null;
        try {
            out = new SecureFileOutputStream(this.file);
            this.dest.writeBytes(out);
            this.privKey.writeBytes(out);
            this.signingPrivKey.writeBytes(out);
            if (isOffline()) {
                DataHelper.writeLong(out, 4, _offlineExpiration / 1000);
                DataHelper.writeLong(out, 2, _transientSigningPubKey.getType().getCode());
                _transientSigningPubKey.writeBytes(out);
                _offlineSignature.writeBytes(out);
                _transientSigningPrivKey.writeBytes(out);
            }
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ioe) {}
            }
        }
    }

    /**
     *  Verify that the PublicKey matches the PrivateKey, and
     *  the SigningPublicKey matches the SigningPrivateKey.
     *
     *  NOTE this will fail for Destinations containing random padding for the enc. key
     *
     *  @return success
     *  @since 0.9.16
     */
    public boolean validateKeyPairs() {
        try {
            if (!dest.getPublicKey().equals(KeyGenerator.getPublicKey(privKey)))
                return false;
            return dest.getSigningPublicKey().equals(KeyGenerator.getSigningPublicKey(signingPrivKey));
        } catch (IllegalArgumentException iae) {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder(1024);
        boolean isRI = !getClass().equals(PrivateKeyFile.class) ||
                       (privKey != null && privKey.getType() != EncType.ELGAMAL_2048);
        s.append(isRI ? "RouterIdentity: " : "Destination: ");
        s.append(this.dest != null ? this.dest.toBase64() : "null");
        s.append("\nB32        : ");
        s.append(this.dest != null ? this.dest.toBase32() : "null");
        if (!isRI && dest != null) {
            SigningPublicKey spk = dest.getSigningPublicKey();
            SigType type = spk.getType();
            if (type == SigType.EdDSA_SHA512_Ed25519 ||
                type == SigType.RedDSA_SHA512_Ed25519) {
                s.append("\nBlinded B32: ").append(Blinding.encode(spk));
                s.append("\n + auth key: ").append(Blinding.encode(spk, false, true));
                s.append("\n + password: ").append(Blinding.encode(spk, true, false));
                s.append("\n + auth/pw : ").append(Blinding.encode(spk, true, true));
            }
        }
        s.append("\nContains: ");
        s.append(this.dest);
        if (isRI) {
            s.append("\nPrivate Key: ");
            s.append(this.privKey);
        }
        s.append("\nSigining Private Key: ");
        if (!isRI && isOffline()) {
            s.append("offline\nOffline Signature Expires: ");
            s.append(new Date(getOfflineExpiration()));
            s.append("\nTransient Signing Public Key: ");
            s.append(_transientSigningPubKey);
            s.append("\nOffline Signature: ");
            s.append(_offlineSignature);
            s.append("\nTransient Signing Private Key: ");
            s.append(_transientSigningPrivKey);
        } else {
            s.append(this.signingPrivKey);
        }
        s.append("\n");
        return s.toString();
    }
    
    public static String estimateHashCashTime(int hashEffort) {
        if (hashEffort <= 0 || hashEffort > 160)
            return "Bad HashCash value: " + hashEffort;
        long low = Long.MAX_VALUE;
        try {
            low = HashCash.estimateTime(hashEffort);
        } catch (NoSuchAlgorithmException e) {}
        // takes a lot longer than the estimate usually...
        // maybe because the resource string is much longer than used in the estimate?
        return "It is estimated that generating a HashCash Certificate with value " + hashEffort +
               " for the Destination will take " +
               ((low < 1000l * 24l * 60l * 60l * 1000l)
                 ?
                   "approximately " + DataHelper.formatDuration(low) +
                   " to " + DataHelper.formatDuration(4*low)
                 :
                   "longer than three years!"
               );
    }    
    
    /**
     *  Sample code to verify a 3rd party signature.
     *  This just goes through all the hosts.txt files and tries everybody.
     *  You need to be in the $I2P directory or have a local hosts.txt for this to work.
     *  Doubt this is what you want as it is super-slow, and what good is
     *  a signing scheme where anybody is allowed to sign?
     *
     *  In a real application you would make a list of approved signers,
     *  do a naming lookup to get their Destinations, and try those only.
     *  Or do a netDb lookup of the Hash in the Certificate, do a reverse
     *  naming lookup to see if it is allowed, then verify the Signature.
     */
    public static boolean verifySignature(Destination d) {
        if (d.getCertificate().getCertificateType() != Certificate.CERTIFICATE_TYPE_SIGNED)
            return false;
        int len = PublicKey.KEYSIZE_BYTES + SigningPublicKey.KEYSIZE_BYTES; // no cert 
        byte[] data = new byte[len];
        System.arraycopy(d.getPublicKey().getData(), 0, data, 0, PublicKey.KEYSIZE_BYTES);
        System.arraycopy(d.getSigningPublicKey().getData(), 0, data, PublicKey.KEYSIZE_BYTES, SigningPublicKey.KEYSIZE_BYTES);
        Signature sig = new Signature();
        byte[] payload = d.getCertificate().getPayload();
        Hash signerHash = null;
        if (payload == null) {
            System.out.println("Bad signed cert - no payload");
            return false;
        } else if (payload.length == Signature.SIGNATURE_BYTES) {
            sig.setData(payload);
        } else if (payload.length == Certificate.CERTIFICATE_LENGTH_SIGNED_WITH_HASH) {
            byte[] pl = new byte[Signature.SIGNATURE_BYTES];
            System.arraycopy(payload, 0, pl, 0, Signature.SIGNATURE_BYTES);
            sig.setData(pl);
            byte[] hash = new byte[Hash.HASH_LENGTH];
            System.arraycopy(payload, Signature.SIGNATURE_BYTES, hash, 0, Hash.HASH_LENGTH);
            signerHash = new Hash(hash);
            System.out.println("Destination is signed by " + Base32.encode(hash) + ".b32.i2p");
        } else {
            System.out.println("Bad signed cert - length = " + payload.length);
            return false;
        }
     
        String[] filenames = new String[] {"privatehosts.txt", "userhosts.txt", "hosts.txt"};
        int tried = 0;
        for (int i = 0; i < filenames.length; i++) { 
            Properties hosts = new Properties();
            try {
                File f = new File(filenames[i]);
                if ( (f.exists()) && (f.canRead()) ) {
                    DataHelper.loadProps(hosts, f, true);
                    int sz = hosts.size();
                    if (sz > 0) {
                        tried += sz;
                        if (signerHash == null)
                            System.out.println("Attempting to verify using " + sz + " hosts, this may take a while");
                    }
                    
                    for (Map.Entry<Object, Object> entry : hosts.entrySet())  {
                        String s = (String) entry.getValue();
                        Destination signer = new Destination(s);
                        // make it go faster if we have the signerHash hint
                        if (signerHash == null || signer.calculateHash().equals(signerHash)) {
                            if (checkSignature(sig, data, signer.getSigningPublicKey())) {
                                System.out.println("Good signature from: " + entry.getKey());
                                return true;
                            }
                            if (signerHash != null) {
                                System.out.println("Bad signature from: " + entry.getKey());
                                // could probably return false here but keep going anyway
                            }
                        }
                    }
                }
            } catch (DataFormatException dfe) {
            } catch (IOException ioe) {
            }
            // not found, continue to the next file
        }
        if (tried > 0)
            System.out.println("No valid signer found");
        else
            System.out.println("No addressbooks found to valididate signer");
        return false;
    }

    public static boolean checkSignature(Signature s, byte[] data, SigningPublicKey spk) {
        return DSAEngine.getInstance().verifySignature(s, data, spk);
    }
}
