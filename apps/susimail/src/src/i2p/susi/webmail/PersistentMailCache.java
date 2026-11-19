package i2p.susi.webmail;

import i2p.susi.util.Buffer;
import i2p.susi.util.Config;
import i2p.susi.util.FixCRLFOutputStream;
import i2p.susi.util.GzipFileBuffer;
import i2p.susi.util.LogHelper;
import i2p.susi.util.SealedFileBuffer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import javax.crypto.SecretKey;
import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;
import net.i2p.util.ThreadNames;

/**
 * Manages persistent on-disk email cache with custom compressed format.
 *
 * This is a custom format with subdirectories, compressed files, and the encoded
 * UIDL in the file name. A file is gzipped, and where the account has a mailbox
 * password it is also sealed with AES-GCM keyed on that password, so a backup of the
 * config directory does not hand over the mailbox in clear.
 * We store either the headers or the full message.
 * No, it is not Maildir format but we could add Maildir-style
 * status suffixes (e.g. ":2.SR") later.
 *
 * Exporting to a Maildir format would be just ungzipping, and decrypting, each file
 * to a flat directory.
 *
 * This class should only be accessed from {@link MailCache}.
 *
 * @since 0.9.14
*/
class PersistentMailCache {

    /**
     * One lock for each user in the whole JVM, to protect against multiple sessions.
     * One big lock for the whole cache dir, not one for each file or subdir.
     * Never expired.
     * Sure, if we did a maildir format we wouldn't need this.
     */
    private static final ConcurrentHashMap<String, Object> _locks = new ConcurrentHashMap<>();

    /** Lock for the cache state. */
    private final Object _lock;
    /** Directory holding the cached mail. */
    private final File _cacheDir;
    /** Directory for the drafts' attachments. */
    private final File _attachmentDir;
    /** The app context. */
    private final I2PAppContext _context;
    /** Logger for the mail cache. */
    private final Log _log;
    /** True for the drafts folder cache. */
    private final boolean _isDrafts;
    /**
     * Key the cached messages are sealed with, or null to store them the way this
     * tree always did: gzip only.
     */
    private final SecretKey _key;

    /** Name of the mail data directory. */
    private static final String DIR_SUSI = "susimail";
    /** Name of the cache subdirectory. */
    private static final String DIR_CACHE = "cache";
    /** Prefix of the cache file names. */
    private static final String CACHE_PREFIX = "cache-";
    /**
     * Directory beside the cache holding imported .eml files, debug only.
     */
    public static final String DIR_IMPORT = "import"; // Flat with .eml files, debug only for now
    /**
     * Directory holding draft attachment files, created only by the drafts cache.
     */
    public static final String DIR_ATTACHMENTS = "attachments"; // Flat with draft attachment files
    /** Prefix of the per-user directory names. */
    private static final String DIR_PREFIX = "s";
    /** Prefix of the mail file names. */
    private static final String FILE_PREFIX = "mail-";
    /** Suffix of the header-only cache files. */
    private static final String HDR_SUFFIX = ".hdr.txt.gz";
    /** Suffix of the full-message cache files. */
    private static final String FULL_SUFFIX = ".full.txt.gz";
    /** Alphabet of the base64 mail uids. */
    private static final String B64 = Base64.ALPHABET_I2P;
    /** Name of the per-account salt file, written beside the cache directories. */
    private static final String SALT_FILE = "salt";
    /** Suffix of the temporary file a legacy cache file is re-sealed into. */
    private static final String SEALING_SUFFIX = ".sealing";
    /** A legacy file larger than this is left as it is rather than re-sealed. */
    private static final long MAX_SEAL_BYTES = 32L * 1024 * 1024;
    /** Config key that turns cache encryption off, `true` unless set otherwise. */
    private static final String CONFIG_CACHE_ENCRYPT = "cache.encrypt";

    /**
     * Cache-encryption keys, one per account, keyed like the account locks, and
     * holding the password each was derived from so a different password re-derives
     * it instead of reusing a key that cannot open the cache.
     */
    private static final ConcurrentHashMap<String, CacheKey> _keys = new ConcurrentHashMap<>();

    /**
     * A derived cache key with the password it came from.
     *
     * @since 0.9.72+
     */
    private static final class CacheKey {
        /** The mailbox password the key was derived from. */
        private final String _pass;
        /** The derived key. */
        private final SecretKey _key;

