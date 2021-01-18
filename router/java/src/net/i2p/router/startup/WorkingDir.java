package net.i2p.router.startup;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Properties;

import net.i2p.data.DataHelper;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 * Get a working directory for i2p.
 *
 * For the location, first try the system property i2p.dir.config
 * Next try $HOME/.i2p on linux or %LOCALAPPDATA%\I2P on Windows.
 *
 * If the dir exists, return it.
 * Otherwise, attempt to create it, and copy files from the base directory.
 * To successfully copy, the base install dir must be the system property i2p.dir.base
 * or else must be in $CWD.
 *
 * If I2P was run from the install directory in the past,
 * and migrateOldData = true, copy the
 * necessary data files (except i2psnark/) over to the new working directory.
 *
 * Otherwise, just copy over a limited number of files over.
 *
 * Do not ever copy or move the old i2psnark/ directory, as if the
 * old and new locations are on different file systems, this could
 * be quite slow.
 *
 * Modify some files while copying, see methods below.
 *
 * After migration, the router will run using the new directory.
 * The wrapper, however, must be stopped and restarted from the new script - until then,
 * it will continue to write to wrapper.log* in the old directory.
 */
public class WorkingDir {

    private final static String PROP_BASE_DIR = "i2p.dir.base";
    private final static String PROP_WORKING_DIR = "i2p.dir.config";
    private final static String WORKING_DIR_DEFAULT_WINDOWS = "I2P";
    private final static String WORKING_DIR_DEFAULT_MAC = "i2p";
    private final static String WORKING_DIR_DEFAULT = ".i2p";
    private final static String WORKING_DIR_DEFAULT_DAEMON = "i2p-config";
    /** we do a couple of things differently if this is the username */
    private static final String PROP_WRAPPER_LOG = "wrapper.logfile";
    private static final String DEFAULT_WRAPPER_LOG = "wrapper.log";
    /** Feb 16 2006 */
    private static final long EEPSITE_TIMESTAMP = 1140048000000l;

