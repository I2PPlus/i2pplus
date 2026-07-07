package net.i2p.router.tasks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.util.FileUtil;
import net.i2p.util.SystemVersion;

/**
 * Automatic I2P router update installer and processor.
 *
 * This class handles the complete update process when an i2pupdate.zip
 * file is present in the router or base directory. It performs a
 * comprehensive update including verification, extraction, cleanup,
 * and restart procedures.
 *
 * <p><strong>Update Process:</strong></p>
 * <ul>
 *   <li>Searches for i2pupdate.zip in router dir or base dir</li>
 *   <li>Verifies write permissions to target directory</li>
 *   <li>Validates ZIP file integrity and structure</li>
 *   <li>Updates router configuration with version information</li>
 *   <li>Extracts update files to base directory</li>
 *   <li>Processes deletelist.txt for obsolete file removal</li>
 *   <li>Cleans up native libraries (jbigi, jcpuid)</li>
 *   <li>Removes update file and restarts JVM</li>
 * </ul>
 *
 * <p><strong>Error Handling:</strong></p>
 * <ul>
 *   <li>Renames failed updates to "BAD-i2pupdate.zip"</li>
 *   <li>Summarizes file deletions at end of process</li>
 *   <li>Handles permission issues gracefully</li>
 *   <li>Performs cleanup even on partial failures</li>
 * </ul>
 *
 * <p><strong>Restart Behavior:</strong></p>
 * <ul>
 *   <li>With wrapper: Automatic restart after update</li>
 *   <li>Without wrapper: Manual restart required</li>
 *   <li>Exit code: Router.EXIT_HARD_RESTART</li>
 * </ul>
 *
 * <p>If no update file is found, performs routine maintenance
 * including native library cleanup and deletion list processing.</p>
 *
 * @since 0.9.20 moved from Router.java
 */
public class InstallUpdate {

    private InstallUpdate() {}

    private static final String DELETE_FILE = "deletelist.txt";

    /**
     * Install updates from i2pupdate.zip file if present.
     *
     * @param r the router instance, must have a valid context
     * @since 0.9.20 moved from Router.java
     */
    public static void installUpdates(Router r) {
        RouterContext context = r.getContext();
        File updateFile = new File(context.getRouterDir(), Router.UPDATE_FILE);
        boolean exists = updateFile.exists();
        if (!exists) {
            updateFile = new File(context.getBaseDir(), Router.UPDATE_FILE);
            exists = updateFile.exists();
        }
        if (exists) {
            // do a simple permissions test, if it fails leave the file in place and don't restart
            File test = new File(context.getBaseDir(), "history.txt");
            if ((test.exists() && !test.canWrite()) || (!context.getBaseDir().canWrite())) {
                System.out.println("ERROR: No write permissions on " + context.getBaseDir() +
                                   " to extract software update file"); // NOSONAR post-update NCDFE risk
                return;
            }
            System.out.println("INFO: Update file exists: " + updateFile + " -> Installing..."); // NOSONAR post-update NCDFE risk
            // verify the whole thing first
            boolean ok = FileUtil.verifyZip(updateFile);
            if (ok) {
                Map<String, String> config = new HashMap<>(4);
                config.put("router.updateLastInstalled", "" + System.currentTimeMillis());
                config.put("router.previousVersion", RouterVersion.VERSION);
                config.put("router.previousFullVersion", RouterVersion.FULL_VERSION);
                r.saveConfig(config, null);
                ok = FileUtil.extractZip(updateFile, context.getBaseDir());
            }

            // Very important - we have now trashed our jars.
            // After this point, do not use any new I2P classes, or they will fail to load
            // and we will die with NCDFE.
            try {
                if (ok) {
                    // We do this here so we may delete old jars before we restart
                    deleteListedFiles(context);
                    System.out.println("INFO: Update installed successfully!"); // NOSONAR post-update NCDFE risk
                } else {
                    System.out.println("ERROR: Update failed!"); // NOSONAR post-update NCDFE risk
                    // we can't leave the file in place or we'll continually restart, so rename it
                    File bad = new File(context.getRouterDir(), "BAD-" + Router.UPDATE_FILE);
                    if (updateFile.renameTo(bad)) {
                        System.out.println("INFO: Moved update file to " + bad); // NOSONAR post-update NCDFE risk
                    } else {
                        System.out.println("WARNING: Could not rename update file to " + bad); // NOSONAR post-update NCDFE risk
                    }
                }
                if (ok) {
                    if (!updateFile.delete()) {
                        System.out.println("WARNING: Unable to delete " + updateFile); // NOSONAR post-update NCDFE risk
                        updateFile.deleteOnExit();
                    }
                }
                if (context.hasWrapper())
                    System.out.println("INFO: Restarting after update..."); // NOSONAR post-update NCDFE risk
                else
                    System.out.println("WARNING: Exiting after update (no service manager) -> Restart I2P+ manually!"); // NOSONAR post-update NCDFE risk
            } catch (Throwable t) { // NOSONAR catches NoClassDefFoundError
                // hopefully the update file got deleted or we will loop
            }
            System.exit(Router.EXIT_HARD_RESTART);
        } else {
            deleteJbigiFiles(context);
            deleteListedFiles(context);
        }
    }

