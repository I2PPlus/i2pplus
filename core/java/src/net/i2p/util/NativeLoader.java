package net.i2p.util;

import java.io.File;
import java.util.Locale;

/**
 * Loads a native library out of a file the router wrote itself, tolerating the
 * file lock Windows antivirus puts on it.
 *
 * Both {@link NativeBigInteger} and freenet's CPUID pull their JNI library out
 * of a jar, write it to the temp dir and load it from there. Windows Defender
 * opens a file the moment it appears to scan it, so the load can fail with a
 * sharing violation on a library that is perfectly valid for this JVM. The lock
 * is released as soon as the scan finishes, so retrying after a short wait wins
 * it. Nothing else is retried: a wrong-architecture or corrupt library fails
 * the same way every time, and the caller has other libraries to try.
 *
 * @since 0.9.72+
 */
public class NativeLoader {

    /**
     * Milliseconds to wait before the 1st, 2nd and 3rd retry of a load that hit
     * a file lock. A Defender scan of a 100 KB dll is far under a second, so
     * this only waits long enough for a slow scan, and never longer than 1.7s
     * total on a router that is starting up anyway.
     */
    private static final long[] LOCK_BACKOFF_MS = {200, 500, 1000};

    private NativeLoader() {}

    /**
     * Load the given native library, retrying while the failure is only that
     * another process still has the file open.
     *
     * @param libFile the library to load, need not exist - a missing file is
     *                reported through the error like any other failure
     * @throws UnsatisfiedLinkError if the library was never loaded, with the
     *                              error from the last attempt
     * @since 0.9.72+
     */
    public static void load(File libFile) {
        String path = libFile.getAbsolutePath();
        UnsatisfiedLinkError last = null;
        for (int i = 0; i <= LOCK_BACKOFF_MS.length; i++) {
            try {
                System.load(path);
                return;
            } catch (UnsatisfiedLinkError ule) {
                last = ule;
                if (i == LOCK_BACKOFF_MS.length || !isFileLockError(ule.getMessage())) {
                    break;
                }
                try {
                    Thread.sleep(LOCK_BACKOFF_MS[i]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    /**
     * Is this the Windows sharing-violation text that a retry can get past?
     *
     * Only the English wording is matched, so a localized Windows keeps the
     * behaviour it has today - fail on the first attempt - rather than gaining
     * a retry it cannot detect.
     *
     * @param msg the UnsatisfiedLinkError message, null for no message
     * @return true if waiting and loading again is worth trying
     * @since 0.9.72+
     */
    static boolean isFileLockError(String msg) {
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("being used by another process") ||
               lower.contains("sharing violation") ||
               lower.contains("cannot access the file");
    }
}
