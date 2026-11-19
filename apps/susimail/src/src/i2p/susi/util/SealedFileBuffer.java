package i2p.susi.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.i2p.data.DataHelper;
import net.i2p.util.SecureFileOutputStream;

/**
 * File Buffer that stores its data compressed and encrypted, so a message left in the
 * disk cache is not readable just because the directory is.
 *
 * The file is the four magic bytes, a random 12-byte IV, then AES-256-GCM over the
 * gzip-compressed data. GCM rather than a mode without a tag, because an unauthenticated
 * blob can be edited on disk and the reader has no way to notice; the tag is what makes
 * a tampered cache file a failure instead of a different message. The file name is the
 * additional authenticated data, so a message cannot be renamed, or copied into another
 * account's cache, and still open.
 *
 * A file written by the older {@link GzipFileBuffer} is still readable, see
 * {@link #isSealed}.
 *
 * @since 0.9.72+
 */
public class SealedFileBuffer extends FileBuffer {

    /** The four bytes at the start of a sealed file, which is also its version tag. */
    private static final byte[] MAGIC = {'S', 'M', 'V', '1'};
    /** Length of the per-file GCM nonce, in bytes. */
    private static final int IV_LENGTH = 12;
    /** Length of the GCM authentication tag, in bits. */
    private static final int TAG_BITS = 128;
    /** Length of the per-account salt handed to PBKDF2, in bytes. */
    public static final int SALT_LENGTH = 16;
    /** Length of the derived data key, in bits. */
    public static final int KEY_BITS = 256;
    /**
     * PBKDF2 rounds for turning the mailbox password into the cache key. High enough
     * to cost an attacker who has the cache a second per guess, low enough that
     * opening a mailbox is not slowed down.
     */
    public static final int PBKDF2_ROUNDS = 600_000;

    /** Key the blob is sealed with. */
    private final SecretKey _key;
    /** The name the blob is authenticated against, as ASCII. */
    private final byte[] _binding;
    /** Length of the plaintext, in bytes. */
    private long _actualLength;
    /** Stream counting the bytes read. */
    private CountingInputStream _cis;
    /** Stream counting the bytes written. */
    private CountingOutputStream _cos;

    /**
     * Create a buffer over a sealed file, bound to that file's own name.
     *
     * @param file the file to read or write
     * @param key the AES key to seal with, non-null
     */
    public SealedFileBuffer(File file, SecretKey key) {
        this(file, key, file.getName());
    }

    /**
     * Create a buffer over a sealed file, bound to a name other than the file's own.
     *
     * A file being re-sealed is written under a temporary name and then renamed into
     * place, so the blob has to be authenticated against the name it will end up with,
     * not the one it was written under.
     *
     * @param file the file to read or write
     * @param key the AES key to seal with, non-null
     * @param binding the name the blob is bound to
     */
    public SealedFileBuffer(File file, SecretKey key, String binding) {
        super(file);
        if (key == null)
            throw new IllegalArgumentException("a sealed file needs a key");
        _key = key;
        _binding = DataHelper.getASCII(binding);
    }

    /**
     * The bytes the blob is bound to, so a sealed message cannot be renamed, or
     * copied into another account's cache, and still be read.
     *
     * @return the bound name as ASCII
     */
    private byte[] binding() {
        return _binding;
    }

