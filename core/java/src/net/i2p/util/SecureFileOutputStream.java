package net.i2p.util;

import net.i2p.I2PAppContext;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Same as FileOutputStream but sets the file mode so it can only
 * be read and written by the owner only (i.e. 600 on POSIX).
 * Best effort: on filesystems without POSIX permission support,
 * such as Windows, only the read-only attribute can be affected.
 *
 * @author zzz
 * @since 0.8.1
 */
public class SecureFileOutputStream extends FileOutputStream {

    private static final boolean oneDotSix = SystemVersion.isJava6();

    /**
     * Tries to set output file to mode 600
     * @param file the path to open for writing, truncating any existing file
     * @throws FileNotFoundException if the file cannot be opened for writing
     */
    public SecureFileOutputStream(String file) throws FileNotFoundException {
        super(file);
        setPerms(new File(file));
    }

    /**
     * Tries to set output file to mode 600 whether append = true or false
     * @param file the path to open for writing
     * @param append true to write at the end of an existing file, false to truncate
     * @throws FileNotFoundException if the file cannot be opened for writing
     */
    public SecureFileOutputStream(String file, boolean append) throws FileNotFoundException {
        super(file, append);
        setPerms(new File(file));
    }

    /**
     * Tries to set output file to mode 600
     * @param file the file to open for writing, truncating any existing content
     * @throws FileNotFoundException if the file cannot be opened for writing
     */
    public SecureFileOutputStream(File file) throws FileNotFoundException {
        super(file);
        setPerms(file);
    }

    /**
     * Tries to set output file to mode 600 whether append = true or false
     * @param file the file to open for writing
     * @param append true to write at the end of an existing file, false to truncate
     * @throws FileNotFoundException if the file cannot be opened for writing
     */
    public SecureFileOutputStream(File file, boolean append) throws FileNotFoundException {
        super(file, append);
        setPerms(file);
    }

    /**
     * Whether it is worth trying to tighten the mode of a file this class opens.
     * False on a pre-1.6 JVM, and false when the router is configured with
     * i2p.insecureFiles, since the setting is then left alone.
     *
     * @return true if the permissions should be set, false to skip
     * @since 0.8.2
     */
    static boolean canSetPerms() {
        if (!oneDotSix) return false;
        I2PAppContext ctx = I2PAppContext.getCurrentContext();
        if (ctx == null) return true;
        return !ctx.getBooleanProperty("i2p.insecureFiles");
    }

    /**
     * Tries to set the permissions to 600,
     * ignores errors
     *
     * @param f the file to make readable and writable by the owner only
     */
    public static void setPerms(File f) {
        if (!canSetPerms()) return;
        try {
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
        } catch (Throwable t) {
            // NoSuchMethodException or NoSuchMethodError if we somehow got the
            // version detection wrong or the JVM doesn't support it
        }
    }

    /**
     * Tries to set the permissions to 660 (owner+group rw),
     * ignores errors, including on filesystems without POSIX
     * permission support (e.g. Windows). Uses the PosixFilePermission API.
     *
     * @param f the file to make readable and writable by owner and group
     * @since 0.9.70+
     */
    public static void setGroupPerms(File f) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ,
                                                        PosixFilePermission.OWNER_WRITE,
                                                        PosixFilePermission.GROUP_READ,
                                                        PosixFilePermission.GROUP_WRITE);
            Files.setPosixFilePermissions(f.toPath(), perms);
        } catch (IOException e) {
            // not a POSIX filesystem or other error, ignore
        } catch (UnsupportedOperationException e) {
            // not a POSIX filesystem (e.g. Windows), ignore
        }
    }
}
