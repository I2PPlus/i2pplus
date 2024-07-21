package net.i2p.util;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.i2p.I2PAppContext;
import net.i2p.util.ObjectCounterUnsafe;

/**
 * Output translation stats by loading ResourceBundles from jars and wars,
 * in html or text format.
 *
 * Bundles only, does not support external resources (html files, man pages,
 * Debian po files) or the gettext properties files.
 *
 * This is run at build time, so output is not tagged or translated.
 *
 * @since 0.9.64
 */
public class TranslationStatus {

    private final I2PAppContext _context;
    private final boolean _html;
    private final StringBuilder buf, buf2;
    private final List<String> langs;
    private final Set<Locale> foundLangs;
    private final ObjectCounterUnsafe<Locale> counts;
    private final ObjectCounterUnsafe<Locale> bundles;

    private static final String[] JARS =  { "desktopgui.jar", "i2p.jar",
                                            "i2psnark.war", "i2ptunnel.jar", "i2ptunnel.war",
                                            "mstreaming.jar", "router.jar", "routerconsole.jar",
                                            "susidns.war", "susimail.war" };

    // Java lang codes, see notes below
    private static final String[] LANGS = { "ar", "az", "cs", "da", "de", "el", "es", "et", "fa", "fi", "fr",
                                            "hu", "in", "it", "ja", "ko", "nb", "nl", "pl", "pt", "ro", "ru",
                                            "sl", "sv", "tr", "uk", "vi", "zh" };
/**
    private static final String[] LANGS = { "ar", "az", "bg", "ca", "cs", "da", "de", "el", "es", "es_AR",
                                            "et", "fa", "fi", "fr", "gl", "hi", "hr", "hu", "in", "it", "iw",
                                            "ja", "ko", "ku", "mg", "nb", "nl", "nn", "pl", "pt", "pt_BR",
                                            "ro", "ru", "sk", "sl", "sq", "sr", "sv", "tk", "tr", "uk", "vi",
                                            "zh", "zh_TW" };
**/

    private static final String[] FILES = { "core/java/src/gnu/getopt/MessagesBundle.properties",
                                            "installer/resources/readme/readme.html",               // no country variants supported
                                            "installer/resources/eepsite/docroot/help/index.html",
                                            "installer/resources/locale-man/man.po",                // non-Java
                                            "installer/resources/locale/po/messages.po",            // non-Java
                                            "debian/po/.po" };                                      // non-Java

    public TranslationStatus(I2PAppContext ctx, boolean html) {
        _context = ctx;
        _html = html;
        buf = new StringBuilder(65536);
        buf2 = new StringBuilder(4096);
        langs = Arrays.asList(LANGS);
        counts = new ObjectCounterUnsafe<Locale>();
        bundles = new ObjectCounterUnsafe<Locale>();
        foundLangs = new HashSet<Locale>(64);
    }

/*
   only useful if we bundle this at runtime

    public String getStatus() throws IOException {
        File base = _context.getBaseDir();
        File jars = new File(base, "lib");
        File wars = new File(base, "webapps");
        File[] files = new File[JARS.length];
        for (int i = 0; i < JARS.length; i++) {
            String f = JARS[i];
            files[i] = new File(f.endsWith(".jar") ? jars : wars, f);
        }
        return getStatus(files);
    }
*/

    public String getStatus(File[] files) throws IOException {
        buf.setLength(0);
        buf2.setLength(0);
        List<String> classes = new ArrayList<String>(64);
        int grandtot = 0;
        int resources = 0;

        // pass 1: for each file
        for (int i = 0; i < files.length; i++) {
            // pass 1A: collect the class names in the file
            ZipFile zip = null;
            try {
                zip = new ZipFile(files[i]);
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.contains("messages_") && !name.contains("$")) {
                        //buf.append("found bundle " + name);
                        if (name.startsWith("WEB-INF/classes/"))
                            name = name.substring(16);
                        classes.add(name);
                    }
                }
                Collections.sort(classes);
            } catch (IOException ioe) {
                ioe.printStackTrace();
                continue;
            } finally {
                if (zip != null) try { zip.close(); } catch (IOException e) {}
            }

