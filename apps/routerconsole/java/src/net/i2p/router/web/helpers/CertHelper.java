package net.i2p.router.web.helpers;

import java.io.File;
import java.io.IOException;

import net.i2p.data.DataHelper;
import net.i2p.util.FileUtil;
import net.i2p.util.FileSuffixFilter;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.web.HelperBase;


/**
 *  Dump out our local SSL certs, if any
 *
 *  @since 0.9.23
 */
public class CertHelper extends HelperBase {

    private static final String DIR = "certificates";
    private static final String I2CP = "i2cp/i2cp.local.crt";
    private static final String CONSOLE = "console/console.local.crt";
    private static final String I2PTUNNEL_DIR = "i2ptunnel";
    private static final String SAM_DIR = "sam";
    private static final String EEPSITE_DIR = "eepsite";
    private static final String slash = System.getProperty("file.separator");

    public String getSummary() {
        File dir = new File(_context.getConfigDir(), DIR);
        File configPath = _context.getConfigDir();
        try {
            _out.write("<p class=\"infohelp\">");
            _out.write(_t("Certificates are used to authenticate encrypted services running on the network, provision optional SSL for hosted services and the router console, " +
                          "or provide proof of ownership of a router family."));
            _out.write("</p><h3>");
            _out.write(_t("Local SSL Certificates"));
            _out.write("</h3>\n");
            // console
            output(_t("Router Console") + "<span style=\"float: right;\">" +
                   _t("Location") + ": <span class=\"unbold\">" + configPath + slash + DIR + slash + "console" + slash + "</span></span>", new File(dir, CONSOLE));
            // I2CP
            output(_t("I2CP"), new File(dir, I2CP));

            // i2ptunnel clients
            File tunnelDir = new File(_context.getConfigDir(), I2PTUNNEL_DIR);
            boolean hasTunnels = false;
            File[] tunnels = tunnelDir.listFiles(new FileSuffixFilter("i2ptunnel-", ".local.crt"));
            if (tunnels != null) {
                for (int i = 0; i < tunnels.length; i++) {
                    File f = tunnels[i];
                    String name = f.getName();
                    String b32 = name.substring(10, name.length() - 10);
                    output(_t("I2PTunnel") + ": <span class=\"unbold\">" + b32.substring(0,6) + "&hellip;</span><span style=\"float: right;\">" +
                           _t("Location") + ": <span class=\"unbold\">" + configPath + slash + DIR + slash + "i2ptunnel" + slash + "</span></span>", f);
                    hasTunnels = true;
                }
            }
//            if (!hasTunnels)
//                output(_t("I2PTunnel"), null);

            // SAM
            tunnelDir = new File(dir, SAM_DIR);
            hasTunnels = false;
            tunnels = tunnelDir.listFiles(new FileSuffixFilter("sam-", ".local.crt"));
            if (tunnels != null) {
                for (int i = 0; i < tunnels.length; i++) {
                    File f = tunnels[i];
                    output(_t("SAM") + "<span style=\"float: right;\">" +
                           _t("Location") + ": <span class=\"unbold\">" + configPath + slash + DIR + slash + "sam" + slash + "</span></span>", f);
                    hasTunnels = true;
                }
            }
//            if (!hasTunnels)
//                output(_t("SAM"), null);

            // Eepsite
            tunnelDir = new File(dir, EEPSITE_DIR);
            hasTunnels = false;
            tunnels = tunnelDir.listFiles(new FileSuffixFilter(".crt"));
            if (tunnels != null) {
                for (int i = 0; i < tunnels.length; i++) {
                    File f = tunnels[i];
                    String name = f.getName();
                    output(_t("Website") + ": <span class=\"unbold\">" + name.substring(0, name.length() - 4) + "</span><span style=\"float: right;\">" +
                           _t("Location") + ": <span class=\"unbold\">" + configPath + slash + DIR + slash + "eepsite" + slash + "</span></span>", f);
                    hasTunnels = true;
                }
            }
//            if (!hasTunnels)
//                output(_t("Website"), null);

            // Family
            String family = _context.getProperty(FamilyKeyCrypto.PROP_FAMILY_NAME);
            if (family != null) {
                _out.write("<h3>");
                _out.write(_t("Local Router Family Certificate"));
                _out.write("</h3>\n");
                File f = new File(dir, "family");
                f = new File(f, family + ".crt");
                output(_t("Family") + ": <span class=\"unbold\">" + DataHelper.escapeHTML(family) + "</span><span style=\"float: right;\">" +
                       _t("Location") + ": <span class=\"unbold\">" + configPath + slash + DIR + slash + "family" + slash + "</span></span>", f);
//            } else {
//                _out.write("<p>");
//                _out.write(_t("none"));
//                _out.write("</p>\n");
            }

            // anything else? plugins?

        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }

    /**
     *  @param file may be null
     */
    private void output(String name, File file) throws IOException {
        if (file != null && file.exists()) {
            _out.write("<h4>");
            _out.write(name);
            _out.write("</h4>");
            String cert = FileUtil.readTextFile(file.toString(), -1, true);
            if (cert != null) {
                _out.write("\n<textarea readonly=\"readonly\">\n");
                _out.write(cert);
                _out.write("</textarea>\n");
            } else {
                _out.write("\n<textarea readonly=\"readonly\">\n");
                _out.write("Error: Failed to read certificate.");
                _out.write("</textarea>\n");
            }
//        } else {
//            _out.write("<p>");
//            _out.write(_t("none"));
//            _out.write("</p>\n");
        } else if (name.contains(_t("Console"))) {
            File configPath = _context.getConfigDir();
            _out.write("<h4>");
            _out.write(_t("Router Console"));
            _out.write("</h4>");
            _out.write("<p class=\"infohelp\">");
            _out.write(_t("To enable SSL access for the Router Console, see the commented <code>clients.config</code> file " +
                          "located in your I2P application directory for more information. You will need to edit the " +
                          "RouterConsoleRunner config file located in the directory: ").replace("I2P", "I2P+"));
            _out.write("<code>" + configPath + slash + "clients.config.d" + slash + "</code>.");
            _out.write("</p>\n");
        }
    }
}
