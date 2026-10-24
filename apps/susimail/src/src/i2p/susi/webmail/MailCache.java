// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.webmail;

import static i2p.susi.webmail.Sorters.*;

import i2p.susi.util.Buffer;
import i2p.susi.util.Config;
import i2p.susi.util.FileBuffer;
import i2p.susi.util.Folder;
import i2p.susi.util.LogHelper;
import i2p.susi.util.MemoryBuffer;
import i2p.susi.webmail.pop3.POP3MailBox;
import i2p.susi.webmail.pop3.POP3MailBox.FetchRequest;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.app.NotificationService;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Caches email data for each folder with POP3 mailbox integration.
 * Only DIR_FOLDER has a non-null POP3MailBox.
 */
class MailCache {

    /**
     * Enumeration of email fetching strategies.
     */
    public enum FetchMode {
        /** Header only. */
        HEADER,
        /** All messages. */
        ALL,
        /** Cache only. */
        CACHE_ONLY,
        /** Header and cache only. */
        HEADER_CACHE_ONLY
    }

    /** The mailbox the messages come from. */
    private final POP3MailBox mailbox;
    /** The cached messages, by id. */
    private final Map<String, Mail> mails;
    /** The on disk copy of the cache. */
    private final PersistentMailCache disk;
    /** The app context. */
    private final I2PAppContext _context;
    /** The folder holding the messages. */
    private final Folder<String> folder;
    /** The name of the folder. */
    private final String folderName;
    /**
     * Serializes draft/sent full-mail writes. @since 0.9.71+.
     */
    private final Object _writeLock = new Object();
    /** Listener of the load currently running. */
    private NewMailListener _loadInProgress;
    /** True once the folder was loaded. */
    private boolean _isLoaded;
    /** True if this cache holds the drafts. */
    private final boolean _isDrafts;
    /** Logger for the mail cache. */
    private final Log _log;

    /**
     * Includes header, headers are generally 1KB to 1.5 KB,
     * and bodies will compress well.
     */
    private static final int FETCH_ALL_SIZE = 32*1024;


    /**
     * Does NOT load the mails in. Caller MUST call {@link #loadFromDisk}.
     *
     * @param ctx the application context supplying the logger and the disk cache
     * @param mailbox non-null for DIR_FOLDER; null otherwise
     * @param folderName the folder this cache holds, one of the WebMail.DIR_ names
     * @param host POP3 host, passed through to the disk cache for reconnects
     * @param port POP3 port, passed through to the disk cache for reconnects
     * @param user POP3 account name, passed through to the disk cache
     * @param pass POP3 password, passed through to the disk cache
     * @throws IOException if the persistent cache directory cannot be created
     */
    MailCache(I2PAppContext ctx, POP3MailBox mailbox, String folderName,
          String host, int port, String user, String pass) throws IOException {
        _log = ctx.logManager().getLog(MailCache.class);
        this.mailbox = mailbox;
        mails = new HashMap<>();
        disk = new PersistentMailCache(ctx, host, port, user, pass, folderName);
        _context = ctx;
        Folder<String> folder = new Folder<>();
        // setElements() sorts, so configure the sorting first
        if (folderName.equals(WebMail.DIR_DRAFTS) || folderName.equals(WebMail.DIR_SENT))
            folder.addSorter(WebMail.SORT_SENDER, new ToSorter(this));
        else
            folder.addSorter(WebMail.SORT_SENDER, new SenderSorter(this));
        folder.addSorter(WebMail.SORT_SUBJECT, new SubjectSorter(this));
        folder.addSorter(WebMail.SORT_DATE, new DateSorter(this));
        folder.addSorter(WebMail.SORT_SIZE, new SizeSorter(this));
        // reverse sort, latest mail first
        // TODO get user defaults from config
        folder.setSortBy(WebMail.SORT_DEFAULT, WebMail.SORT_ORDER_DEFAULT);
        this.folder = folder;
        this.folderName = folderName;
        _isDrafts = folderName.equals(WebMail.DIR_DRAFTS);
    }

