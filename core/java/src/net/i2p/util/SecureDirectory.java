package net.i2p.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/**
 * Same as File but sets the file mode after mkdir() so it can
 * be read and written by the owner only (i.e. 700 on linux)
 * As of 0.8.2, just use SecureFile instead of this.
 *
 * @author zzz
 * @since 0.8.1
 */
public class SecureDirectory extends File {

    /** Whether the OS is not Windows. */
    protected static final boolean isNotWindows = !SystemVersion.isWindows();

    /**
     * Whether a newly created directory gets group read/write/execute (770)
     * instead of owner-only (700). Set by the constructor overloads that take
     * this flag; a directory is only ever widened at creation time, never
     * after.
     */
    private final boolean _groupPerms;

    /**
     * SecureDirectory, owner-only (700) on creation.
     */
    public SecureDirectory(String pathname) {
        this(pathname, false);
    }

    /**
     * SecureDirectory.
     *
     * @param pathname the path
     * @param groupPerms true to create the directory group-accessible (770)
     *        instead of owner-only (700)
     * @since 0.9.71+
     */
    public SecureDirectory(String pathname, boolean groupPerms) {
        super(pathname);
        _groupPerms = groupPerms;
    }

    /**
     * SecureDirectory.
     */
    public SecureDirectory(String parent, String child) {
        this(parent, child, false);
    }

    /**
     * SecureDirectory.
     *
     * @param parent the parent path
     * @param child the child name
     * @param groupPerms true to create the directory group-accessible (770)
     * @since 0.9.71+
     */
    public SecureDirectory(String parent, String child, boolean groupPerms) {
        super(parent, child);
        _groupPerms = groupPerms;
    }

    /**
     * SecureDirectory.
     */
    public SecureDirectory(File parent, String child) {
        this(parent, child, false);
    }

    /**
     * SecureDirectory.
     *
     * @param parent the parent directory
     * @param child the child name
     * @param groupPerms true to create the directory group-accessible (770)
     * @since 0.9.71+
     */
    public SecureDirectory(File parent, String child, boolean groupPerms) {
        super(parent, child);
        _groupPerms = groupPerms;
    }

    /**
     *  Sets directory mode on creation: 700 by default, or 770 when the
     *  directory was requested group-accessible.
     */
    @Override
    public boolean mkdir() {
        boolean rv = super.mkdir();
        if (rv) setPerms();
        return rv;
    }

    /**
     *  Sets directory mode on creation: 700 by default, or 770 when the
     *  directory was requested group-accessible.
     *  Does NOT change the mode of other created directories.
     */
    @Override
    public boolean mkdirs() {
        boolean rv = super.mkdirs();
        if (rv) setPerms();
        return rv;
    }

    /**
     *  Tries to set the permissions to 700 (or 770 when this directory was
     *  requested group-accessible), ignores errors.
     *
     *  <p>Uses the {@link java.nio.file.attribute.PosixFilePermission} API rather
     *  than {@link File#setReadable}: the {@code File} setters take an
     *  {@code ownerOnly} flag, so there is no way to grant the <i>group</i>
     *  without also granting <i>others</i> — {@code setReadable(true, false)}
     *  yields 777, not 770. {@code SecureFileOutputStream.setGroupPerms()} sets
     *  file permissions the same way for the same reason.
     *
     *  <p>The execute bit matters as much as read here: a group can read a file
     *  inside a directory it cannot traverse, so a group-traversable parent is
     *  what actually makes group-readable log files reachable.
     */
    protected void setPerms() {
        if (!SecureFileOutputStream.canSetPerms() || !isNotWindows) return;
        try {
            EnumSet<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ,
                                                            PosixFilePermission.OWNER_WRITE);
            if (isNotWindows)
                perms.add(PosixFilePermission.OWNER_EXECUTE);
            if (_groupPerms)
                perms.addAll(EnumSet.of(PosixFilePermission.GROUP_READ,
                                        PosixFilePermission.GROUP_WRITE,
                                        PosixFilePermission.GROUP_EXECUTE));
            Files.setPosixFilePermissions(toPath(), perms);
        } catch (Throwable t) {
            // not a POSIX filesystem, or other error; ignore
        }
    }
}
