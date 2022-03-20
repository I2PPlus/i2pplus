package net.i2p.router.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.cert.X509CRL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.CertUtil;
import net.i2p.crypto.KeyStoreUtil;
import net.i2p.crypto.SigType;
import net.i2p.crypto.SigUtil;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;

/**
 * Utilities for creating, storing, retrieving the signing keys for
 * the netdb family feature
 *
 * @since 0.9.24
 */
public class FamilyKeyCrypto {

    private final RouterContext _context;
    private final Log _log;
    private final Map<Hash, Verified> _verified;
    private final Map<String, SigningPublicKey> _knownKeys;
    private final Map<Hash, Result> _negativeCache;
    private final Set<Hash> _ourFamily;
    // following for verification only, otherwise null
    private final String _fname;
    private final SigningPrivateKey _privkey;
    private final SigningPublicKey _pubkey;

    public static final String PROP_KEYSTORE_PASSWORD = "netdb.family.keystorePassword";
    public static final String PROP_FAMILY_NAME = "netdb.family.name";
    public static final String PROP_KEY_PASSWORD = "netdb.family.keyPassword";
    public static final String CERT_SUFFIX = ".crt";
    public static final String CRL_SUFFIX = ".crl";
    public static final String KEYSTORE_PREFIX = "family-";
    public static final String KEYSTORE_SUFFIX = ".ks";
    public static final String CN_SUFFIX = ".family.i2p.net";
    private static final int DEFAULT_KEY_VALID_DAYS = 3652;  // 10 years
    // Note that we can't use RSA here, as the b64 sig would exceed the 255 char limit for a Mapping
    // Note that we can't use EdDSA here, as keystore doesn't know how, and encoding/decoding is unimplemented
    private static final String DEFAULT_KEY_ALGORITHM = SigType.ECDSA_SHA256_P256.isAvailable() ? "EC" : "DSA";
    private static final int DEFAULT_KEY_SIZE = SigType.ECDSA_SHA256_P256.isAvailable() ? 256 : 1024;
    //private static final String DEFAULT_KEY_ALGORITHM = "EdDSA";
    //private static final int DEFAULT_KEY_SIZE = 256;
    private static final String KS_DIR = "keystore";
    private static final String CERT_DIR = "certificates/family";
    private static final String CRL_DIR = "crls";
    public static final String OPT_NAME = "family";
    public static final String OPT_SIG = "family.sig";
    public static final String OPT_KEY = "family.key";


    /**
     *  For signing and verification.
     *
     *  If the context property netdb.family.name is set, this can be used for signing,
     *  else only for verification.
     */
    public FamilyKeyCrypto(RouterContext context) throws GeneralSecurityException {
        _context = context;
        _log = _context.logManager().getLog(FamilyKeyCrypto.class);
        _fname = _context.getProperty(PROP_FAMILY_NAME);
        if (_fname != null) {
            if (_fname.contains("/") || _fname.contains("\\") ||
                _fname.contains("..") || (new File(_fname)).isAbsolute() ||
                _fname.length() <= 0)
                throw new GeneralSecurityException("Illegal family name: " + _fname);
        }
        _privkey = (_fname != null) ? initialize() : null;
        _pubkey = (_privkey != null) ? _privkey.toPublic() : null;
        _verified = new ConcurrentHashMap<Hash, Verified>(16);
        _negativeCache = new ConcurrentHashMap<Hash, Result>(4);
        _ourFamily = (_privkey != null) ? new ConcurrentHashSet<Hash>(4) : Collections.<Hash>emptySet();
        _knownKeys = new HashMap<String, SigningPublicKey>(8);
        loadCerts();
    }

    /**
     * Create (if necessary) and load the key store, then run.
     */
    private SigningPrivateKey initialize() throws GeneralSecurityException {
        File dir = new SecureDirectory(_context.getConfigDir(), KS_DIR);
        File keyStore = new File(dir, KEYSTORE_PREFIX + _fname + KEYSTORE_SUFFIX);
        verifyKeyStore(keyStore);
        return getPrivKey(keyStore);
    }

    /**
     * Clears the caches
     */
    public void shutdown() {
        _verified.clear();
        _negativeCache.clear();
    }