    /**
     * The folder key this cache was created under.
     *
     * @return as passed in
     * @since 0.9.35
     */
    public String getFolderName() {
        return folderName;
    }

    /**
     * The folder name after message-bundle translation, "Inbox" for the inbox folder.
     *
     * @return translation of name passed in
     * @since 0.9.35
     */
    public String getTranslatedName() {
        String rv = folderName.equals(WebMail.DIR_FOLDER) ? "Inbox" : folderName;
        return Messages.getString(rv);
    }

    /**
     * The sortable view of the UIDLs held in this cache.
     *
     * @return non-null
     * @since 0.9.35
     */
    public Folder<String> getFolder() {
        return folder;
    }

    /**
     * For writing a new full mail (NOT headers only)
     * Caller must close.
     * Caller must hold the write lock (see {@link #getWriteLock()}) for the whole write.
     * @param uidl the UIDL identifying the mail being written
     * @return the full write buffer
     * @since 0.9.35
     */
    public Buffer getFullWriteBuffer(String uidl) {
        // no locking this way
        return disk.getFullBuffer(uidl);
    }

    /**
     * The monitor callers must hold across a full-mail write.
     *
     * @return the write lock serializing draft and sent folder writes
     * @since 0.9.71+
     */
    public Object getWriteLock() {return _writeLock;}

    /**
     * For writing a new full mail.
     * @param uidl the UIDL identifying the mail that was written
     * @param buffer as received from getFullBuffer
     * @param success whether the write finished without error; on false the mail is not added
     * @since 0.9.35
     */
    public void writeComplete(String uidl, Buffer buffer, boolean success) {
        buffer.writeComplete(success);
        if (success) {
            Mail mail;
            if (_isDrafts)
                mail = new Draft(uidl);
            else
                mail = new Mail(uidl);
            mail.setBody(buffer);
            synchronized(mails) {
                mails.put(uidl, mail);
            }
            folder.addElement(uidl);
        }
    }

    /**
     * Where attachments for a newly composed draft are written.
     *
     * @return non-null only for Drafts
     * @since 0.9.35
     */
    public File getAttachmentDir() {
        return disk.getAttachmentDir();
    }

    /**
     * Move a mail to another MailCache, neither may be DIR_DRAFTS.
     * @param uidl the UIDL of the mail to move
     * @param toMC the cache receiving the mail
     * @return true if the mail was moved, false if either cache holds drafts or the
     * mail has no body or its file is missing
     * @since 0.9.35
     */
    public boolean moveTo(String uidl, MailCache toMC) {
        if (folderName.equals(WebMail.DIR_DRAFTS) ||
            toMC.getFolderName().equals(WebMail.DIR_DRAFTS))
            return false;
        Mail mail;
        synchronized(mails) {
            mail = mails.get(uidl);
            if (mail == null)
                return false;
            if (!mail.hasBody())
                return false;
            File from = disk.getFullFile(uidl);
            if (!from.exists())
                return false;
            PersistentMailCache toPMC = toMC.disk;
            File to = toPMC.getFullFile(uidl);
            if (to.exists())
                return false;
            if (!FileUtil.rename(from, to))
                return false;
            Mail.deleteTempBodyFile(mail.getBody());
            mails.remove(uidl);
            folder.removeElement(uidl);
        }
        toMC.movedTo(mail);
        if (mailbox != null)
            mailbox.queueForDeletion(mail.uidl);
        return true;
    }

    /**
     * Moved a mail from another MailCache.
     *
     * @param mail the mail moved into this cache, its body is re-read from disk
     * @since 0.9.35
     */
    private void movedTo(Mail mail) {
        synchronized(mails) {
            // we must reset the body of the mail to the new FileBuffer
            Buffer body = disk.getFullBuffer(mail.uidl);
            mail.setBody(body);
            mails.put(mail.uidl, mail);
        }
        folder.addElement(mail.uidl);
    }

