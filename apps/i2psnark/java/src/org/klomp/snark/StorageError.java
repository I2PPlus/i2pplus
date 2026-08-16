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
 * classic java.io failures (RandomAccessFile paths surface as plain
 * IOException) and for errno cases with no typed exception (ENOSPC, EIO,
 * EMFILE, EDQUOT, ...).
 *
 * <p>Each constant carries the human-readable label used in console and
 * log messages, and whether the failure is fatal for the torrent (stop)
 * or transient (drop the request and retry later).
 *
 * @since 0.9.71+
 */
public enum StorageError {

    NO_SPACE("No space left on device", true),
    PERMISSION("Permission denied", true),
    READ_ONLY("Read-only file system", true),
    QUOTA("Disk quota exceeded", true),
    IO_ERROR("Input/output error", true),
    IS_DIRECTORY("Path is a directory", true),
    NAME_TOO_LONG("File name too long", true),
    /** transient: fd exhaustion, retry */
    TOO_MANY_OPEN("Too many open files", false),
    /** transient: stale or read-only handle, retry */
    STALE_HANDLE("Stale or invalid file handle", false),
    /** transient: file vanished, retry */
    MISSING("File or directory missing", false),
    /** unclassified; the raw exception text is preserved by describe() */
    OTHER("I/O error", false);

    private final String label;
    private final boolean fatal;

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
     */
    public boolean isFatal() {
        return fatal;
    }

    /**
     * Classify a storage failure.  Typed NIO exceptions map directly;
     * otherwise the errno text is matched case-insensitively, using the
     * JVM's canonical English messages for Unix and Windows.
     *
     * @param ioe the storage error
     * @return the classified error, never null
     * @since 0.9.71+
     */
    public static StorageError classify(IOException ioe) {
        if (ioe instanceof FileNotFoundException || ioe instanceof NoSuchFileException) {
            return MISSING;
        }
        if (ioe instanceof AccessDeniedException) {
            return PERMISSION;
        }
        if (ioe instanceof NotDirectoryException) {
            return IS_DIRECTORY;
        }
        String msg = ioe.getMessage();
        if (ioe instanceof FileSystemException) {
            // the reason is the errno text without the path prefix, so a
            // path that happens to contain an errno phrase cannot mislead
            String reason = ((FileSystemException) ioe).getReason();
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
     * @return the description
     * @since 0.9.71+
     */
    public static String describe(IOException ioe) {
        StorageError err = classify(ioe);
        if (err != OTHER) {
            return err.toString();
        }
        String msg = ioe.getMessage();
        if (msg != null) {
            return "I/O error: " + msg;
        }
        return "I/O error";
    }
}
