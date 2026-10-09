package net.i2p.installer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * <p>Post-install fixup of the installed <code>wrapper.config</code> on
 * Windows, run by the installer as <code>Main fixwinpaths wrapper.config</code>.</p>
 *
 * The wrapper resolves a path containing a forward slash as a UNIX-style path
 * and silently logs elsewhere, so the forward slashes that the izpack
 * <code>&lt;parsable&gt;</code> substitution leaves in the shipped
 * <code>wrapper.config</code> have to become backslashes. Only the four
 * literal substitutions commented in <code>replace()</code> are applied - the
 * file is not reparsed and paths are not made absolute, because the wrapper
 * already resolves relative paths against its working directory. Other forward
 * slashes in the file are left alone, and are harmless only because the wrapper
 * accepts a mixed-separator path.
 *
 * Usage: <code>FixWinPaths wrapper.config</code>
 *
 * @since 0.9.5
 */
public class FixWinPaths{
    /**
     * Rewrite the forward slashes in the given wrapper.config to backslashes.
     * Does nothing unless os.name starts with "Win".
     * @param args exactly one element, the path of the wrapper.config to fix
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: FixWinPaths [wrapper.config]\r\n");
            System.exit(1);
        }

        // This is only intended for Windows systems
        if (!System.getProperty("os.name").startsWith("Win")) {return;}
        replace(args[0]);

    }
    /**
     * Rewrite wrapper.config in place, via a sibling .tmp file. Exits 1 if the
     * rewritten file cannot be put in place, and returns silently if the name
     * is not a wrapper.config.
     *
     * <p>Windows refuses a rename onto an existing file, so the original has
     * to be removed before the temp file can take its name. That ordering
     * makes a failed delete or rename destructive, so both are reported as
     * failures rather than being allowed to look like success, and the temp
     * file is verified before the original is touched.
     *
     * @param file path of the wrapper.config to rewrite
     */
    private static void replace(String file) {
        // the installer only ever passes the wrapper.config, so anything else
        // means the caller is wrong and this must not be rewritten
        if (!file.contains("wrapper.config")) {return;}
        String wConf = file;
        String wConfTemp = wConf + ".tmp";

        BufferedReader br = null;
        BufferedWriter bw = null;
        boolean wroteTemp = false;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(wConf), "UTF-8"));
            bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(wConfTemp), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                // literal substring substitutions over the whole line, in this
                // order. They are deliberately narrow: a line containing one of
                // these strings anywhere would have it rewritten, so anything
                // added to wrapper.config must avoid all four.
                //   "\i2p/" -> "\i2p\"  separator directly after a directory
                //                          component named "i2p", as in an
                //                          install path ending in \i2p
                //   "lib/"  -> "lib\"    classpath, library path and jarfile
                //   "\/"    -> "\"       doubled separator, left when the install
                //                          path already ends in a backslash; runs
                //                          last so it collapses what the two
                //                          above leave behind
                //   "logs/log-router" -> "logs\log-router"  loggerFilenameOverride
                if (line.contains("\\i2p/"))
                    line = line.replace("\\i2p/", "\\i2p\\");
                if (line.contains("lib/"))
                    line = line.replace("lib/", "lib\\");
                if (line.contains("\\/"))
                    line = line.replace("\\/", "\\");
                if (line.contains("logs/log-router"))
                    line = line.replace("logs/log-router", "logs\\log-router");
                bw.write(line);
                bw.newLine();
            }
            wroteTemp = true;
        } catch (IOException e) {
            // A read or write failure leaves the original untouched, so there
            // is nothing to recover, but it must not read as success: the
            // wrapper would keep logging to the wrong place.
            System.err.println("ERROR: Could not rewrite " + wConf + ": " + e.getMessage());
            System.exit(1);
        }
        finally {
            try {
                if (br != null) {br.close();}
            } catch (IOException e) {}
            try {
                if (bw != null) {bw.close();}
            } catch (IOException e) {}
        }
        if (!wroteTemp || !replaceFromTemp(wConf, wConfTemp)) {
            System.err.println("ERROR: Problem processing " + wConf);
            System.exit(1);
        }
    }

    /**
     * Put a fully written temp file in place of the original.
     *
     * <p>Refuses to remove the original unless the temp file is present and
     * non-empty, so a truncated or missing temp file cannot leave the install
     * with no wrapper.config at all.
     *
     * @param wConf path of the original file
     * @param wConfTemp path of the rewritten sibling
     * @return true if the temp file is now the original
     */
    static boolean replaceFromTemp(String wConf, String wConfTemp) {
        File newFile = new File(wConfTemp);
        // Verify before deleting: the delete is not reversible.
        if (!newFile.isFile() || newFile.length() <= 0) {return false;}
        File oldFile = new File(wConf);
        if (!oldFile.delete()) {
            return false;
        }
        return newFile.renameTo(oldFile);
    }
}