    /**
     *  Caller must add family to RI also.
     *  throws on all errors
     *
     *  @param family non-null, must match that we were initialized with or will throw GSE
     *  @param h non-null
     *  @return non-null options to be added to the RI
     *  @throws GeneralSecurityException on null hash, null or changed family, or signing error
     */
    public Map<String, String> sign(String family, Hash h) throws GeneralSecurityException {
        if (_privkey == null) {
            _log.logAlways(Log.WARN, "family name now set, must restart router to generate or load keys");
            throw new GeneralSecurityException("family name now set, must restart router to generate or load keys");
        }
        if (h == null)
            throw new GeneralSecurityException("null router hash");
        if (!_fname.equals(family)) {
            _log.logAlways(Log.WARN, "family name changed, must restart router to generate or load new keys");
            throw new GeneralSecurityException("family name changed, must restart router to generate or load new keys");
        }
        byte[] nb = DataHelper.getUTF8(_fname);
        int len = nb.length + Hash.HASH_LENGTH;
        byte[] b = new byte[len];
        System.arraycopy(nb, 0, b, 0, nb.length);
        System.arraycopy(h.getData(), 0, b, nb.length, Hash.HASH_LENGTH);
        Signature sig = _context.dsa().sign(b, _privkey);
        if (sig == null)
            throw new GeneralSecurityException("sig failed");
        Map<String, String> rv = new HashMap<String, String>(3);
        rv.put(OPT_NAME, family);
        rv.put(OPT_KEY, _pubkey.getType().getCode() + ":" + _pubkey.toBase64());
        rv.put(OPT_SIG, sig.toBase64());
        return rv;
    }

    /**
     *  Do we have a valid family?
     *  @since 0.9.28
     */
    public boolean hasFamily() {
        return _pubkey != null;
    }

    /**
     *  Get verified members of our family.
     *  Will not contain ourselves.
     *
     *  @return non-null, not a copy, do not modify
     *  @since 0.9.28
     */
    public Set<Hash> getOurFamily() {
        return _ourFamily;
    }

    /**
     *  Get our family name.
     *
     *  @return name or null
     *  @since 0.9.28
     */
    public String getOurFamilyName() {
        return _fname;
    }

    /**
     *  Only STORED_KEY is fully trusted.
     *  RI_KEY is Java with key in the RI.
     *  NO_KEY is i2pd without a key in the RI.
     *
     *  @since 0.9.54
     */
    public enum Result { NO_FAMILY, NO_KEY, NO_SIG, NAME_CHANGED, SIG_CHANGED, INVALID_SIG,
                         UNSUPPORTED_SIG, BAD_KEY, BAD_SIG, RI_KEY, STORED_KEY }

    /**
     *  Cached name/sig/result.
     *
     *  @since 0.9.54
     */
    private static class Verified {
        public final String name, sig;
        public final Result result;
        public Verified(String n, String s, Result r) {
            name = n; sig = s; result = r;
        }
    }

    /** 
     *  Verify the family signature in a RouterInfo.
     *  This requires a family key in the RI,
     *  or a certificate file for the family
     *  in certificates/family.
     *
     *  @return Result
     */
    public Result verify(RouterInfo ri) {
        String name = ri.getOption(OPT_NAME);
        if (name == null)
            return Result.NO_FAMILY;
        Result rv = verify(ri, name);
        if (_log.shouldInfo())
            _log.info("Result: " + rv + " for " + name + ' ' + ri.getHash());
        return rv;
    }

    /**
     *  Verify the family in a RouterInfo matches ours and the signature is good.
     *  Returns false if we don't have a family and sig, or they don't.
     *  Returns false for ourselves.
     *
     *  @return true if family matches with good sig
     *  @since 0.9.28
     */
    public boolean verifyOurFamily(RouterInfo ri) {
        if (_pubkey == null)
            return false;
        String name = ri.getOption(OPT_NAME);
        if (!_fname.equals(name))
            return false;
        Hash h = ri.getHash();
        if (_ourFamily.contains(h))
            return true;
        if (h.equals(_context.routerHash()))
            return false;
        boolean rv = verify(ri, name) == Result.STORED_KEY;
        if (rv) {
            _ourFamily.add(h);
            _log.logAlways(Log.INFO, "Found and verified member of our Family (" + _fname + "): [" + h.toBase64().substring(0,6) + "]");
        } else {
            if (_log.shouldWarn())
                _log.warn("Found spoofed member of our Family (" + _fname + "): [" + h.toBase64().substring(0,6) + "]");
        }
        return rv;
    }