            if (classes.isEmpty()) {
                System.err.println("No translations found in " + files[i]);
                continue;
            }

            // pass 1B: setup a classloader, load each class
            URL url;
            if (files[i].getName().endsWith(".jar")) {
                url = files[i].toURI().toURL();
            } else if (files[i].getName().endsWith(".war")) {
                try {
                    url = (new URI("jar:file:" + files[i] + "!/WEB-INF/classes/")).toURL();
                } catch (URISyntaxException use) { continue; }
            } else {
                System.err.println("Not a jar/war file: " + files[i]);
                continue;
            }
            URL[] urls = new URL[] { url };
            URLClassLoader cl = new URLClassLoader(urls);

            String pclz = "";
            int max = 0;
            List<ResourceBundle> buns = new ArrayList<ResourceBundle>(64);
            for (String name : classes) {
                name = name.substring(0, name.length() - 6);  // .class
                int c = name.indexOf('_');
                String clz = name.substring(0, c).replace("/", ".");
                if (!clz.equals(pclz)) {
                    // pass 1C: output a table for the resource
                    // output goes here, we have to make two passes to find the max
                    // number of entries to generate a true %
                    if (!buns.isEmpty()) {
                        report(pclz, max, buns);
                        resources++;
                    }
                    grandtot += max;
                    pclz = clz;
                    max = 0;
                    buns.clear();
                }
                String s = name.substring(c + 1);
                String lang;
                String country;
                Locale loc;
                c = s.indexOf("_");
                if (c < 0) {
                    lang = s;
                    country = null;
                    loc = new Locale(lang);
                } else {
                    lang = s.substring(0, c);
                    country = s.substring(c + 1);
                    loc = new Locale(lang, country);
                }
                foundLangs.add(loc);
                ResourceBundle bun;
                try {
                    bun = ResourceBundle.getBundle(clz, loc, cl);
                } catch (Exception e) {
                    System.err.println("FAILED loading class " + clz + " lang " + lang + " country " + country);
                    continue;
                }
                // in this pass we just calculate the max strings
                buns.add(bun);
                Set<String> keys = bun.keySet();
                int tot = keys.size() - 1; // subtract empty header string
                if (tot > max)
                    max = tot;
            }
            if (!buns.isEmpty()) {
                report(pclz, max, buns);
                grandtot += max;
                resources++;
            }
            classes.clear();
        }

        nl();

        // pass 2: resources not in jars/wars
        resources += nonCompiledStatus();
        nl();

        // pass 3: output summary table

        // from here down to buf2 so we can output it first
        String h = "Translation Summary (" + resources + " resources, " + langs.size() + " languages, " + grandtot + " strings)";
        if (_html) {
            buf2.append("<span id=tx_summary>\n")
                .append("<p class=infohelp>Note: Percentage translated is only displayed for compiled resources.</p>\n");
        } else {
            buf2.append(h).append("\n\n% translated includes compiled resources only\n\n");
        }
        if (_html) {
            buf2.append("<table class=tx id=tx_total>\n")
                .append("<tr class=tx_header><th colspan=4>").append(h).append("</th>")
                .append("<tr><th>Language</th><th>Language Code</th><th>Missing Resources</th><th>% Translated</th></tr>\n");
        } else {
            buf2.append("Code\t   %TX\tMissing\tLanguage\n");
            buf2.append("----\t------\t--------\t-------\n");
        }
        List<Locale> sorted = counts.sortedObjects();
        for (Locale loc : sorted) {
            String s = loc.getLanguage();
            String lang = loc.getDisplayLanguage();
            String country = loc.getCountry();
            String cc = getCountryCode(loc);
            String flag = "<span class=langflag><img class=tx_flag src=\"/flags.jsp?c=" + cc + "\" width=24></span>";
            if (country.length() > 0) {
                s += '_' + country;
                country = '(' + loc.getDisplayCountry() + ')';
            }
            if (_html) {
                buf2.append(String.format(Locale.US, "<tr><td>%s %s %s</td><td>%s</td><td>%d</td>" +
                                                     "<td><span class=percentBarOuter title=\"%5.1f%%\">" +
                                                     "<span class=percentBarInner style=\"width:%5.1f%%\"></span></span></td>" +
                                                     "</tr>\n",
                                                     flag, lang, country, s, resources - bundles.count(loc), 100f * counts.count(loc) / grandtot,
                                                     100f * counts.count(loc) / grandtot));
            } else {
                buf2.append(String.format("%s\t%5.1f%%\t%s %s\n", s, 100f * counts.count(loc) / grandtot, resources - bundles.count(loc), lang, country));
            }
        }
        if (_html) {
            buf2.append("</table>\n<hr>\n");
        } else {
            nl();
            nl();
        }
        if (_html)
            buf2.append("<h2>Compiled Resources")
                .append("&nbsp;<span class=script><button id=tx_toggle_compiled>")
                .append("Show Complete Translations</button></span>")
                .append("</h2>\n");
        else
            buf2.append("Compiled Resources\n\n");
        String rv = buf2.toString() + buf.toString();
        buf.setLength(0);
        buf2.setLength(0);
        return rv;
    }

    private void report(String clz, int max, List<ResourceBundle> buns) {
        if (clz.endsWith(".messages")) {clz = clz.substring(0, clz.length() - 9);}
        String classTitle = "";
        String location = "";
        if (clz.contains("snark")) {
            classTitle = "tx_snark";
            location = "apps/i2psnark/locale";
        } else if (clz.contains("i2ptunnel.web")) {
            classTitle = "tx_i2ptunnel";
            location = "apps/i2ptunnel/locale";
        } else if (clz.contains("i2ptunnel.proxy")) {
            classTitle = "tx_proxy";
            location = "apps/i2ptunnel/locale-proxy";
        } else if (clz.contains("router.countries")) {
            classTitle = "tx_netdb";
            location = "apps/routerconsole/locale-countries";
        } else if (clz.contains("router.news")) {
            classTitle = "tx_news";
            location = "apps/routerconsole/locale-news";
        } else if (clz.contains("router.web")) {
            classTitle = "tx_console";
            location = "apps/routerconsole/locale";
        } else if (clz.contains("susi.dns")) {
            classTitle = "tx_dns";
            location = "apps/susidns/locale";
        } else if (clz.contains("webmail")) {
            classTitle = "tx_webmail";
            location = "apps/susimail/locale";
        } else if (clz.contains("router.util")) {
            classTitle = "tx_router";
            location = "router/locale";
        } else if (clz.contains("net.i2p.util")) {
            classTitle = "tx_router";
            location = "core/locale";
        } else if (clz.contains("client.streaming")) {
            classTitle = "tx_streaming";
            location = "apps/ministreaming/locale";
        } else if (clz.contains("desktopgui")) {
            classTitle = "tx_dtg";
            location = "apps/desktopgui/locale";
        }
        if (!_html) {
            buf.append("\n\nTranslations for " + clz + " (" + max + " strings, " + buns.size() + " translations)\n");
            buf.append("Code\t  TX\t   %TX\tLanguage\n");
            buf.append("----\t----\t------\t--------\n");
        } else {
            buf.append("<table class=\"tx tx_compiled\">\n<thead>\n")
               .append("<tr class=\"tx_header ").append(classTitle).append("\">")
               .append("<th colspan=2>").append(clz).append(" (").append(max).append(" strings, ")
               .append(buns.size()).append(" translations)</th>")
               .append("<th colspan=2 class=tx_location><span>").append(location).append("</span></th></tr>\n")
               .append("<tr><th>Language</th><th>Language Code</th><th>Translated</th><th>% Translated</th></tr>\n")
               .append("</thead>\n<tbody>\n");
        }
        Set<String> missing = new TreeSet<String>(langs);
        for (ResourceBundle bun : buns) {
            //int not = 0;
            //int same = 0;
            //int tx = 0;
            Set<String> keys = bun.keySet();
            int tot = keys.size() - 1; // subtract empty header string
         /*
            for (String k : keys) {
                try {
                    String v = bun.getString(k);
                    if (v.length() == 0)
                        not++;
                    else if (v.equals(k))
                        same++;
                    else
                        tx++;
                } catch (MissingResourceException e) {
                    not++;
                }
            }
         */
            Locale loc = bun.getLocale();
            String lang = loc.getLanguage();
            String country = loc.getCountry();
            String cc = getCountryCode(loc);
            String flag = "<span class=langflag><img class=tx_flag src=\"/flags.jsp?c=" + cc + "\" width=24></span>";
            String dlang = loc.getDisplayLanguage();
            if (country.length() > 0) {
                lang += '_' + country;
                country = '(' + loc.getDisplayCountry() + ')';
            }
            missing.remove(lang);
            counts.add(loc, tot);
            bundles.increment(loc);
            boolean incomplete = tot < max;
            String row = incomplete ? "<tr class=incomplete>" : "<tr class=complete>";
            if (_html) {
                //buf.append(String.format(Locale.US, "<tr><td>%s %s<td>%s<td>%4d<td>%4d<td>%4d<td>%5.1f%%\n", dlang, country, lang, tx, same, tot, 100f * (tx + same) / max));
                buf.append(String.format(Locale.US, "%s<td>%s %s %s</td><td>%s</td><td>%4d</td>" +
                                                    "<td><span class=percentBarOuter title=\"%5.1f%%\">" +
                                                    "<span class=percentBarInner style=\"width:%5.1f%%\"></span></span></td></tr>\n",
                                                    row, flag, dlang, country, lang, tot, 100f * tot / max, 100f * tot / max));
            } else {
                //buf.append(String.format("%s\t%4d\t%4d\t%4d\t%5.1f%%\t%s %s\n", lang, tx, same, tot, 100f * (tx + same) / max, dlang, country));
                buf.append(String.format("%s\t%4d\t%5.1f%%\t%s %s\n", lang, tot, 100f * tot / max, dlang, country));
            }
        }
        buf.append("</tbody>");

        if (!missing.isEmpty()) {
            if (_html)
                buf.append("<tfoot><tr><th class=tx_notranslate colspan=4><b>Not translated:</b>\n");
            else
                buf.append("Not translated:\n");
            for (String s : missing) {
                int count = missing.size();
                String lang;
                String country;
                Locale loc;
                int c = s.indexOf("_");
                if (c < 0) {
                    lang = s;
                    country = "";
                    loc = new Locale(lang);
                } else {
                    lang = s.substring(0, c);
                    country = s.substring(c + 1);
                    loc = new Locale(lang, country);
                    country = " (" + loc.getDisplayCountry() + ')';
                }
                String dlang = loc.getDisplayLanguage();
                if (_html) {buf.append(" &bullet; ").append(dlang);}
                else {buf.append(s).append("\t--\t--\t").append(dlang).append(country).append('\n');}
            }
            if (_html) {buf.append("</tfoot>\n");}
        }
        if (_html)
            buf.append("</table>\n<hr>\n");
        else
            nl();
    }

    private int nonCompiledStatus() {
        int rv  = 0;
        if (_html) {
            buf.append("<h2>Other Resources")
               .append("&nbsp;<span class=script><button id=tx_toggle_files>")
               .append("Show Complete Translations</button></span></h2>\n");
        } else {
            buf.append("\nOther Resources\n\n");
        }
        for (String file : FILES) {
            boolean nonJava = file.startsWith("debian/po/") ||
                              file.startsWith("installer/resources/locale-man/") ||
                              file.startsWith("installer/resources/locale/po/");
            boolean noCountries = file.startsWith("apps/routerconsole/resources/docs/");
            int dot = file.lastIndexOf(".");
            int slash = file.lastIndexOf("/");
            String pfx = file.substring(slash + 1, dot);
            String sfx = file.substring(dot);
            String sdir = file.substring(0, slash);
            // we assume we're in build/
            File dir = new File("..", sdir);
            if (!dir.exists())
                continue;
            rv++;
            if (_html) {
                buf.append("<table class=\"tx tx_file\">\n")
                   .append("<tr class=tx_header><th colspan=4>").append(file).append("</th></tr>\n")
                   .append("<tr><th>Language</th><th>Language Code</th><th></th><th>Translated</th></tr>\n");
            } else {
                buf.append("\nTranslations for " + file + "\n")
                   .append("Code\tTX\tLanguage\n")
                   .append("----\t--\t--------\n");
            }
            for (String lg : LANGS) {
                String njlg = lg;
                if (nonJava) {
                    // non-java (debian, installer, man) undo conversion
                    if (lg.equals("in"))
                        njlg = "id";
                    if (lg.equals("iw"))
                        njlg = "he";
                }
                String sf;
                if (pfx.length() > 0)
                    sf = pfx + '_' + njlg + sfx;
                else
                    sf = njlg + sfx;
                File f = new File(dir, sf);
                boolean ok = f.exists();
                String lang;
                String country;
                Locale loc;
                int c = lg.indexOf("_");
                if (c < 0) {
                    lang = lg;
                    country = "";
                    loc = new Locale(lang);
                } else {
                    lang = lg.substring(0, c);
                    country = lg.substring(c + 1);
                    loc = new Locale(lang, country);
                    country = " (" + loc.getDisplayCountry() + ')';
                }
                String dlang = loc.getDisplayLanguage();
                String sok = (noCountries && c >= 0) ? "n/a" : (ok ? "yes" : "no");
                boolean complete = sok.equals("yes");
                String row = "<tr class=incomplete>";
                if (complete) {row = "<tr class=complete>";}
                String cc = getCountryCode(loc);
                String flag = "<span class=langflag><img class=tx_flag src=\"/flags.jsp?c=" + cc + "\" width=24></span>";
                if (_html) {
                    buf.append(row).append("<td>").append(flag).append(dlang).append(country).append("</td><td>")
                       .append(lg).append("</td><td></td><td>").append("<span class=").append(sok).append(">").append(sok).append("</span></td></tr>\n");
                } else {
                    buf.append(lg).append('\t').append(sok).append('\t').append(dlang).append(country).append("\n");
                }
                if (ok || (noCountries && c >= 0))
                    bundles.increment(loc);
                if (ok)
                    foundLangs.add(loc);
            }
            if (_html)
                buf.append("</table>\n<hr>\n");
        }
        if (_html) {buf.append("</span>\n");}  // close containing #tx_summary span
        return rv;
    }

    private void nl() {
        buf.append("\n");
    }

    public static void main(String[] args) throws IOException {
        boolean html = false;
        if (args.length > 0 && args[0].equals("-h")) {
            html = true;
            args = Arrays.copyOfRange(args, 1, args.length);
        }
        if (args.length == 0)
            args = JARS;
        File[] files = new File[args.length];
        for (int i = 0; i < args.length; i++) {
            String f = JARS[i];
            files[i] = new File(f);
        }
        TranslationStatus ts = new TranslationStatus(I2PAppContext.getGlobalContext(), html);
        System.out.print(ts.getStatus(files));
    }

    private static String getCountryCode(Locale loc) {
        String langCode = loc.getLanguage();
        String countryCode = "xx";
        if (langCode.equals("ar")) {countryCode = "lang_ar";}
        else if (langCode.equals("ca")) {countryCode = "lang_ca";}
        else if (langCode.equals("cs")) {countryCode = "cz";}
        else if (langCode.equals("da")) {countryCode = "dk";}
        else if (langCode.equals("et")) {countryCode = "ee";}
        else if (langCode.equals("el")) {countryCode = "gr";}
        else if (langCode.equals("hi")) {countryCode = "in";}
        else if (langCode.equals("fa")) {countryCode = "ir";}
        else if (langCode.equals("iw")) {countryCode = "il";}
        else if (langCode.equals("ja")) {countryCode = "jp";}
        else if (langCode.equals("ko")) {countryCode = "kr";}
        else if (langCode.equals("nb")) {countryCode = "no";}
        else if (langCode.equals("nn")) {countryCode = "no";}
        else if (langCode.equals("sq")) {countryCode = "al";}
        else if (langCode.equals("sv")) {countryCode = "se";}
        else if (langCode.equals("uk")) {countryCode = "ua";}
        else if (langCode.equals("zh")) {countryCode = "cn";}
        else if (!langCode.isEmpty()) {countryCode = langCode.toLowerCase();}
        return countryCode;
    }
}