    /**
     * Is loadFromDisk in progress?
     * @return true if loadFromDisk() is running
     * @since 0.9.35
     */
    public synchronized boolean isLoading() {
        return _loadInProgress != null;
    }

    /**
     * Has loadFromDisk completed?
     * @return true if loadFromDisk() has completed
     * @since 0.9.35
     */
    public synchronized boolean isLoaded() {
        return _isLoaded;
    }

    /**
     * Threaded. Returns immediately.
     * This will not access the mailbox. Mailbox need not be ready.
     *
     * @param nml notified when the background load completes; not called if a load is already in progress or already finished
     * @return success false if in progress already and nml will NOT be called back, true if nml will be called back
     * @since 0.9.13
     */
    public synchronized boolean loadFromDisk(NewMailListener nml) {
        if (_isLoaded || _loadInProgress != null)
            return false;
        Thread t = new I2PAppThread(new LoadMailRunner(nml), "EmailLoader");
        _loadInProgress = nml;
        try {
            t.start();
        } catch (Throwable e) {
            _loadInProgress = null;
            return false;
        }
        return true;
    }

    /**
     * Loads new mail in a background thread.
     *
     * @since 0.9.34
     */
    private class LoadMailRunner implements Runnable {
        /** Listener notified of the new messages. */
        private final NewMailListener _nml;

        /**
         * Creates the loader with its completion listener.
         *
         * @param nml listener to notify after load
         */
        public LoadMailRunner(NewMailListener nml) {
            _nml = nml;
        }

        /**
         * Loads the folder from disk, then reports whether any mail was found.
         */
        @Override
        public void run() {
            boolean result = false;
            try {
                blockingLoadFromDisk();
                if(!mails.isEmpty())
                    result = true;
            } finally {
                synchronized(MailCache.this) {
                    if (_loadInProgress == _nml)
                        _loadInProgress = null;
                    _isLoaded = true;
                }
                _nml.foundNewMail(result);
            }
        }
    }

    /**
     * Blocking. Only call once!
     * This will not access the mailbox. Mailbox need not be ready.
     *
     * @since 0.9.13
     */
    private void blockingLoadFromDisk() {
        synchronized(mails) {
            if (_isLoaded)
                throw new IllegalStateException();
            Collection<Mail> dmails = disk.getMails(folderName);
            for (Mail mail : dmails) {
                mails.put(mail.uidl, mail);
            }
        }
    }

    /**
     * The ones known locally, which will include any known on the server, if connected.
     * Will not include any marked for deletion.
     *
     * This will not access the mailbox. Mailbox need not be ready.
     * loadFromDisk() must have been called first.
     *
     * @return non-null
     * @since 0.9.13
     */
    public String[] getUIDLs() {
        List<String> uidls = new ArrayList<>(mails.size());
        synchronized(mails) {
            for (Mail mail : mails.values()) {
                if (!mail.markForDeletion)
                    uidls.add(mail.uidl);
            }
        }
        return uidls.toArray(new String[uidls.size()]);
    }

