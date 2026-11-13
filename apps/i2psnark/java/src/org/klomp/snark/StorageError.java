package org.klomp.snark;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.Locale;

/**
 * Classified storage failures.  The JDK maps many errno failures to typed
 * NIO exceptions, so those are matched first without any message parsing;
 * the OS error text carried by the exception message is only a fallback for
 * classic java.io failures, which surface as plain IOException, and for errno
 * cases with no typed exception (ENOSPC, EIO, EMFILE, EDQUOT, ...).
 *
 * <p>Each constant carries the human-readable label used in console and
 * log messages, and whether the failure is fatal for the torrent (stop)
 * or transient (drop the request and retry later).
 *
 * @since 0.9.71+
 */
public enum StorageError {

    /** fatal: ENOSPC, the device has no free blocks left. */
    NO_SPACE("No space left on device", true),
    /** fatal: EACCES or EPERM, the torrent's paths are not writable. */
    PERMISSION("Permission denied", true),
    /** fatal: EROFS, the filesystem holding the torrent is mounted read-only. */
    READ_ONLY("Read-only file system", true),
    /** fatal: EDQUOT, the user's block quota on the filesystem is exhausted. */
    QUOTA("Disk quota exceeded", true),
    /** fatal: EIO, the device reported an unrecoverable error. */
    IO_ERROR("Input/output error", true),
    /** fatal: EISDIR, a path that must name a file is a directory. */
    IS_DIRECTORY("Path is a directory", true),
    /** fatal: ENAMETOOLONG, a path component exceeds the filesystem limit. */
    NAME_TOO_LONG("File name too long", true),
    /** transient: fd exhaustion, retry. */
    TOO_MANY_OPEN("Too many open files", false),
    /** transient: stale or read-only handle, retry. */
    STALE_HANDLE("Stale or invalid file handle", false),
    /** transient: file vanished, retry. */
    MISSING("File or directory missing", false),
    /** Unclassified error; the raw exception text is preserved by {@link #describe}. */
    OTHER("I/O error", false);

    /** Short name shown for this error. */
    private final String label;
    /** True when the error stops the download. */
    private final boolean fatal;

    /**
     * Describes one error condition.
     *
     * @param label the name shown to the user
     * @param fatal whether it stops the download
     */
    StorageError(String label, boolean fatal) {
        this.label = label;
        this.fatal = fatal;
    }

    /**
     * Human-readable label for console and log messages.
     */
    @Override
    public String toString() {
        return label;
    }

    /**
     * Whether a torrent should stop on this failure rather than drop the
     * request and retry.
     *
     * @return true if the torrent must stop, false if the failed request may be
     *         dropped and retried
     */
    public boolean isFatal() {
        return fatal;
    }

    /**
     * How far down an exception's cause chain {@link #classify} and
     * {@link #deepestMessage} look. A storage failure is wrapped once or twice to
     * name the file involved, so the reason sits a link or two below the wrapper.
     * The bound also keeps a cause cycle, two exceptions naming each other as each
     * other's cause, from looping forever.
     */
    private static final int MAX_CAUSE_DEPTH = 8;

    /**
     * Classify a storage failure.  Typed NIO exceptions map directly;
     * otherwise the errno text is matched case-insensitively, using the
     * JVM's canonical English messages for Unix and Windows.
     *
     * <p>The cause chain is searched as well as the exception itself, because the
     * storage layer wraps a write or read failure to name the file it was working
     * on: the wrapper carries the path and the errno text sits on its cause. The
     * first link that classifies wins, so a wrapper that says something specific
     * is still trusted first.
     *
     * @param ioe the storage error
     * @return the classified error, never null
     * @since 0.9.71+
     */
    public static StorageError classify(IOException ioe) {
        Throwable t = ioe;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            StorageError err = classifyOne(t);
            if (err != OTHER) {
                return err;
            }
            t = t.getCause();
        }
        return OTHER;
    }

    /**
     * Classify one link of a cause chain, with no search of its own causes.
     *
     * @param t the exception to classify, any link of the chain
     * @return the classified error, never null
     */
    private static StorageError classifyOne(Throwable t) {
        if (t instanceof FileNotFoundException || t instanceof NoSuchFileException) {
            return MISSING;
        }
        if (t instanceof AccessDeniedException) {
            return PERMISSION;
        }
        if (t instanceof NotDirectoryException) {
            return IS_DIRECTORY;
        }
        String msg = t.getMessage();
        if (t instanceof FileSystemException) {
            // the reason is the errno text without the path prefix, so a
            // path that happens to contain an errno phrase cannot mislead
            String reason = ((FileSystemException) t).getReason();
            if (reason != null) {
                msg = reason;
            }
        }
        if (msg == null) {
            return OTHER;
        }
        String lc = msg.toLowerCase(Locale.US);
        if (lc.contains("no space left on device")) { return NO_SPACE; }
        if (lc.contains("permission denied")) { return PERMISSION; }
        if (lc.contains("read-only file system")) { return READ_ONLY; }
        if (lc.contains("disk quota exceeded")) { return QUOTA; }
        if (lc.contains("input/output error")) { return IO_ERROR; }
        if (lc.contains("too many open files")) { return TOO_MANY_OPEN; }
        if (lc.contains("bad file descriptor")) { return STALE_HANDLE; }
        if (lc.contains("no such file or directory")) { return MISSING; }
        if (lc.contains("is a directory")) { return IS_DIRECTORY; }
        if (lc.contains("file name too long")) { return NAME_TOO_LONG; }
        // Windows canonical texts
        if (lc.contains("access is denied")) { return PERMISSION; }
        if (lc.contains("a required privilege is not held by the client")) { return PERMISSION; }
        if (lc.contains("the system cannot find the file specified")) { return MISSING; }
        if (lc.contains("there is not enough space on the disk")) { return NO_SPACE; }
        if (lc.contains("the device is not ready")) { return IO_ERROR; }
        if (lc.contains("being used by another process")) { return STALE_HANDLE; }
        return OTHER;
    }

    /**
     * Human-readable description of a storage failure, preserving the raw
     * exception text for unclassified errors.
     *
     * @param ioe the storage error
     * @return the classification's own text when any link of the chain could be
     *         matched, otherwise the most specific message in the chain prefixed
     *         with "I/O error", or just that prefix if no link has a message
     * @since 0.9.71+
     */
    public static String describe(IOException ioe) {
        StorageError err = classify(ioe);
        if (err != OTHER) {
            return err.toString();
        }
        String msg = deepestMessage(ioe);
        if (msg != null) {
            return "I/O error: " + msg;
        }
        return "I/O error";
    }

    /**
     * The message of the deepest link of an exception chain that has one. The
     * storage layer wraps a failure to name the file it was working on, so the
     * operating system's own reason usually sits further down than the wrapper's
     * text, and a reader wants that reason rather than the file name.
     *
     * @param t the exception to walk, null allowed
     * @return the most specific message available, or null if no link has one
     * @since 0.9.72+
     */
    static String deepestMessage(Throwable t) {
        String rv = null;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String msg = t.getMessage();
            if (msg != null) {
                rv = msg;
            }
            t = t.getCause();
        }
        return rv;
    }
}
