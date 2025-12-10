package net.i2p.router.tasks;

import java.io.File;
import net.i2p.router.RouterContext;
import net.i2p.util.ShellCommand;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Windows permission fixer for router installations.
 * 
 * This class handles permission issues on Windows platforms where
 * older I2P installations may have set overly broad file permissions.
 * It detects installations that need permission fixes and runs
 * the appropriate scripts to tighten security.
 * 
 * <strong>Platform Specific:</strong>
 * <ul>
 *   <li>Only runs on Windows platforms</li>
 *   <li>Skipped on embedded installations (killVMOnEnd = false)</li>
 *   <li>Requires write permissions to base directory</li>
 * </ul>
 * 
 * The fix is applied once per installation and marked as complete
 * to avoid repeated execution. This is particularly important for
 * installations upgraded from versions prior to 0.9.46.
 * 
 *  @since 0.9.46
 */
public class BasePerms {

    private static final String FIXED_VER = "0.9.46";
    private static final String PROP_FIXED = "router.fixedBasePerms";

    /**
     *  Fix base permissions for Windows installations.
     *  On Windows, this method checks if permissions need to be fixed and runs
     *  the fixperms2.bat script if necessary. This is typically needed after
     *  upgrades from older versions where permissions were set too broadly.
     *
     *  @param ctx the router context for accessing configuration and directories
     *  @since 0.9.46
     */
    public static void fix(RouterContext ctx) {
        if (!SystemVersion.isWindows())
            return;
        if (ctx.getBooleanProperty(PROP_FIXED))
            return;
        if (!ctx.router().getKillVMOnEnd())  // embedded
            return;
        File dir = ctx.getBaseDir();
        File f = new File(dir, "history.txt");
        if (f.exists() && !f.canWrite())     // no permissions, nothing we can do
            return;

        // broad permissions set starting in 0.7.5,
        // but that's before we had the firstVersion property,
        // so no use checking for earlier than that
        String first = ctx.getProperty("router.firstVersion");
        if (first == null || VersionComparator.comp(first, FIXED_VER) < 0) {
            File f1 = new File(dir, "Uninstaller");  // izpack install
            File f2 = new File(dir, "fixperms.log"); // fixperms.bat was run
            if (f1.exists() && f2.exists()) {
                File f3 = new File(dir, "fixperms.bat");
                f3.delete();  // don't need it
                try {
                    fix(dir);
                } catch (Exception e) {
                }
            }
        }
        ctx.router().saveConfig(PROP_FIXED, "true");
    }

    /**
     *  Run the bat file
     */
    private static void fix(File f) {
        File bat = new File(f, "scripts");
        bat = new File(bat, "fixperms2.bat");
        String[] args = { bat.getAbsolutePath(), f.getAbsolutePath() };
        // don't wait, takes appx. 6 seconds on Windows 8 netbook
        (new ShellCommand()).executeSilentAndWaitTimed(args, 0);
    }
}

