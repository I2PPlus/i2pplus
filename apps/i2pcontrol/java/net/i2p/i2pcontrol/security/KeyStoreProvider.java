package net.i2p.i2pcontrol.security;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import net.i2p.util.Log;
import net.i2p.crypto.KeyStoreUtil;

/**
 * Provider for managing I2PControl keystore operations.
 * Handles loading and accessing SSL certificates for secure connections.
 */
public class KeyStoreProvider {
    /**
     * DEFAULT_CERTIFICATE_ALGORITHM_STRING.
     */
    public static final String DEFAULT_CERTIFICATE_ALGORITHM_STRING = "RSA";
    /**
     * DEFAULT_CERTIFICATE_KEY_LENGTH.
     */
    public static final int DEFAULT_CERTIFICATE_KEY_LENGTH = 4096;
    /**
     * DEFAULT_CERTIFICATE_VALIDITY.
     */
    public static final int DEFAULT_CERTIFICATE_VALIDITY = 365 * 10;
    /**
     * DEFAULT_CERTIFICATE_DOMAIN.
     */
    public final static String DEFAULT_CERTIFICATE_DOMAIN = "localhost";
    /**
     * DEFAULT_CERTIFICATE_ALIAS.
     */
    public final static String DEFAULT_CERTIFICATE_ALIAS = "I2PControl CA";
    /**
     * DEFAULT_KEYSTORE_NAME.
     */
    public static final String DEFAULT_KEYSTORE_NAME = "i2pcontrol.ks";
    /**
     * DEFAULT_KEYSTORE_PASSWORD.
     */
    public static final String DEFAULT_KEYSTORE_PASSWORD = KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD;
    private static final Log _log = new Log(KeyStoreProvider.class);
    private static String DEFAULT_CERTIFICATE_PASSWORD;

    static {
        // Generate a random secure password on class load
        StringBuilder sb = new StringBuilder(16);
        SecureRandom r = new SecureRandom();
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        for (int i = 0; i < 16; i++)
            sb.append(chars.charAt(r.nextInt(chars.length())));
        DEFAULT_CERTIFICATE_PASSWORD = sb.toString();
    }

    /**
     * Get the dynamically generated certificate password.
     * @return the certificate password
     * @since 0.9.70
     */
    public static String getCertificatePassword() {
        return DEFAULT_CERTIFICATE_PASSWORD;
    }
    private final String _pluginDir;
    private KeyStore _keystore;

    /** @param pluginDir path to the plugin directory for keystore storage */
    public KeyStoreProvider(String pluginDir) {
        _pluginDir = pluginDir;
    }

    /** Create keystore if it does not exist */
    public void initialize() {
        KeyStoreUtil.createKeys(new File(getKeyStoreLocation()),
                                DEFAULT_KEYSTORE_PASSWORD,
                                DEFAULT_CERTIFICATE_ALIAS,
                                DEFAULT_CERTIFICATE_DOMAIN,
                                "i2pcontrol",
                                DEFAULT_CERTIFICATE_VALIDITY,
                                DEFAULT_CERTIFICATE_ALGORITHM_STRING,
                                DEFAULT_CERTIFICATE_KEY_LENGTH,
                                DEFAULT_CERTIFICATE_PASSWORD);
    }

    /**
     *  @return null on failure
     */
    public static X509Certificate readCert(KeyStore ks, String certAlias) {
        try {
            X509Certificate cert = (X509Certificate) ks.getCertificate(certAlias);

            if (cert == null) {
                throw new RuntimeException("Got null cert from keystore!");
            }

            try {
                cert.verify(cert.getPublicKey());
                return cert;
            } catch (Exception e) {
                _log.log(Log.WARN, "Failed to verify caCert certificate against caCert", e);
            }
        } catch (KeyStoreException e) {
            _log.log(Log.WARN, "Failed to read cert from keystore", e);
        }
        return null;
    }

    /**
     *  Load the keystore, creating it first if it does not exist yet.
     *
     *  A keystore file that exists but cannot be read - wrong password,
     *  truncated or corrupt contents - is never replaced, since that would
     *  destroy any credentials it holds. That case, and any failure to create
     *  a missing file, is logged at WARN and reported as null. Nothing is
     *  cached in the failure case, so a later call retries the load.
     *
     *  @return the keystore, or null if it could not be read or created
     */
    public synchronized KeyStore getDefaultKeyStore() {
        if (_keystore != null)
            return _keystore;

        File keyStoreFile = new File(getKeyStoreLocation());
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            if (keyStoreFile.exists())
                return _keystore = load(ks, keyStoreFile);

            initialize();
            if (!keyStoreFile.exists())
                throw new IOException("KeyStore file " + keyStoreFile.getAbsolutePath() + " wasn't created");
            return _keystore = load(ks, keyStoreFile);
        } catch (Exception e) {
            _log.log(Log.WARN, "Failed to load or create the I2PControl keystore", e);
            return null;
        }
    }

    /**
     *  @param ks the empty keystore to load into
     *  @param keyStoreFile the file to read, which must exist
     *  @return ks, loaded from keyStoreFile
     *  @throws IOException if the file cannot be read
     *  @throws GeneralSecurityException if the file is not a valid keystore
     *          for the default type
     */
    private static KeyStore load(KeyStore ks, File keyStoreFile) throws IOException, GeneralSecurityException {
        try (InputStream is = new FileInputStream(keyStoreFile)) {
            ks.load(is, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
        }
        return ks;
    }

    /** @return full path to the keystore file */
    public String getKeyStoreLocation() {
        File keyStoreFile = new File(_pluginDir, DEFAULT_KEYSTORE_NAME);
        return keyStoreFile.getAbsolutePath();
    }
}