    /**
     *  Verify the family in a RouterInfo, name already retrieved
     *  @since 0.9.28
     */
    private Result verify(RouterInfo ri, String name) {
        Hash h = ri.getHash();
        String ssig = ri.getOption(OPT_SIG);
        if (ssig == null) {
            if (_log.shouldInfo())
                _log.info("No signature detected for [" + h.toBase64().substring(0,6) + "] (Family:" + name + ")");
            return Result.NO_SIG;
        }
        Verified v = _verified.get(h);
        if (v != null) {
            if (!v.name.equals(name))
                return Result.NAME_CHANGED;
            if (v.sig.equals(ssig))
                return v.result;
            // sig changed, fall thru to re-check
            _verified.remove(h);
        }
        SigningPublicKey spk;
        boolean isKnownKey;
        if (name.equals(_fname)) {
            // us
            spk = _pubkey;
            isKnownKey = true;
        } else {
            Result r = _negativeCache.get(h);
            if (r != null)
                return r;
            spk = _knownKeys.get(name);
            isKnownKey = spk != null;
            if (!isKnownKey) {
                // look for a b64 key in the RI
                String skey = ri.getOption(OPT_KEY);
                if (skey != null) {
                    int colon = skey.indexOf(':');
                    // switched from ';' to ':' during dev, remove this later
                    if (colon < 0)
                        colon = skey.indexOf(';');
                    if (colon > 0) {
                        try {
                            int code = Integer.parseInt(skey.substring(0, colon));
                            SigType type = SigType.getByCode(code);
                            if (type != null) {
                                byte[] bkey = Base64.decode(skey.substring(colon + 1));
                                if (bkey != null) {
                                    spk = new SigningPublicKey(type, bkey);
                                }
                            }
                        } catch (NumberFormatException e) {
                            if (_log.shouldInfo())
                                _log.info("Bad b64 Family key: " + ri, e);
                             _negativeCache.put(h, Result.BAD_KEY);
                             return Result.BAD_KEY;
                        } catch (IllegalArgumentException e) {
                            if (_log.shouldInfo())
                                _log.info("Bad b64 Family key: " + ri, e);
                             _negativeCache.put(h, Result.BAD_KEY);
                             return Result.BAD_KEY;
                        } catch (ArrayIndexOutOfBoundsException e) {
                            if (_log.shouldInfo())
                                _log.info("Bad b64 Family key: " + ri, e);
                             _negativeCache.put(h, Result.BAD_KEY);
                             return Result.BAD_KEY;
                        }
                    }
                }
                if (spk == null) {
                    _negativeCache.put(h, Result.NO_KEY);
                    if (_log.shouldInfo())
                        _log.info("No cert or valid key for [" + h.toBase64().substring(0,6) + "] Family: " + name);
                    return Result.NO_KEY;
                }
            }
        }
        if (!spk.getType().isAvailable()) {
            _negativeCache.put(h, Result.UNSUPPORTED_SIG);
            if (_log.shouldInfo())
                _log.info("Unsupported crypto for signature for [" + h.toBase64().substring(0,6) + "]");
            return Result.UNSUPPORTED_SIG;
        }
        byte[] bsig = Base64.decode(ssig);
        if (bsig == null) {
            _negativeCache.put(h, Result.INVALID_SIG);
            if (_log.shouldInfo())
                _log.info("Bad signature [" + ssig + "] detected for [" + h.toBase64().substring(0,6) + "] Family: " + name);
            return Result.INVALID_SIG;
        }
        Signature sig;
        try {
            sig = new Signature(spk.getType(), bsig);
        } catch (IllegalArgumentException iae) {
            // wrong size (type mismatch)
            _negativeCache.put(h, Result.INVALID_SIG);
            if (_log.shouldInfo())
                _log.info("Bad signature detected for [" + ri.toBase64().substring(0,6) + "]", iae);
            return Result.INVALID_SIG;
        }
        byte[] nb = DataHelper.getUTF8(name);
        byte[] b = new byte[nb.length + Hash.HASH_LENGTH];
        System.arraycopy(nb, 0, b, 0, nb.length);
        System.arraycopy(ri.getHash().getData(), 0, b, nb.length, Hash.HASH_LENGTH);
        boolean ok = _context.dsa().verifySignature(sig, b, spk);
        Result rv;
        if (ok) {
            rv = isKnownKey ? Result.STORED_KEY : Result.RI_KEY;
            _verified.put(h, new Verified(name, ssig, rv));
        } else {
            if (_log.shouldInfo())
                _log.info("Family: " + name + " belonging to [" + h.toBase64().substring(0,6) + "] -> Verified? " + rv +
                          "\n* Signature: " + ssig);
            rv = Result.BAD_SIG;
            _negativeCache.put(h, rv);
        }
        return rv;
    }

