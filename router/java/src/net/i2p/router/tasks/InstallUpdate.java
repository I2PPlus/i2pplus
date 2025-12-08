package net.i2p.router.tasks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
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
 *   <li>Provides detailed logging throughout process</li>
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

    private static final String DELETE_FILE = "deletelist.txt";

    /**
     * Install updates from i2pupdate.zip file if present.
     *
     * This method searches for an update file in the router directory or base directory,
     * verifies its integrity, extracts it to the base directory, and performs cleanup operations.
     * If successful, the JVM will exit with a restart code and never return.
     *
     * The method performs the following steps:
     * <ul>
     *   <li>Search for i2pupdate.zip in router dir or base dir</li>
     *   <li>Check write permissions to base directory</li>
     *   <li>Verify ZIP file integrity</li>
     *   <li>Update router configuration with version info</li>
     *   <li>Extract update to base directory</li>
     *   <li>Delete files listed in deletelist.txt</li>
     *   <li>Cleanup update file and restart JVM</li>
     * </ul>
     *
     * If no update file is found, the method performs cleanup of old native libraries
     * and processes any pending file deletions.
     *
     * @param r the router instance, must have a valid context
     * @throws RuntimeException if critical errors occur during update process
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
                                   " to extract software update file");
                // carry on
                return;
            }
            System.out.println("INFO: Update file exists: " + updateFile + " -> Installing...");
            // verify the whole thing first
            // we could remember this fails, and not bother restarting, but who cares...
            boolean ok = FileUtil.verifyZip(updateFile);
            if (ok) {
                // This may be useful someday. First added in 0.8.2
                // Moved above the extract so we don't NCDFE
                Map<String, String> config = new HashMap<String, String>(4);
                config.put("router.updateLastInstalled", "" + System.currentTimeMillis());
                // Set the last version to the current version, since 0.8.13
                config.put("router.previousVersion", RouterVersion.VERSION);
                config.put("router.previousFullVersion", RouterVersion.FULL_VERSION);
                r.saveConfig(config, null);
                ok = FileUtil.extractZip(updateFile, context.getBaseDir());
            }

            // Very important - we have now trashed our jars.
            // After this point, do not use any new I2P classes, or they will fail to load
            // and we will die with NCDFE.
            // Ideally, do not use I2P classes at all, new or not.
            try {
                if (ok) {
                    // We do this here so we may delete old jars before we restart
                    deleteListedFiles(context);
                    System.out.println("INFO: Update installed successfully!");
                } else {
                    System.out.println("ERROR: Update failed!");
                }
                if (!ok) {
                    // we can't leave the file in place or we'll continually restart, so rename it
                    File bad = new File(context.getRouterDir(), "BAD-" + Router.UPDATE_FILE);
                    boolean renamed = updateFile.renameTo(bad);
                    if (renamed) {
                        System.out.println("Moved update file to " + bad.getAbsolutePath());
                    } else {
                        System.out.println("Deleting file " + updateFile.getAbsolutePath());
                        ok = true;  // so it will be deleted
                    }
                }
                if (ok) {
                    boolean deleted = updateFile.delete();
                    if (!deleted) {
                        System.out.println("ERROR: Unable to delete the update file!");
                        updateFile.deleteOnExit();
                    }
                }
                // exit whether ok or not
                if (context.hasWrapper())
                    System.out.println("INFO: Restarting after update...");
                else
                    System.out.println("WARNING: Exiting after update (no service manager) -> Restart I2P+ manually!");
            } catch (Throwable t) {
                // hide the NCDFE
                // hopefully the update file got deleted or we will loop
            }
            System.exit(Router.EXIT_HARD_RESTART);
        } else {
            deleteJbigiFiles(context);
            // It was here starting in 0.8.12 so it could be used the very first time
            // Now moved up so it is usually run only after an update
            // But the first time before jetty 6 it will run here...
            // Here we can't remove jars
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
        // only do this on these OSes
        boolean goodOS = isWin || isMac || osName.contains("linux") || osName.contains("freebsd");

        File jbigiJar = new File(context.getLibDir(), "jbigi.jar");
        if (goodOS && jbigiJar.exists()) {
            String libPrefix = (isWin ? "" : "lib");
            String libSuffix = (isWin ? ".dll" : isMac ? ".jnilib" : ".so");

            if (isX86) {
                File jcpuidLib = new File(context.getBaseDir(), libPrefix + "jcpuid" + libSuffix);
                if (jcpuidLib.canWrite() && jbigiJar.lastModified() > jcpuidLib.lastModified()) {
                    String path = jcpuidLib.getAbsolutePath();
                    boolean success = FileUtil.copy(path, path + ".bak", true, true);
                    if (success) {
                        boolean success2 = jcpuidLib.delete();
                        if (success2) {
                            System.out.println("New jbigi.jar detected -> Moved jcpuid library to " + path + ".bak");
                            System.out.println("Check logs for successful installation of new library");
                        }
                    }
                }
            }

            if (isX86 || SystemVersion.isARM()) {
                File jbigiLib = new File(context.getBaseDir(), libPrefix + "jbigi" + libSuffix);
                if (jbigiLib.canWrite() && jbigiJar.lastModified() > jbigiLib.lastModified()) {
                    String path = jbigiLib.getAbsolutePath();
                    boolean success = FileUtil.copy(path, path + ".bak", true, true);
                    if (success) {
                        boolean success2 = jbigiLib.delete();
                        if (success2) {
                            System.out.println("New jbigi.jar detected -> Moved jbigi library to " + path + ".bak");
                            System.out.println("Check logs for successful installation of new library");
                        }
                    }
                }
            }
        }
    }

    /**
     *  Delete all files listed in the delete file.
     *  Format: One file name per line, comment lines start with '#'.
     *  All file names must be relative to $I2P, absolute file names not allowed.
     *  We probably can't remove old jars this way.
     *  Fails silently.
     *  Use no new I2P classes here so it may be called after zip extraction.
     *  @since 0.8.12
     */
    private static void deleteListedFiles(RouterContext context) {
        File deleteFile = new File(context.getBaseDir(), DELETE_FILE);
        if (!deleteFile.exists())
            return;
        // this is similar to FileUtil.readTextFile() but we can't use any I2P classes here
        FileInputStream fis = null;
        BufferedReader in = null;
        try {
            fis = new FileInputStream(deleteFile);
            in = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            String line;
            while ( (line = in.readLine()) != null) {
                String fl = line.trim();
                if (fl.contains("..") || fl.startsWith("#") || fl.length() == 0)
                    continue;
                File df = new File(fl);
                if (df.isAbsolute())
                    continue;
                df = new File(context.getBaseDir(), fl);
                if (df.exists() && df.isFile()) {
                    if (df.delete())
                        System.out.println("INFO: File [" + fl + "] deleted");
                }
            }
        } catch (IOException ioe) {
        } finally {
            if (in != null) try { in.close(); } catch(IOException ioe) {}
            if (deleteFile.delete()) {
                //System.out.println("INFO: File [" + DELETE_FILE + "] deleted");
            }
        }
    }
}

