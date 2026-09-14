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
 * <p>This class is used by the installer in Windows to process the <code>wrapper.config</code> file. It
 * <ul>
 * <li>corrects the paths, rewriting <code>/</code> to <code>\</code></li>
 * </ul>
 * <p>
 * Usage: <code>FixWinPaths [WrapperConfigFile]</code>
 * @since 0.9.5
 */
public class FixWinPaths{
    /**
     *  Rewrite the wrapper.conf paths for Windows: convert forward slashes
     *  and relative paths to backslash absolute paths.
     *  @param args the wrapper config file, or nothing for the default
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
    private static void replace(String file) {
        if (!file.contains("wrapper.config")) {return;} //  Shouldn't be true
        String wConf = file;
        String wConfTemp = wConf + ".tmp";

        BufferedReader br = null;
        BufferedWriter bw = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(wConf), "UTF-8"));
            bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(wConfTemp), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
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
        } catch (IOException e) {return;}
        finally {
            try {
                if (br != null) {br.close();}
            } catch (IOException e) {}
            try {
                if (bw != null) {bw.close();}
            } catch (IOException e) {}
        }
        boolean successful = false;
        File oldFile = new File(wConf);
        File newFile = new File(wConfTemp);
        // Once changes have been made, delete the original wrapper.conf
        successful = oldFile.delete();
        if (successful) {
            // ...and rename temp file's name to wrapper.conf
            successful = newFile.renameTo(oldFile);
            if (!successful) {
                System.err.println("ERROR: Problem processing " + wConf);
                System.exit(1);
            }
        }

    }
}