    /**
     *  @return success if it exists and we have a password, or it was created successfully.
     *  @throws GeneralSecurityException on keystore error
     */
    private void verifyKeyStore(File ks) throws GeneralSecurityException {
        if (ks.exists()) {
            if (_context.getProperty(PROP_KEY_PASSWORD) == null) {
                String s ="Family key error, must set " + PROP_KEY_PASSWORD + " in " +
                          (new File(_context.getConfigDir(), "router.config")).getAbsolutePath();
                _log.error(s);
                throw new GeneralSecurityException(s);
            }
            return;
        }
        File dir = ks.getParentFile();
        if (!dir.exists()) {
            File sdir = new SecureDirectory(dir.getAbsolutePath());
            if (!sdir.mkdirs()) {
                String s ="Family key error, must set " + PROP_KEY_PASSWORD + " in " +
                          (new File(_context.getConfigDir(), "router.config")).getAbsolutePath();
                _log.error(s);
                throw new GeneralSecurityException(s);
            }
        }

        try {
            createKeyStore(ks);
        } catch (IOException ioe) {
            throw new GeneralSecurityException("Failed to create NetDb Family keystore", ioe);
        }
    }


    /**
     * Call out to keytool to create a new keystore with a keypair in it.
     * Trying to do this programatically is a nightmare, requiring either BouncyCastle
     * libs or using proprietary Sun libs, and it's a huge mess.
     * If successful, stores the keystore password and key password in router.config.
     *
     * @throws GeneralSecurityException on all errors
     */
    private void createKeyStore(File ks) throws GeneralSecurityException, IOException {
        // make a random 48 character password (30 * 8 / 5)
        String keyPassword = KeyStoreUtil.randomString();
        // and one for the cname
        String cname = _fname + CN_SUFFIX;

        Object[] rv = KeyStoreUtil.createKeysAndCRL(ks, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD, _fname, cname, "family",
                                                  DEFAULT_KEY_VALID_DAYS, DEFAULT_KEY_ALGORITHM,
                                                  DEFAULT_KEY_SIZE, keyPassword);

                Map<String, String> changes = new HashMap<String, String>();
                changes.put(PROP_KEYSTORE_PASSWORD, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD);
                changes.put(PROP_KEY_PASSWORD, keyPassword);
                changes.put(PROP_FAMILY_NAME, _fname);
                _context.router().saveConfig(changes, null);

            _log.logAlways(Log.INFO, "Created new private key for netdb family \"" + _fname +
                           "\" in keystore: " + ks.getAbsolutePath() + "\n" +
                           "Copy the keystore to the other routers in the family,\n" +
                           "and add the following entries to their router.config file:\n" +
                           PROP_FAMILY_NAME + '=' + _fname + '\n' +
                           PROP_KEYSTORE_PASSWORD + '=' + KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD + '\n' +
                           PROP_KEY_PASSWORD + '=' + keyPassword);

        X509Certificate cert = (X509Certificate) rv[2];
        exportCert(cert);
        X509CRL crl = (X509CRL) rv[3];
        exportCRL(ks.getParentFile(), crl);
    }

    /**
     * Save the public key certificate
     * so the clients can get to it.
     */
    private void exportCert(X509Certificate cert) {
        File sdir = new SecureDirectory(_context.getConfigDir(), CERT_DIR);
        if (sdir.exists() || sdir.mkdirs()) {
            String name = _fname.replace("@", "_at_") + CERT_SUFFIX;
            File out = new File(sdir, name);
            boolean success = CertUtil.saveCert(cert, out);
            if (success) {
                _log.logAlways(Log.INFO, "Created new public key certificate for NetDb Family \"" + _fname +
                           "\" in file: " + out.getAbsolutePath() + "\n" +
                           "The certificate will be associated with your router identity.\n" +
                           "Copy the certificate to the directory $I2P/" + CERT_DIR + " for each of the other routers in the Family.\n" +
                           "Give this certificate to an I2P developer for inclusion in the next I2P release.");
            } else {
                _log.error("Error saving Family key certificate");
            }
        } else {
            _log.error("Error saving Family key certificate");
        }
    }