    /**
     * Fetch any needed data from pop3 server, unless mode is CACHE_ONLY or HEADER_CACHE_ONLY,
     * or this isn't the Inbox.
     * Blocking for a long time unless mode is CACHE_ONLY or HEADER_CACHE_ONLY.
     *
     * @param uidl message id to get
     * @param mode CACHE_ONLY or HEADER_CACHE_ONLY to not pull from pop server
     * @return An e-mail or null
     */
        @SuppressWarnings({"unchecked", "rawtypes"})
    public Mail getMail(String uidl, FetchMode mode) {
        Mail mail = null;
        Mail newMail = null;

        /*
         * synchronize update to Map
         */
        synchronized(mails) {
            mail = mails.get( uidl );
            if( mail == null ) {
                // if not in inbox, we can't fetch, this is what we have
                if (mailbox == null)
                    return null;
                newMail = new Mail(uidl);
                // TODO really?
                mails.put( uidl, newMail );
            }
        }
        if( mail == null ) {
            mail = newMail;
            mail.setSize(mailbox.getSize(uidl));
        }
        if (mail.markForDeletion)
            return null;

        long sz = mail.getSize();
        if (mode == FetchMode.HEADER && sz > 0 && sz <= FETCH_ALL_SIZE)
            mode = FetchMode.ALL;

        if (mode == FetchMode.HEADER) {
            if (!mail.hasHeader() && mailbox != null) {
                if (_log.shouldInfo()) {
                    _log.info("Fetching the message header from the server for " + LogHelper.shortId(uidl));
                }
                Buffer buf = mailbox.getHeader(uidl);
                if (buf != null)
                    mail.setHeader(buf);
            }
        } else if (mode == FetchMode.HEADER_CACHE_ONLY) {
            if (!mail.hasHeader()) {
                // shouldn't happen
                if (disk.getMail(mail, true)) {
                    if (_log.shouldInfo()) {
                        _log.info("Loaded the deferred header for " + LogHelper.shortId(uidl));
                    }
                    return mail;
                } else {
                    if (_log.shouldDebug()) {
                        _log.debug("Loading the deferred header for [" + LogHelper.head(uidl) + "] failed");
                    }
                }
            }
        } else if (!mail.hasBody()) {
            // CACHE_ONLY or ALL
            if (disk.getFullFile(uidl).exists()) {
                // body was not loaded at startup but we have it, load it now
                if (disk.getMail(mail, false)) {
                    if (_log.shouldDebug()) {
                        _log.debug("Loaded the deferred body for " + LogHelper.shortId(uidl));
                    }
                    return mail;
                }
                if (_log.shouldDebug()) {
                    _log.debug("Loading the deferred body for [" + LogHelper.head(uidl) + "] failed");
                }
            } else {
                if (_log.shouldDebug()) _log.debug("Message body for [" + LogHelper.head(uidl) + "] is not cached");
            }
            if (mode == FetchMode.ALL && mailbox != null) {
                if (_log.shouldInfo()) {
                    _log.info("Fetching the message body from the server for " + LogHelper.shortId(uidl));
                }
                File file = new File(_context.getTempDir(), "susimail-new-" + _context.random().nextLong());
                Buffer rb = mailbox.getBody(uidl, new FileBuffer(file));
                if (rb != null) {
                    mail.setBody(rb);
                    if (disk.saveMail(mail) &&
                        !Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
                        mailbox.queueForDeletion(mail.uidl);
                    }
                }
            }
        }
        return mail;
    }