    /**
     * The cipher for this file, in encrypt or decrypt mode, already bound to the
     * file name.
     *
     * @param mode {@link Cipher#ENCRYPT_MODE} or {@link Cipher#DECRYPT_MODE}
     * @param iv the 12-byte nonce recorded in the file
     * @return the initialized cipher
     * @throws IOException if AES-GCM is unavailable in this JVM
     */
    private Cipher cipherFor(int mode, byte[] iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, _key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(binding());
            return cipher;
        } catch (GeneralSecurityException gse) {
            throw new IOException("AES/GCM unavailable for " + _file.getName() + ": " + gse, gse);
        }
    }

    /**
     * Read exactly buf.length bytes or fail, which a single read call does not do.
     *
     * @param in the stream to read
     * @param buf where to put the bytes
     * @throws IOException if the input ends before the buffer is full
     */
    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0)
                throw new IOException("ended before the sealed header was complete");
            got += n;
        }
    }

    /**
     * Get a decrypting, decompressing InputStream, wrapped with a counter.
     * Caller must call {@link #readComplete}.
     *
     * @return a stream over the plaintext
     * @throws IOException on I/O error, if the file is not a sealed one, or if
     *                     the authentication tag does not match, which covers a
     *                     wrong key, a renamed file and an edited blob
     */
    @Override
    public synchronized InputStream getInputStream() throws IOException {
        if (_cis != null)
            return _cis;
        InputStream in = new BufferedInputStream(new FileInputStream(_file));
        InputStream plain;
        try {
            byte[] magic = new byte[MAGIC.length];
            readFully(in, magic);
            if (!Arrays.equals(magic, MAGIC))
                throw new IOException("not a sealed file: " + _file.getName());
            byte[] iv = new byte[IV_LENGTH];
            readFully(in, iv);
            Cipher cipher = cipherFor(Cipher.DECRYPT_MODE, iv);
            plain = new GZIPInputStream(new CipherInputStream(in, cipher));
        } catch (IOException | RuntimeException e) {
            try { in.close(); } catch (IOException ioe) { /* ignored */ }
            throw e;
        }
        _cis = new CountingInputStream(plain);
        return _cis;
    }

    /**
     * Get an encrypting, compressing OutputStream, wrapped with a counter.
     * Caller must call {@link #writeComplete}.
     *
     * @return a stream taking plaintext
     * @throws IllegalStateException if a stream is already open for writing
     * @throws IOException on I/O error
     */
    @Override
    public synchronized OutputStream getOutputStream() throws IOException {
        if (_cos != null)
            throw new IllegalStateException();
        OutputStream out = new SecureFileOutputStream(_file);
        try {
            out.write(MAGIC);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            out.write(iv);
            Cipher cipher = cipherFor(Cipher.ENCRYPT_MODE, iv);
            _cos = new CountingOutputStream(new BufferedOutputStream(
                       new GZIPOutputStream(new CipherOutputStream(out, cipher))));
        } catch (IOException | RuntimeException e) {
            try { out.close(); } catch (IOException ioe) { /* ignored */ }
            _file.delete();
            throw e;
        }
        return _cos;
    }

    /**
     * Close the input stream and record the plaintext length if successful.
     *
     * @param success if true, records the bytes read
     */
    @Override
    public synchronized void readComplete(boolean success) {
        if (_cis != null) {
            if (success)
                _actualLength = _cis.getRead();
            try { _cis.close(); } catch (IOException ioe) { /* ignored */ }
            _cis = null;
        }
    }

    /**
     * Close the output stream and record the plaintext length if successful.
     *
     * @param success if true, records the bytes written
     */
    @Override
    public synchronized void writeComplete(boolean success) {
        if (_cos != null) {
            if (success)
                _actualLength = _cos.getWritten();
            try { _cos.close(); } catch (IOException ioe) { /* ignored */ }
            _cos = null;
        }
    }

    /**
     * The plaintext size, known only after a completed read or write, and 0 before.
     *
     * @return the plaintext length in bytes
     */
    @Override
    public int getLength() {
        return (int) _actualLength;
    }

    /**
     * Is this file one of ours, rather than a plain gzip file left by an earlier
     * release? Reads the first four bytes only.
     *
     * @param file the file to test
     * @return true if the file starts with the sealed-file magic
     */
    public static boolean isSealed(File file) {
        if (file == null || !file.canRead())
            return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[MAGIC.length];
            readFully(in, magic);
            return Arrays.equals(magic, MAGIC);
        } catch (IOException ioe) {
            return false;
        }
    }

    /**
     * New random salt for an account, to be stored beside its cache.
     *
     * @return {@link #SALT_LENGTH} bytes
     */
    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * The cache key for a mailbox password.
     *
     * The password is what the user types at login and is not kept in the config
     * directory, so a backup or a forensic image of that directory holds the salt and
     * the ciphertext, but not the secret the ciphertext needs.
     *
     * @param password the mailbox password, non-null and not empty
     * @param salt the per-account salt, {@link #SALT_LENGTH} bytes
     * @return an AES-256 key
     * @throws IOException if the key cannot be derived
     */
    public static SecretKey deriveKey(String password, byte[] salt) throws IOException {
        if (password == null || password.isEmpty())
            throw new IllegalArgumentException("no password to derive a cache key from");
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ROUNDS, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } catch (GeneralSecurityException gse) {
            throw new IOException("Could not derive the cache key: " + gse, gse);
        }
    }

    @Override
    public String toString() {
        return "SEALED " + _file;
    }
}