    /**
     * Only call this once on router invocation.
     * Caller should store the return value for future reference.
     *
     * This also redirects stdout and stderr to a wrapper.log file if there is no wrapper present,
     * unless system property I2P_DISABLE_OUTPUT_OVERRIDE is set.
     *
     * @param migrateOldConfig whether to copy all data over from an existing install
     */
    public static String getWorkingDir(Properties envProps, boolean migrateOldConfig) {
        String dir = null;
        if (envProps != null)
            dir = envProps.getProperty(PROP_WORKING_DIR);
        if (dir == null)
            dir = System.getProperty(PROP_WORKING_DIR);

        boolean isWindows = SystemVersion.isWindows();
        File dirf = null;
        String gentooWarning = null;
        if (dir != null) {
            dirf = new SecureDirectory(dir);
        } else {
            String home = System.getProperty("user.home");
            if (isWindows) {
                String appdata = System.getenv("LOCALAPPDATA");
                if (appdata != null) {
                    home = appdata;
                }
                // Don't mess with existing Roaming Application Data installs,
                // in case somebody is using roaming appdata for a reason
                // already. In new installs, use local appdata by default. -idk
                appdata = System.getenv("APPDATA");
                if (appdata != null) {
                    File checkOld = new File(appdata, WORKING_DIR_DEFAULT_WINDOWS);
                    if (checkOld.exists() && checkOld.isDirectory()){
                        File routerConfig = new File(checkOld.getAbsolutePath(), "router.config");
                        // The Firefox profile installer was mistakenly using the Roaming application data
                        // which is synced between devices on some Windows machines using MS cloud services,
                        // instead of the local application data which is used by default.
                        // It would create the router.config file in an empty directory, which the router would
                        // then attempt to use, resulting in a router with no client applications. Checking
                        // for clients.config.d determines if the directory is "Real" or not.
                        File clientAppsConfig = new File(checkOld.getAbsolutePath(), "clients.config.d");
                        if (routerConfig.exists() && clientAppsConfig.exists() && clientAppsConfig.isDirectory())
                            home = appdata;
                    }
                }
                dirf = new SecureDirectory(home, WORKING_DIR_DEFAULT_WINDOWS);
            } else if (SystemVersion.isMac()) {
                String appdata = "/Library/Application Support/";
                File old = new File(home,WORKING_DIR_DEFAULT);
                if (old.exists() && old.isDirectory())
                    dirf = new SecureDirectory(home, WORKING_DIR_DEFAULT);
                else {
                    home = home+appdata;
                    dirf = new SecureDirectory(home, WORKING_DIR_DEFAULT_MAC);
                }
            } else {
                if (SystemVersion.isLinuxService()) {
                    if (SystemVersion.isGentoo() &&
                        SystemVersion.GENTOO_USER.equals(System.getProperty("user.name"))) {
                        // whoops, we didn't recognize Gentoo as a service until 0.9.29,
                        // so the config dir was /var/lib/i2p/.i2p through 0.9.28
                        // and changed to /var/lib/i2p/i2p-config in 0.9.29.
                        // Look for both to decide which to use.
                        // We prefer .i2p if neither exists.
                        // We prefer the newer if both exist.
                        File d1 = new SecureDirectory(home, WORKING_DIR_DEFAULT);
                        File d2 = new SecureDirectory(home, WORKING_DIR_DEFAULT_DAEMON);
                        boolean e1 = isSetup(d1);
                        boolean e2 = isSetup(d2);
                        if (e1 && e2) {
                            // d1 is probably older. Switch if it isn't.
                            if (d2.lastModified() < d1.lastModified()) {
                                File tmp = d2;
                                d2 = d1;
                                d1 = tmp;
                                // d1 now is the older one
                            }
                            dirf = d2;
                            gentooWarning = "Warning - Found both an old configuration directory " + d1.getAbsolutePath() +
                                            " and new configuration directory " + d2.getAbsolutePath() +
                                            " created due to a bug in release 0.9.29\n. Using the new configuration" +
                                            " directory. To use the old directory instead, stop i2p," +
                                            " delete the new directory, and restart.";
                        } else if (e1 && !e2) {
                            dirf = d1;
                        } else if (!e1 && e2) {
                            dirf = d2;
                        } else {
                            dirf = d1;
                        }
                    } else {
                        dirf = new SecureDirectory(home, WORKING_DIR_DEFAULT_DAEMON);
                    }
                } else {
                    dirf = new SecureDirectory(home, WORKING_DIR_DEFAULT);
                }
            }
        }

        // where we are now
        String cwd = null;
        if (envProps != null)
            cwd = envProps.getProperty(PROP_BASE_DIR);
        if (cwd == null) {
            cwd = System.getProperty(PROP_BASE_DIR);
            if (cwd == null)
                cwd = System.getProperty("user.dir");
        }

        // Check for a hosts.txt file, if it exists then I2P is there
        File oldDirf = new File(cwd);
        File test = new File(oldDirf, "hosts.txt");
        if (!test.exists()) {
            setupSystemOut(cwd);
            System.err.println("ERROR - Cannot find I2P installation in " + cwd +
                  " - Will probably be just a router with no apps or console at all!");
            // we are probably doomed...
            return cwd;
        }

        // apparently configured for "portable" ?
        try {
            if (oldDirf.getCanonicalPath().equals(dirf.getCanonicalPath())) {
                setupSystemOut(cwd);
                return cwd;
            }
        } catch (IOException ioe) {}

        // where we want to go
        String rv = dirf.getAbsolutePath();
        if (dirf.exists()) {
            if (dirf.isDirectory()) {
                if (isSetup(dirf)) {
                    setupSystemOut(rv);
                    // see above for why
                    if (gentooWarning != null)
                        System.err.println(gentooWarning);
                    return rv; // all is good, we found the user directory
                }
            }
            else {
                setupSystemOut(null);
                System.err.println("Wanted to use " + rv + " for a working directory but it is not a directory");
                return cwd;
            }
        }
        // Check for a router.keys file or logs dir, if either exists it's an old install,
        // and only migrate the data files if told to do so
        // (router.keys could be deleted later by a killkeys())
        test = new File(oldDirf, CreateRouterInfoJob.KEYS_FILENAME);
        boolean oldInstall = test.exists();
        if (!oldInstall) {
            test = new File(oldDirf, "logs");
            oldInstall = test.exists();
        }
        // keep everything where it is, in one place...
        if (oldInstall && !migrateOldConfig) {
            setupSystemOut(cwd);
            return cwd;
        }
        boolean migrateOldData = false; // this is a terrible idea

        if (!dirf.exists() && !dirf.mkdir()) {
            setupSystemOut(null);
            System.err.println("Wanted to use " + rv + " for a working directory but could not create it");
            return cwd;
        }

        setupSystemOut(dirf.getAbsolutePath());
        // Do the copying
        if (migrateOldData)
            System.err.println("Migrating data files to new user directory " + rv);
        else
            System.err.println("Setting up new user directory " + rv);
        boolean success = migrate(MIGRATE_BASE, oldDirf, dirf);
        // this one must be after MIGRATE_BASE
        File oldEep = new File(oldDirf, "eepsite");
        File newEep = new File(dirf, "eepsite");
        String newPath = newEep.getAbsolutePath() + File.separatorChar;
        success &= migrateJettyXml(oldEep, newEep, "jetty.xml", "./eepsite/", newPath);
        success &= migrateJettyXml(oldEep, newEep, "jetty-ssl.xml", "./eepsite/", newPath);
        success &= migrateJettyXml(oldEep, newEep, "contexts/base-context.xml", "./eepsite/", newPath);
        success &= migrateJettyXml(oldEep, newEep, "contexts/cgi-context.xml", "./eepsite/", newPath);
        success &= migrateClientsConfig(oldDirf, dirf);
        // for later news.xml updates (we don't copy initialNews.xml over anymore)
        success &= (new SecureDirectory(dirf, "docs")).mkdir();
        // prevent correlation of eepsite timestamps with router first-seen time
        touchRecursive(new File(dirf, "eepsite/docroot"), EEPSITE_TIMESTAMP);

        // Report success or failure
        if (success) {
            System.err.println("Successfully copied data files to new user directory " + rv);
            return rv;
        } else {
            System.err.println("FAILED copy of some or all data files to new directory " + rv);
            System.err.println("Check logs for details");
            System.err.println("Continung to use data files in old directory " + cwd);
            return cwd;
        }
    }