    /**
     *  Remove extracted libjbigi.so and libjcpuid.so files if we have a newer jbigi.jar,
     *  so the new ones will be extracted.
     *  We do this after the restart, not after the extract, because it's safer, and
     *  because people may upgrade their jbigi.jar file manually.
     *
     *  Copied from NativeBigInteger, which we can't access here or the
     *  libs will get loaded.
     */
    private static void deleteJbigiFiles(RouterContext context) {
        boolean isX86 = SystemVersion.isX86();
        String osName = System.getProperty("os.name").toLowerCase(Locale.US);
        boolean isWin = SystemVersion.isWindows();
        boolean isMac = SystemVersion.isMac();
        boolean goodOS = isWin || isMac || osName.contains("linux") || osName.contains("freebsd");

        File jbigiJar = new File(context.getLibDir(), "jbigi.jar");
        if (goodOS && jbigiJar.exists()) {
            String libPrefix = (isWin ? "" : "lib");
            String libSuffix;
            if (isWin) {
                libSuffix = ".dll";
            } else if (isMac) {
                libSuffix = ".jnilib";
            } else {
                libSuffix = ".so";
            }

            if (isX86) {
                deleteNativeLib(jbigiJar, new File(context.getBaseDir(), libPrefix + "jcpuid" + libSuffix));
            }
            if (isX86 || SystemVersion.isARM()) {
                deleteNativeLib(jbigiJar, new File(context.getBaseDir(), libPrefix + "jbigi" + libSuffix));
            }
        }
    }

    /**
     *  Delete a native library if the jbigi.jar is newer.
     *  Copies to .bak first, then deletes.
     */
    private static void deleteNativeLib(File jbigiJar, File lib) {
        if (lib.canWrite() && jbigiJar.lastModified() > lib.lastModified()) {
            String path = lib.getAbsolutePath();
            if (FileUtil.copy(path, path + ".bak", true, true)) {
                if (lib.delete()) {
                    System.out.println("INFO: New jbigi.jar detected -> Moved " + lib.getName() + " to " + path + ".bak"); // NOSONAR post-update
                } else {
                    System.out.println("WARNING: New jbigi.jar detected but could not delete " + lib.getName()); // NOSONAR post-update
                }
            }
        }
    }

    /**
     *  Delete all files listed in the delete file.
     *  Format: One file name per line, comment lines start with '#'.
     *  All file names must be relative to $I2P, absolute file names not allowed.
     *  Summarizes deletions at the end.
     *  Use no new I2P classes here so it may be called after zip extraction.
     *  @since 0.8.12
     */
    private static void deleteListedFiles(RouterContext context) {
        File deleteFile = new File(context.getBaseDir(), DELETE_FILE);
        if (!deleteFile.exists())
            return;
        int deleted = 0;
        int failed = 0;
        // this is similar to FileUtil.readTextFile() but we can't use any I2P classes here
        try (FileInputStream fis = new FileInputStream(deleteFile);
             BufferedReader in = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            String line;
            while ( (line = in.readLine()) != null) {
                String fl = line.trim();
                if (fl.isEmpty() || fl.startsWith("#")) continue;
                if (fl.contains("..")) continue;
                if (fl.startsWith("/") || fl.startsWith("\\")) continue;
                File df = new File(context.getBaseDir(), fl);
                if (!df.exists()) continue;
                boolean ok;
                if (df.isDirectory()) {
                    ok = deleteDir(df);
                } else {
                    ok = df.delete();
                }
                if (ok) {
                    deleted++;
                } else {
                    failed++;
                }
            }
        } catch (IOException ioe) { /* ignored */ }
        deleteFile.delete();
        if (deleted > 0 || failed > 0) {
            StringBuilder sb = new StringBuilder("INFO: Deletion summary: ");
            sb.append(deleted).append(" items deleted");
            if (failed > 0) {
                sb.append(", ").append(failed).append(" FAILED");
            }
            System.out.println(sb.toString()); // NOSONAR post-update NCDFE risk
        }
    }

    /**
     *  Recursive directory delete.
     *  @return true if the directory was deleted
     */
    private static boolean deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDir(f);
                } else {
                    f.delete(); // NOSONAR false positive S899
                }
            }
        }
        return dir.delete();
    }
}
