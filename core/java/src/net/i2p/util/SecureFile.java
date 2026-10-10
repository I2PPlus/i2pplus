package net.i2p.util;

import java.io.File;
import java.io.IOException;

/**
 * Same as SecureDirectory but sets the file mode after createNewFile()
 * and createTempFile() also. So just use this instead.
 * Probably should have just made this class in the beginning and not had two.
 *
 * @author zzz
 * @since 0.8.2
 */
public class SecureFile extends SecureDirectory {

    /**
     * SecureFile.
     *
     * @param pathname the path of the file to wrap
     */
    public SecureFile(String pathname) {
        super(pathname);
    }

    /**
     * SecureFile.
     *
     * @param parent the path of the containing directory
     * @param child the name of the file within parent
     */
    public SecureFile(String parent, String child) {
        super(parent, child);
    }

    /**
     * SecureFile.
     *
     * @param parent the containing directory
     * @param child the name of the file within parent
     */
    public SecureFile(File parent, String child) {
        super(parent, child);
    }

    /**
     * Tries to set file to mode 600 if the file is created
     */
    @Override
    public boolean createNewFile() throws IOException {
        boolean rv = super.createNewFile();
        if (rv) setPerms();
        return rv;
    }

    /**
     * Tries to set file to mode 600 when the file is created
     *
     * @param prefix the prefix of the generated file name
     * @param suffix the suffix of the generated file name
     * @return the new file, already set to mode 600
     * @throws IOException if the file could not be created
     */
    public static File createTempFile(String prefix, String suffix) throws IOException {
        File rv = File.createTempFile(prefix, suffix);
        // same thing as below but static
        SecureFileOutputStream.setPerms(rv);
        return rv;
    }

    /**
     * Tries to set file to mode 600 when the file is created
     *
     * @param prefix the prefix of the generated file name
     * @param suffix the suffix of the generated file name
     * @param directory the directory to create the file in, or null for the default temp directory
     * @return the new file, already set to mode 600
     * @throws IOException if the file could not be created
     */
    public static File createTempFile(String prefix, String suffix, File directory) throws IOException {
        File rv = File.createTempFile(prefix, suffix, directory);
        // same thing as below but static
        SecureFileOutputStream.setPerms(rv);
        return rv;
    }

    /**
     * Tries to set the permissions to 600,
     * ignores errors
     */
    @Override
    protected void setPerms() {
        if (!SecureFileOutputStream.canSetPerms()) return;
        try {
            setReadable(false, false);
            setReadable(true, true);
            setWritable(false, false);
            setWritable(true, true);
            if (isNotWindows && isDirectory()) {
                setExecutable(false, false);
                setExecutable(true, true);
            }
        } catch (Throwable t) {
            // NoSuchMethodException or NoSuchMethodError if we somehow got the
            // version detection wrong or the JVM doesn't support it
        }
    }
}