        /**
         * @param pass the mailbox password the key was derived from
         * @param key the derived key
         */
        CacheKey(String pass, SecretKey key) {
            _pass = pass;
            _key = key;
        }
    }

    /**
     * Use the params to generate a unique directory name.
     *
     * Does NOT load the mails in. Caller MUST call {@link #getMails(String)}.
     *
     * @param ctx supplies the log manager and the base directory
     * @param host IMAP host, part of the directory name
     * @param port IMAP port, part of the directory name
     * @param user mail account name, part of the directory name
     * @param pass the mailbox password, used to derive the key the cache is sealed
     *             with, and otherwise not used; the cache is keyed on account name
     * @param folder on-disk maildir name, one of the {@code DIRS} values,
     *               e.g. {@code DIR_FOLDER}; only DIR_DRAFTS gets an
     *               attachment directory
     * @throws IOException if the cache directory cannot be created
     */
    public PersistentMailCache(I2PAppContext ctx, String host, int port, String user, String pass, String folder) throws IOException {
        _context = ctx;
        _log = ctx.logManager().getLog(PersistentMailCache.class);
        _isDrafts = folder.equals(WebMail.DIR_DRAFTS);
        _lock = getLock(host, port, user);
        synchronized(_lock) {
            _cacheDir = makeCacheDirs(host, port, user, folder);
            _key = cacheKey(_cacheDir.getParentFile(), host, port, user, pass);
            if (_key != null) {
                int sealed = sealLegacy(_cacheDir, _key, _log);
                if (sealed > 0 && _log.shouldInfo())
                    _log.info("Cache files re-sealed -> Messages no longer readable in plaintext\n* Files: " + sealed);
            }
            // Debugging only for now.
            File attach = null;
            if (folder.equals(WebMail.DIR_FOLDER)) {
                importMail();
            } else if (folder.equals(WebMail.DIR_DRAFTS)) {
                attach = new SecureDirectory(_cacheDir, DIR_ATTACHMENTS);
                attach.mkdirs();
            }
            _attachmentDir = attach;
        }
    }

    /**
     * Fetch all mails from disk, naming the folder in the completion log so a
     * reader can tell which mailbox the counts belong to.
     *
     * @param folder label for the log, null if the caller has none to give
     * @return a new collection
     */
    public Collection<Mail> getMails(String folder) {
        synchronized(_lock) {
            return locked_getMails(folder);
        }
    }

    /**
     * Lists the cached mails, lock held.
     *
     * @param folder the folder to list
     * @return the mails found in the cache
     */
    private Collection<Mail> locked_getMails(String folder) {
        Queue<File> fq = new LinkedBlockingQueue<>();
        for (int j = 0; j < B64.length(); j++) {
            File subdir = new File(_cacheDir, DIR_PREFIX + B64.charAt(j));
            File[] files = subdir.listFiles();
            if (files == null) {continue;}
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (!f.isFile()) {continue;}
                fq.offer(f);
            }
        }
        int sz = fq.size();
        if (sz <= 0) {return Collections.emptyList();}