    /**
     * Fetch any needed data from pop3 server.
     * Mail objects are inserted into the requests.
     * After this, call {@link #getUIDLs()} to get all known mail UIDLs.
     * MUST already be connected, otherwise returns false.
     * Call only on inbox!
     *
     * Blocking.
     *
     * @param mode HEADER or ALL only
     * @return true if any were fetched
     * @since 0.9.13
     */
        @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean getMail(FetchMode mode) {
        if (mode == FetchMode.CACHE_ONLY || mode == FetchMode.HEADER_CACHE_ONLY)
            throw new IllegalArgumentException();
        if (mailbox == null) {
            if (_log.shouldDebug()) {
                _log.debug("Asked for mail with no mailbox set -> Listing not refreshed\n* Folder: " + getFolderName() +
                           "\n* Mode: " + mode);
            }
            return false;
        }
        boolean hOnly = mode == FetchMode.HEADER;

        Collection<String> popKnown = mailbox.getUIDLs();
        if (popKnown == null)
            return false;
        List<POP3Request> fetches = new ArrayList<>();
        //  Fill in the answers from the cache and make a list of
        //  requests.to send off
        for (String uidl : popKnown) {
            Mail mail = null;
            Mail newMail = null;
            boolean headerOnly = hOnly;

            /*
             * synchronize update to Map
             */
            synchronized(mails) {
                mail = mails.get( uidl );
                if( mail == null ) {
                    newMail = new Mail(uidl);
                    mails.put( uidl, newMail );
                }
            }
            if( mail == null ) {
                mail = newMail;
                mail.setSize(mailbox.getSize(uidl));
            }
            if (mail.markForDeletion)
                continue;
            long sz = mail.getSize();
            if (sz > 0 && sz <= FETCH_ALL_SIZE)
                headerOnly = false;
            if( headerOnly ) {
                if(!mail.hasHeader()) {
                    // load the body too if we have it cached and want to free server space
                    boolean loadFull = !Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER)) &&
                                       disk.getFullFile(uidl).exists();
                    if (disk.getMail(mail, !loadFull)) {
                        if (_log.shouldDebug()) {
                            _log.debug("Loaded the header for [" + LogHelper.head(uidl) + "] from the cache");
                        }
                        // note that disk loaded the full body if we asked for it
                        if (mail.hasBody() &&
                            !Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
                            // we already have it, send delete
                            mailbox.queueForDeletion(mail.uidl);
                        }
                        continue;  // found on disk, woo
                    }
                    POP3Request pr = new POP3Request(mail, true, new MemoryBuffer(1024));
                    fetches.add(pr);
                } else {
                    if (mail.hasBody() &&
                        !Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
                        // we already have it, send delete
                        mailbox.queueForDeletion(mail.uidl);
                    }
                }
            } else {
                if(!mail.hasBody()) {
                    if (disk.getMail(mail, false)) {
                        if (_log.shouldDebug()) {
                            _log.debug("Loaded the body for [" + LogHelper.head(uidl) + "] from the cache");
                        }
                        // disk may only have the header file; never delete the
                        // server copy unless we actually have the body
                        if (mail.hasBody() &&
                            !Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
                            // we already have it, send delete
                            mailbox.queueForDeletion(mail.uidl);
                        }
                        continue;  // found on disk, woo
                    }
                    File file = new File(_context.getTempDir(), "susimail-new-" + _context.random().nextLong());
                    POP3Request pr = new POP3Request(mail, false, new FileBuffer(file));
                    fetches.add(pr);
                } else {
                    if (!Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
                        // we already have it, send delete
                        mailbox.queueForDeletion(mail.uidl);
                    }
                }
            }
        }

        boolean rv = false;
        if (!fetches.isEmpty()) {
            //  Send off the fetches
            // gaah compiler
            List foo = fetches;
            List<FetchRequest> bar = foo;
            mailbox.getBodies(bar);
            //  Process results
            int newMail = 0;
            String additionalMsg = null;  // info on first new message
            for (POP3Request pr : fetches) {
                if (pr.getSuccess()) {
                    boolean setAdditional = false;
                    Mail mail = pr.mail;
                    if (!mail.hasHeader()) {
                        mail.setNew(true);
                        if (newMail == 0)
                            setAdditional = true;
                        newMail++;
                    }
                    if (pr.getHeaderOnly()) {
                        mail.setHeader(pr.getBuffer());
                    } else {
                        mail.setBody(pr.getBuffer());
                    }
                    if (setAdditional)
                        additionalMsg = new StringBuilder(mail.sender.length() + mail.subject.length() + 3)
                            .append("\n\n").append(mail.sender).append('\n').append(mail.subject).toString();
                    rv = true;
                    if (disk.saveMail(mail) && mail.hasBody() &&
                        !Boolean.parseBoolean(Config.getProperty(WebMail.CONFIG_LEAVE_ON_SERVER))) {
                        mailbox.queueForDeletion(mail.uidl);
                    }
                }
            }
            if (newMail > 0) {
                // DTG popup
                ClientAppManager cmgr = _context.clientAppManager();
                if (cmgr != null) {
                    NotificationService ns = (NotificationService) cmgr.getRegisteredApp("desktopgui");
                    if (ns != null) {
                        String msg = ngettext("{0} new message", "{0} new messages", newMail);
                        if (newMail == 1 && additionalMsg != null)
                            msg += additionalMsg;
                        ns.notify("SusiMail", null, Log.INFO, _t("Email"), msg, "/susimail/");
                    }
                }
            }
        }
        return rv;
    }

    /**
     * Mark mail for deletion locally.
     * Send delete requests to POP3 then quit and reconnect.
     * No success/failure indication is returned.
     * Does not delete from folder.
     *
     * @param uidl the UIDL of the mail to delete
     * @since 0.9.13
     */
    public void delete(String uidl) {
        delete(Collections.singleton(uidl));
    }

    /**
     * Mark mail for deletion locally.
     * Send delete requests to POP3 then quit and reconnect.
     * No success/failure indication is returned.
     * Does not delete from folder.
     *
     * @param uidls the UIDLs of the mails to delete
     * @since 0.9.13
     */
    public void delete(Collection<String> uidls) {
        List<String> toDelete = new ArrayList<>(uidls.size());
        for (String uidl : uidls) {
            disk.deleteMail(uidl);
            synchronized(mails) {
                Mail mail = mails.get(uidl);
                if (mail == null)
                    continue;
                mail.markForDeletion = true;
                // now replace it with an empty one to save memory
                mail = new Mail(uidl);
                mail.markForDeletion = true;
                mails.put(uidl, mail);
            }
            toDelete.add(uidl);
        }
        if (toDelete.isEmpty())
            return;
        if (mailbox != null)
            mailbox.queueForDeletion(toDelete);
    }

    /**
     * Outgoing to POP3.
     */
    private static class POP3Request implements FetchRequest {
        /**
         * The message this request fetches.
         */
        public final Mail mail;
        /** True to fetch only the message headers. */
        private boolean headerOnly;
        /** True if the load finished without errors. */
        private boolean success;
        /**
         * The buffer the fetched message is written into.
         */
        public final Buffer buf;

        /**
         * Creates a request for the message's headers or full body.
         *
         * @param m the mail to fetch
         * @param hOnly if true, fetch headers only
         * @param buffer the buffer the fetched message is written into
         */
        public POP3Request(Mail m, boolean hOnly, Buffer buffer) {
            mail = m;
            headerOnly = hOnly;
            buf = buffer;
        }

        /**
         * The message's unique ID listing id.
         *
         * @return the unique ID listing id of the mail in this request
         */
        public String getUIDL() {
            return mail.uidl;
        }

        /**
         * Set whether only the headers have been fetched.
         *
         * @since 0.9.34
         */
        public synchronized void setHeaderOnly(boolean headerOnly) {
            this.headerOnly = headerOnly;
        }

        /**
         * Whether only the headers have been fetched.
         *
         * @return true if headers-only
         */
    public synchronized boolean getHeaderOnly() {
            return headerOnly;
        }

        /**
         * Buffer holding the fetched data.
         *
         * @since 0.9.34
         */
        public Buffer getBuffer() {
            return buf;
        }

        /**
         * Record whether the fetch succeeded.
         *
         * @since 0.9.34
         */
        public synchronized void setSuccess(boolean success) {
            this.success = success;
        }

        /**
         * Whether the fetch succeeded.
         *
         * @return true if the fetch finished without errors
         * @since 0.9.34
         */
        public synchronized boolean getSuccess() {
            return success;
        }
    }

    /**
     * Looks up a localized string in the message bundle.
     *
     * @param s the message key
     * @return the localized string
     */
    private static String _t(String s) {
        return Messages.getString(s);
    }

    /**
     * Looks up the localized plural form for n items.
     *
     * @param s the message key of the singular form
     * @param p the message key of the plural form
     * @param n the item count, 1 selects the singular form
     * @return the localized form matching the count
     */
    private static String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p);
    }
}