    /**
     * Tests if <code>dir</code> has been set up as a I2P working directory.<br/>
     * Returns <code>false</code> if a directory is empty, or contains nothing that
     * is usually migrated from the base install.
     * This allows to pre-install plugins before the first router start.
     * @return true if already set up
     */
    private static boolean isSetup(File dir) {
        if (dir.isDirectory()) {
            String[] files = dir.list();
            if (files == null)
                return false;
            String migrated[] = DataHelper.split(MIGRATE_BASE, ",");
            for (String file: files) {
                for (int i = 0; i < migrated.length; i++) {
                    if (file.equals(migrated[i]))
                        return true;
                }
            }
        }
        return false;
    }

    /**
     *  Redirect stdout and stderr to a wrapper.log file if there is no wrapper,
     *  unless system property I2P_DISABLE_OUTPUT_OVERRIDE is set.
     *
     *  If there is no -Dwrapper.log=/path/to/wrapper.log on the java command line
     *  to specify a log file, check for existence of wrapper.log in CWD,
     *  for backward compatibility in old installations (don't move it).
     *  Otherwise, use (system temp dir)/wrapper.log.
     *  Create if it doesn't exist, and append to it if it does.
     *  Put the location in the environment as an absolute path, so logs.jsp can find it.
     *
     *  @param dir if null, use Java temp dir; System property wrapper.logfile overrides
     *  @since 0.8.13
     */
    private static void setupSystemOut(String dir) {
        if (SystemVersion.hasWrapper())
            return;
        if (System.getProperty("I2P_DISABLE_OUTPUT_OVERRIDE") != null)
            return;
        String path = System.getProperty(PROP_WRAPPER_LOG);
        File logfile;
        if (path != null) {
            logfile = new File(path);
        } else {
            logfile = new File(DEFAULT_WRAPPER_LOG);
            if (!logfile.exists()) {
                if (dir == null)
                    dir = System.getProperty("java.io.tmpdir");
                logfile = new File(dir, DEFAULT_WRAPPER_LOG);
            }
        }
        System.setProperty(PROP_WRAPPER_LOG, logfile.getAbsolutePath());
        try {
            PrintStream ps = new PrintStream(new SecureFileOutputStream(logfile, true), true, "UTF-8");
            System.setOut(ps);
            System.setErr(ps);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    /**
     * files and directories from the base install to copy over
     * None of these should be included in i2pupdate.zip
     *
     * The user should not delete these in the old location, leave them as templates for new users
     */
    private static final String MIGRATE_BASE =
        // base install - dirs
        // We don't currently have a default addressbook/ in the base distribution,
        // but distros might put one in
        "addressbook,eepsite," +
        // 0.9.15 support bundled router infos
        "netDb," +
        // base install - files
        // We don't currently have a default router.config, logger.config, susimail.config, or webapps.config in the base distribution,
        // but distros might put one in
        // blocklist.txt now accessed in base dir, user can add another in config dir if desired
        "hosts.txt,i2psnark.config,i2ptunnel.config,jetty-i2psnark.xml," +
        "logger.config,router.config,susimail.config,systray.config,webapps.config";

    private static boolean migrate(String list, File olddir, File todir) {
        boolean rv = true;
        String files[] = DataHelper.split(list, ",");
        for (int i = 0; i < files.length; i++) {
            File from = new File(olddir, files[i]);
            if (!copy(from, todir)) {
                System.err.println("Error copying " + from.getAbsolutePath());
                rv = false;
            }
        }
        return rv;
    }

    /**
     *  Copy over the clients.config file with modifications
     */
    private static boolean migrateClientsConfig(File olddir, File todir) {
        File oldFile = new File(olddir, "clients.config");
        File newFile = new File(todir, "clients.config");
        FileInputStream in = null;
        PrintWriter out = null;
        try {
            in = new FileInputStream(oldFile);
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(newFile), "UTF-8")));
            out.println("# Modified by I2P User dir migration script");
            String s = null;
            boolean isDaemon = SystemVersion.isLinuxService();
            while ((s = DataHelper.readLine(in)) != null) {
                // readLine() doesn't strip \r
                if (s.endsWith("\r"))
                    s = s.substring(0, s.length() - 1);
                if (s.endsWith("=\"eepsite/jetty.xml\"")) {
                    s = s.replace("=\"eepsite/jetty.xml\"", "=\"" + todir.getAbsolutePath() +
                                                            File.separatorChar + "eepsite" +
                                                            File.separatorChar + "jetty.xml\"");
                } else if (isDaemon && s.equals("clientApp.4.startOnLoad=true")) {
                    // disable browser launch for daemon
                    s = "clientApp.4.startOnLoad=false";
                }
                out.println(s);
            }
            System.err.println("Copied " + oldFile + " with modifications");
            if (out.checkError())
                throw new IOException("Failed write to " + newFile);
            return true;
        } catch (IOException ioe) {
            if (in != null) {
                System.err.println("FAILED copy " + oldFile + ": " + ioe);
            }
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) out.close();
        }
    }

    /**
     *  Copy over the jetty.xml file with modifications
     *  It was already copied over once in migrate(), throw that out and
     *  do it again with modifications.
     */
    static boolean migrateJettyXml(File olddir, File todir, String filename, String oldString, String newString) {
        File oldFile = new File(olddir, filename);
        File newFile = new File(todir, filename);
        FileInputStream in = null;
        PrintWriter out = null;
        try {
            in = new FileInputStream(oldFile);
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(newFile), "UTF-8")));
            String s = null;
            while ((s = DataHelper.readLine(in)) != null) {
                // readLine() doesn't strip \r
                if (s.endsWith("\r"))
                    s = s.substring(0, s.length() - 1);
                if (s.indexOf(oldString) >= 0) {
                    s = s.replace(oldString, newString);
                }
                out.println(s);
            }
            out.println("<!-- Modified by I2P User dir migration script -->");
            System.err.println("Copied " + oldFile + " with modifications");
            return true;
        } catch (IOException ioe) {
            if (in != null) {
                System.err.println("FAILED copy " + oldFile + ": " + ioe);
            }
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) out.close();
        }
    }

    /**
     * Recursive copy a file or dir to a dir
     *
     * @param src file or directory, need not exist
     * @param targetDir the directory to copy to, will be created if it doesn't exist
     * @return true for success OR if src does not exist
     */
    private static boolean copy(File src, File targetDir) {
        if (!src.exists())
            return true;
        if (!targetDir.exists()) {
            if (!targetDir.mkdir()) {
                System.err.println("FAILED copy " + src.getPath());
                return false;
            }
            System.err.println("Created " + targetDir.getPath());
        }
        // SecureDirectory is a File so this works for non-directories too
        File targetFile = new SecureDirectory(targetDir, src.getName());
        if (!src.isDirectory())
            return copyFile(src, targetFile);
        File children[] = src.listFiles();
        if (children == null) {
            System.err.println("FAILED copy " + src.getPath());
            return false;
        }
        // make it here so even empty dirs get copied
        if (!targetFile.exists()) {
            if (!targetFile.mkdir()) {
                System.err.println("FAILED copy " + src.getPath());
                return false;
            }
            System.err.println("Created " + targetFile.getPath());
        }
        boolean rv = true;
        for (int i = 0; i < children.length; i++) {
            rv &= copy(children[i], targetFile);
        }
        return rv;
    }

    /**
     * @param src not a directory, must exist
     * @param dst not a directory, will be overwritten if existing, will be mode 600
     * @return true if it was copied successfully
     */
    static boolean copyFile(File src, File dst) {
        if (!src.exists()) return false;
        boolean rv = true;

        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new SecureFileOutputStream(dst);
            DataHelper.copy(in, out);
            System.err.println("Copied " + src.getPath());
        } catch (IOException ioe) {
            System.err.println("FAILED copy " + src.getPath() + ": " + ioe);
            rv = false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
        if (rv)
            dst.setLastModified(src.lastModified());
        return rv;
    }

    /**
     * Recursive touch all files in a dir to a given time
     *
     * @param target the directory or file to touch, must exist
     * @param time the timestamp
     * @since 0.8.13
     */
    private static void touchRecursive(File target, long time) {
        if (!target.exists())
            return;
        if (target.isFile()) {
            target.setLastModified(time);
            return;
        }
        if (!target.isDirectory())
            return;
        File children[] = target.listFiles();
        if (children == null)
            return;
        for (int i = 0; i < children.length; i++) {
            touchRecursive(children[i], time);
        }
    }

}