        // thread the read-in
        long begin = _context.clock().now();
        Queue<Mail> rv = new LinkedBlockingQueue<>();
        int tcnt = Math.max(1, Math.min(sz / 4, Math.min(SystemVersion.getCores(), 16)));
        List<Thread> threads = new ArrayList<>(tcnt);
        for (int i = 0; i < tcnt; i++) {
            Thread t = new I2PAppThread(new Loader(fq, rv, _isDrafts, _attachmentDir, _key, _log),
                                        "MailLd" + ThreadNames.idx(i));
            t.start();
            threads.add(t);
        }
        for (int i = 0; i < tcnt; i++) {
            try {threads.get(i).join();}
            catch (InterruptedException ie) {break;}
        }
        long end = _context.clock().now();
        if (_log.shouldDebug()) {
            _log.debug("Loaded the folder from disk in " + DataHelper.formatDuration(end - begin) +
                       " -> Folder: " + folder + " * Messages: " + sz + " * Threads: " + tcnt);
        }
        return rv;
    }

    /**
     * Load files from in, add mail to out.
     * @since 0.9.34
     */
    private static class Loader implements Runnable {
        /** Files being read from the cache. */
        private final Queue<File> _in;
        /** Mails being written to the cache. */
        private final Queue<Mail> _out;
        /** True when reading from the cache. */
        private final boolean _isD;
        /** Where a draft's uploaded attachments live, null when not drafts. */
        private final File _attachDir;
        /** Key a sealed cache file needs, null for a cache that does not encrypt. */
        private final SecretKey _key;
        /** Logger for this loader, handed down from the cache. */
        private final Log _log;

        /**
         * Take the queues to drain and whether the files are drafts, so the
         * resulting Mail objects are built the right way.
         *
         * @param in queue of files to load
         * @param out queue to put loaded mails into
         * @param isDrafts whether these are drafts
         * @param attachDir where a draft's uploaded attachments are kept, or null
         *                  to accept an attachment path as the draft wrote it
         * @param key the key a sealed cache file needs, null for a plaintext cache
         * @param log the cache's log, for reporting a file that cannot be loaded
         */
        public Loader(Queue<File> in, Queue<Mail> out, boolean isDrafts, File attachDir, SecretKey key, Log log) {
            _in = in; _out = out;
            _isD = isDrafts;
            _attachDir = attachDir;
            _key = key;
            _log = log;
        }

        @Override
        /**
         * Execute the task. One unreadable file must not end the pass: the loader
         * thread dying mid-queue leaves every message behind it in that queue
         * unloaded, with nothing to say why the folder is short.
         */
        public void run() {
            File f;
            while ((f = _in.poll()) != null) {
                Mail mail;
                try {
                    mail = load(f, _isD, _attachDir, _key);
                } catch (Throwable t) {
                    // a StackOverflowError or an OutOfMemoryError is an Error, not an
                    // Exception, and load() parses remote MIME structure
                    if (_log.shouldWarn()) {
                        _log.warn("Loading a cached message failed -> Skipping the file" +
                                  "\n* File: " + f.getName(), t);
                    }
                    mail = null;
                }
                if (mail != null) {_out.offer(mail);}
            }
        }
    }

    /**
     * Fetch any needed data from disk, filling in the header, or the body if
     * that is what was asked for and only a header file is on disk.
     *
     * @param mail the mail to populate, matched on its uidl
     * @param headerOnly true to load just the header, false to load the body too
     * @return true if the mail was populated; false if no file exists for the
     *         uidl or it could not be read
     */
    public boolean getMail(Mail mail, boolean headerOnly) {
        synchronized(_lock) {return locked_getMail(mail, headerOnly);}
    }

    /**
     * Loads one cached mail, lock held.
     *
     * @param mail the mail to load
     * @param headerOnly true to load the headers only
     * @return true if the cache had the mail
     */
    private boolean locked_getMail(Mail mail, boolean headerOnly) {
        if (headerOnly) {
            File hf = getHeaderFile(mail.uidl);
            if (hf.exists()) {
                Buffer rb = read(hf, _key);
                if (rb != null) {
                    mail.setHeader(rb);
                    return true;
                }
                if (_log.shouldWarn()) {
                    _log.warn("Header file for " + LogHelper.shortId(mail.uidl) + " could not be read\n* File: " +
                              hf.getName());
                }
            }
            if (_log.shouldDebug()) {
                _log.debug("Header file for " + LogHelper.shortId(mail.uidl) + " not cached\n* File: " + hf.getName());
            }
            return false;
        }
        File f = getFullFile(mail.uidl);
        if (f.exists()) {
            Buffer rb = read(f, _key);
            if (rb != null) {
                mail.setBody(rb);
                // not "true": a cached file this tree cannot parse leaves the mail
                // with no body, and the caller has to know that to leave the server
                // copy alone
                return mail.hasBody();
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Message file for " + LogHelper.shortId(mail.uidl) + " could not be read\n* File: " +
                              f.getName());
                }
            }
        }
        f = getHeaderFile(mail.uidl);
        if (f.exists()) {
            Buffer rb = read(f, _key);
            if (rb != null) {
                mail.setHeader(rb);
                return true;
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Header file for " + LogHelper.shortId(mail.uidl) + " could not be read\n* File: " +
                              f.getName());
                }
            }
        }
        if (_log.shouldDebug()) {
            _log.debug("Message file for " + LogHelper.shortId(mail.uidl) + " not cached\n* File: " + f.getName());
        }
        return false;
    }

    /**
     * Write the mail to disk as a full message when it has a body, otherwise
     * as a header-only file. An existing file is left alone.
     *
     * @param mail the mail to save
     * @return true if the file is on disk, either just written or already
     *         present; false if the mail carries neither body nor header, or
     *         the write failed
     */
    public boolean saveMail(Mail mail) {
        synchronized(_lock) {return locked_saveMail(mail);}
    }

    /**
     * Writes one mail to the cache, lock held.
     *
     * @param mail the mail to save
     * @return true if the mail was written
     */
    private boolean locked_saveMail(Mail mail) {
        Buffer rb = mail.getBody();
        if (rb != null) {
            File f = getFullFile(mail.uidl);
            if (f.exists()) {return true;} // already there, all good
            boolean rv = write(rb, f);
            if (rv) {getHeaderFile(mail.uidl).delete();}
            return rv;
        }
        rb = mail.getHeader();
        if (rb != null) {
            File f = getHeaderFile(mail.uidl);
            if (f.exists()) {return true;} // already there, all good
            boolean rv = write(rb, f);
            return rv;
        }
        return false;
    }

    /**
     * Delete both the full-message and the header-only file for this mail.
     *
     * @param mail the mail to remove
     */
    public void deleteMail(Mail mail) {deleteMail(mail.uidl);}

    /**
     * Delete both the full-message and the header-only file for this uidl.
     *
     * @param uidl the unique ID of the mail to remove
     */
    public void deleteMail(String uidl) {
        synchronized(_lock) {
            getFullFile(uidl).delete();
            getHeaderFile(uidl).delete();
        }
    }

    /**
     * Returns the lock shared by one account.
     *
     * @param host the mail host
     * @param port the mail port
     * @param user the user name
     * @return the lock for that account
     */
    private static Object getLock(String host, int port, String user) {
        Object lock = new Object();
        Object old = _locks.putIfAbsent(user + host + port, lock);
        return (old != null) ? old : lock;
    }

    /**
     * ~/.i2p/susimail/cache/cache-xxxxx/cur/s[b64char]/mail-xxxxx.full.txt.gz
     * folder1 is the base.
     *
     * @param host the mail server host, part of the cache key
     * @param port the mail server port, part of the cache key
     * @param user the user name, part of the cache key
     * @param folder the folder base name, e.g. cur
     * @return the base folder directory, with one subdirectory per base64 character
     * @throws IOException if any of the directories cannot be created
     */
    private File makeCacheDirs(String host, int port, String user, String folder) throws IOException {
        File f = new SecureDirectory(_context.getConfigDir(), DIR_SUSI);
        if (!f.exists() && !f.mkdir()) {throw new IOException("Cannot create " + f);}
        f = new SecureDirectory(f, DIR_CACHE);
        if (!f.exists() && !f.mkdir()) {throw new IOException("Cannot create " + f);}
        f = new SecureDirectory(f, CACHE_PREFIX + Base64.encode(user + host + port));
        if (!f.exists() && !f.mkdir()) {throw new IOException("Cannot create " + f);}
        File base = new SecureDirectory(f, folder);
        if (!base.exists() && !base.mkdir()) {throw new IOException("Cannot create " + base);}
        for (int i = 0; i < B64.length(); i++) {
            f = new SecureDirectory(base, DIR_PREFIX + B64.charAt(i));
            if (!f.exists() && !f.mkdir()) {throw new IOException("Cannot create " + f);}
        }
        return base;
    }

    /**
     * The key this account's cache files are sealed with, or null to leave them as
     * plain gzip.
     *
     * The key is PBKDF2 over the mailbox password the user types at login and a salt
     * written beside the cache, so the config directory holds the ciphertext and the
     * salt but not the secret the ciphertext needs. It is derived once per account and
     * password: the round count that makes a guess expensive would otherwise be paid
     * once per folder on every login.
     *
     * @param accountDir the directory holding this account's folders
     * @param host the mail host, part of the account name
     * @param port the mail port, part of the account name
     * @param user the account name, part of the account name
     * @param pass the mailbox password, null or empty for an account with none
     * @return the key, or null if encryption is switched off or there is no password
     */
    private SecretKey cacheKey(File accountDir, String host, int port, String user, String pass) {
        if (pass == null || pass.isEmpty())
            return null;
        if (!Boolean.parseBoolean(Config.getProperty(CONFIG_CACHE_ENCRYPT, "true")))
            return null;
        String account = user + host + port;
        CacheKey ck = _keys.get(account);
        if (ck != null && pass.equals(ck._pass))
            return ck._key;
        try {
            byte[] salt = readOrCreateSalt(accountDir);
            SecretKey key = SealedFileBuffer.deriveKey(pass, salt);
            _keys.put(account, new CacheKey(pass, key));
            return key;
        } catch (IOException ioe) {
            _log.error("Cache key could not be derived -> Messages stored in plaintext", ioe);
            return null;
        }
    }

    /**
     * The account's PBKDF2 salt, created and stored on first use.
     *
     * @param accountDir the directory holding this account's folders
     * @return {@link SealedFileBuffer#SALT_LENGTH} bytes
     * @throws IOException if the salt file can be neither read nor written
     */
    static byte[] readOrCreateSalt(File accountDir) throws IOException {
        File f = new SecureFile(accountDir, SALT_FILE);
        if (f.canRead()) {
            byte[] raw = Files.readAllBytes(f.toPath());
            byte[] salt = Base64.decode(new String(raw, StandardCharsets.US_ASCII).trim());
            if (salt != null && salt.length == SealedFileBuffer.SALT_LENGTH)
                return salt;
        }
        byte[] salt = SealedFileBuffer.newSalt();
        byte[] text = DataHelper.getASCII(Base64.encode(salt) + '\n');
        try (OutputStream out = new SecureFileOutputStream(f)) {
            out.write(text);
        }
        return salt;
    }

    /**
     * Re-seal the cache files an earlier release left as plain gzip, so upgrading
     * does not leave the mailbox readable.
     *
     * A file that already carries the sealed header is skipped, which is what lets an
     * interrupted pass resume where it stopped instead of sealing a file twice.
     *
     * @param dir the folder directory to walk
     * @param key the key to seal with
     * @param log the cache's log, for a file that could not be re-sealed
     * @return the number of files re-sealed
     */
    static int sealLegacy(File dir, SecretKey key, Log log) {
        File[] entries = dir.listFiles();
        if (entries == null)
            return 0;
        int sealed = 0;
        for (File f : entries) {
            if (f.isDirectory())
                sealed += sealLegacy(f, key, log);
            else if (f.getName().startsWith(FILE_PREFIX) && !SealedFileBuffer.isSealed(f) &&
                     sealOne(f, key, log))
                sealed++;
        }
        return sealed;
    }

    /**
     * Re-seal one legacy file: decompress it, write it back through the sealed
     * buffer under a temporary name, then rename it into place.
     *
     * @param f the plain gzip cache file
     * @param key the key to seal with
     * @param log the cache's log
     * @return true if the file now carries the sealed header
     */
    private static boolean sealOne(File f, SecretKey key, Log log) {
        if (f.length() > MAX_SEAL_BYTES) {
            if (log.shouldWarn())
                log.warn("Legacy cache file left as it is -> Larger than the re-seal limit" +
                         "\n* File: " + f.getName());
            return false;
        }
        File tmp = new SecureFile(f.getParentFile(), f.getName() + SEALING_SUFFIX);
        Buffer legacy = new GzipFileBuffer(f);
        SealedFileBuffer sealed = new SealedFileBuffer(tmp, key, f.getName());
        InputStream in = null;
        OutputStream out = null;
        boolean done = false;
        try {
            in = legacy.getInputStream();
            out = sealed.getOutputStream();
            DataHelper.copy(in, out);
            out.close();
            out = null;
            legacy.readComplete(true);
            sealed.writeComplete(true);
            done = tmp.renameTo(f);
            if (!done && log.shouldWarn())
                log.warn("Legacy cache file left unsealed -> The re-sealed copy could not take its place" +
                         "\n* File: " + f.getName());
        } catch (IOException ioe) {
            if (log.shouldWarn())
                log.warn("Re-sealing a legacy cache file failed -> Left as it is\n* File: " + f.getName(), ioe);
        } finally {
            if (in != null) {
                try {in.close();}
                catch (IOException ioe) { /* ignored */ }
            }
            if (out != null) {
                try {out.close();}
                catch (IOException ioe) { /* ignored */ }
            }
            legacy.readComplete(false);
            sealed.writeComplete(false);
            if (!done)
                tmp.delete();
        }
        return done;
    }

    /**
     * The buffer for a cache file: sealed whenever this cache has a key, except when
     * reading a file an earlier release wrote as plain gzip, which is read the way it
     * was written until the re-seal pass reaches it.
     *
     * @param f the cache file
     * @param writing true when the file is about to be written
     * @return a buffer over the file, never null
     */
    private Buffer bufferFor(File f, boolean writing) {
        if (_key == null)
            return new GzipFileBuffer(f);
        if (writing || !f.exists() || SealedFileBuffer.isSealed(f))
            return new SealedFileBuffer(f, _key);
        return new GzipFileBuffer(f);
    }

    /**
     * The header-only cache file for this uidl. The file need not exist yet.
     *
     * @param uidl the unique ID of the mail
     * @return the header-only file for this UIDL
     */
    public File getHeaderFile(String uidl) {return getFile(uidl, HDR_SUFFIX);}

    /**
     * The gzipped full-message file for this uidl. The file need not exist yet.
     *
     * @param uidl the unique ID of the mail
     * @return the full message file for this UIDL
     */
    public File getFullFile(String uidl) {return getFile(uidl, FULL_SUFFIX);}

    /**
     * For reading or writing a new full mail (NOT headers only).
     * For writing, caller MUST call writeComplete() on rv.
     * Does not necessarily exist.
     *
     * @param uidl the unique ID of the mail
     * @return a new buffer over the full message file, sealed if this cache encrypts
     * @since 0.9.35
     */
    public Buffer getFullBuffer(String uidl) {return bufferFor(getFile(uidl, FULL_SUFFIX), false);}

    /**
     * Where draft attachments are stored, which only the drafts cache creates.
     *
     * @return non-null only for Drafts
     * @since 0.9.35
     */
    public File getAttachmentDir() {return _attachmentDir;}

    /**
     * Builds the cache file name for a uid.
     *
     * @param uidl the mail uid
     * @param suffix the file suffix to use
     * @return the file to use
     */
    private File getFile(String uidl, String suffix) {
        byte[] raw = DataHelper.getASCII(uidl);
        byte[] md5 = PasswordManager.md5Sum(raw);
        String db64 = Base64.encode(md5);
        File dir = new File(_cacheDir, DIR_PREFIX + db64.charAt(0));
        String b64 = Base64.encode(uidl);
        return new SecureFile(dir, FILE_PREFIX + b64 + suffix);
    }

    /**
     * Save data to disk.
     *
     * @param rb the in-memory buffer whose data is written to disk
     * @param f the cache file to write
     * @return true if the buffer was written in full; false on an IOException,
     *         which also leaves the buffer marked incomplete
     */
    private boolean write(Buffer rb, File f) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = rb.getInputStream();
            Buffer gb = bufferFor(f, true);
            out = gb.getOutputStream();
            DataHelper.copy(in, out);
            out.close();
            out = null;
            rb.readComplete(true);
            return true;
        } catch (IOException ioe) {
            _log.error("Writing the message to the cache failed for " + f.getName() + "\n* Error: " + ioe);
            rb.readComplete(false);
            return false;
        } finally {
            if (in != null) {
                try {in.close();}
                catch (IOException ioe) { /* ignored */ }
            }
            if (out != null) {
                try {out.close();}
                catch (IOException ioe) { /* ignored */ }
            }
        }
    }

    /**
     * Wrap a cache file in a buffer that decompresses it, and decrypts it first when
     * the file carries the sealed header.
     *
     * @param f the cache file to read
     * @param key the key a sealed file needs, null for a cache that does not encrypt
     * @return null on failure
     */
    private static Buffer read(File f, SecretKey key) {
        if (!f.canRead()) {return null;}
        if (key != null && SealedFileBuffer.isSealed(f))
            return new SealedFileBuffer(f, key);
        return new GzipFileBuffer(f);
    }

    /**
     * This is for the initial load only.
     * Others will use {@link #getMail(Mail, boolean)}.
     *
     * @param f the cache file to parse, in full or header form
     * @param isDrafts true if the file is in the drafts folder
     * @param attachDir where a draft's uploaded attachments are kept, or null to
     *                  accept an attachment path as the draft wrote it
     * @param key the key a sealed cache file needs, null for a plaintext cache
     * @return null on failure
     */
    private static Mail load(File f, boolean isDrafts, File attachDir, SecretKey key) {
        String name = f.getName();
        String uidl;
        boolean headerOnly;
        if (name.endsWith(FULL_SUFFIX)) {
            uidl= Base64.decodeToString(name.substring(FILE_PREFIX.length(), name.length() - FULL_SUFFIX.length()));
            headerOnly = false;
        } else if (name.endsWith(HDR_SUFFIX)) {
            uidl= Base64.decodeToString(name.substring(FILE_PREFIX.length(), name.length() - HDR_SUFFIX.length()));
            headerOnly = true;
        } else {return null;}
        if (uidl == null) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(PersistentMailCache.class);
            if (log.shouldWarn()) {log.warn("Unable to extract UIDL from file " + f);}
            return null;
        }
        Buffer rb = read(f, key);
        if (rb == null) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(PersistentMailCache.class);
            if (log.shouldWarn()) {log.warn("Unable to read file " + f);}
            return null;
        }
        Mail mail;
        if (isDrafts) {mail = new Draft(uidl, attachDir);}
        else {mail = new Mail(uidl);}
        if (headerOnly) {mail.setHeader(rb);}
        else if (isDrafts) {
            // drafts always have FULL_SUFFIX but may not actually have a real body or part.
            // If we don't call setBody(), it has a null part and we NPE on the compose page.
            // Attachments are stored in separate files so these are all small.
            mail.setBody(rb);
        } else {
            // Deferred loading, body will be loaded on-demand in MailCache.getMail()
            // We set the size of the gzipped file to be the size so the UI doesn't have ?? in it.
            // The size will be corrected if and when the body is read.
            mail.setHeader(rb);
            mail.setSize(f.length());
        }
        return mail;
    }

    /**
     * For debugging. Import .eml files from the import/ directory.
     * @since 0.9.34
     */
    private void importMail() {
        File importDir = new File(_cacheDir.getParentFile(), DIR_IMPORT);
        if (importDir.exists() && importDir.isDirectory()) {
            File[] files = importDir.listFiles(new FileSuffixFilter(".eml"));
            if (files == null) {return;}
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                // Read in the headers to get the X-UIDL that Thunderbird stuck in there
                String uidl = Long.toString(_context.random().nextLong());
                InputStream in = null;
                try {
                    in = new FileInputStream(f);
                    for (int j = 0; j < 20; j++) {
                        String line = DataHelper.readLine(in);
                        if (line.length() < 2) {break;}
                        if (line.startsWith("X-UIDL:")) {
                            uidl = line.substring(7).trim();
                            break;
                        }
                    }
                } catch (IOException ioe) {
                    _log.error("Reading the message to import failed -> File: " + f.getName(), ioe);
                    continue;
                } finally {
                    if (in != null) {
                        try {in.close();}
                        catch (IOException ioe) { /* ignored */ }
                    }
                }
                if (uidl == null)
                    uidl = Long.toString(_context.random().nextLong());
                File to = getFullFile(uidl);
                if (to.exists()) {
                    if (_log.shouldDebug()) {
                        _log.debug("Import for " + LogHelper.shortId(uidl) + " already cached -> Discarded\n* File: " +
                                   f.getName());
                    }
                    f.delete();
                    continue;
                }
                in = null;
                OutputStream out = null;
                try {
                    in = new FileInputStream(f);
                    Buffer gb = bufferFor(to, true);
                    // Thunderbird exports aren't CRLF terminated
                    out = new FixCRLFOutputStream(gb.getOutputStream());
                    DataHelper.copy(in, out);
                } catch (IOException ioe) {
                    _log.error("Writing the imported message to the cache failed -> File: " + f.getName(), ioe);
                    continue;
                } finally {
                    if (in != null) {
                        try {in.close();}
                        catch (IOException ioe) { /* ignored */ }
                    }
                    if (out != null) {
                        try {out.close();}
                        catch (IOException ioe) { /* ignored */ }
                    }
                }
                f.delete();
                if (_log.shouldDebug()) {
                    _log.debug("Imported " + LogHelper.shortId(uidl) + " into the cache\n* File: " + f.getName());
                }
            }
        }
    }
}