    /**
     * Save the CRL just in case.
     * @param ksdir parent of directory to save in
     * @since 0.9.25
     */
    private void exportCRL(File ksdir, X509CRL crl) {
        File sdir = new SecureDirectory(ksdir, CRL_DIR);
        if (sdir.exists() || sdir.mkdirs()) {
            String name = KEYSTORE_PREFIX + _fname.replace("@", "_at_") + '-' + System.currentTimeMillis() + CRL_SUFFIX;
            File out = new File(sdir, name);
            boolean success = CertUtil.saveCRL(crl, out);
            if (success) {
                _log.logAlways(Log.INFO, "Created certificate revocation list (CRL) for NetDb Family \"" + _fname +
                           "\" in file: " + out.getAbsolutePath() + "\n" +
                           "Back up the keystore and CRL files and keep them secure.\n" +
                           "If your private key is ever compromised, give the CRL to an I2P developer for publication.");
            } else {
                _log.error("Error saving family key CRL");
            }
        } else {
            _log.error("Error saving family key CRL");
        }
    }

    /**
     * Load all the certs.
     *
     * @since 0.9.54
     */
    private void loadCerts() {
        File dir = new File(_context.getBaseDir(), CERT_DIR);
        File[] files = dir.listFiles(new FileSuffixFilter(CERT_SUFFIX));
        if (files == null)
            return;
        for (File file : files) {
            String name = file.getName();
            name = name.substring(0, name.length() - CERT_SUFFIX.length());
            SigningPublicKey spk = loadCert(file);
            if (spk != null)
                _knownKeys.put(name, spk);
        }
        if (_log.shouldInfo())
            _log.info("Loaded " + _knownKeys.size() + " keys");
    }

    /** 
     * Load a public key from a cert.
     *
     * @return null on all errors
     */
    private SigningPublicKey loadCert(File file) {
        try {
            PublicKey pk = CertUtil.loadKey(file);
            return SigUtil.fromJavaKey(pk);
        } catch (GeneralSecurityException gse) {
            _log.error("Error loading Family key " + file, gse);
        } catch (IOException ioe) {
            _log.error("Error loading Family key " + file, ioe);
        }
        return null;
    }

    /**
     * Get the private key from the keystore
     * @return non-null, throws on all errors
     */
    private SigningPrivateKey getPrivKey(File ks) throws GeneralSecurityException {
        String ksPass = _context.getProperty(PROP_KEYSTORE_PASSWORD, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD);
        String keyPass = _context.getProperty(PROP_KEY_PASSWORD);
        if (keyPass == null)
            throw new GeneralSecurityException("No key password, set " + PROP_KEY_PASSWORD +
                       " in " + (new File(_context.getConfigDir(), "router.config")).getAbsolutePath());
        try {
            PrivateKey pk = KeyStoreUtil.getPrivateKey(ks, ksPass, _fname, keyPass);
            if (pk == null)
                throw new GeneralSecurityException("Family key not found: " + _fname);
            // ensure the cert is there in case it needs to be exported
            String familyName = _fname.replace("@", "_at_");
            File dir = new File(_context.getBaseDir(), CERT_DIR);
            File file = new File(dir, familyName + CERT_SUFFIX);
            if (!file.exists())
                KeyStoreUtil.exportCert(ks, ksPass, _fname, file);
            return SigUtil.fromJavaKey(pk);
        } catch (IOException ioe) {
            throw new GeneralSecurityException("Error loading Family key " + _fname, ioe);
        }
    }

    /** @since 0.9.36 */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: FamilyKeyCrypto keystore.ks familyname");
            System.exit(1);
        }
        File ks = new File(args[0]);
        if (ks.exists()) {
            System.err.println("Keystore already exists: " + ks);
            System.exit(1);
        }
        String fname = args[1];
        String cname = fname + CN_SUFFIX;
        String keyPassword = KeyStoreUtil.randomString();
        try {
            KeyStoreUtil.createKeysAndCRL(ks, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD, fname, cname, "family",
                                          DEFAULT_KEY_VALID_DAYS, DEFAULT_KEY_ALGORITHM,
                                          DEFAULT_KEY_SIZE, keyPassword);
            System.out.println("Family keys generated and saved in " + ks + '\n' +
                               "Copy to " + KS_DIR + '/' + KEYSTORE_PREFIX + fname + KEYSTORE_SUFFIX + " in the i2p configuration directory\n" +
                               "Family key configuration for router.config:\n" +
                               PROP_FAMILY_NAME + '=' +  fname + '\n' +
                               PROP_KEYSTORE_PASSWORD + '=' + KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD + '\n' +
                               PROP_KEY_PASSWORD + '=' + keyPassword);
        } catch (Exception e) {
            System.err.println("Failed");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
