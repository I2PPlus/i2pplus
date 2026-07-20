package net.i2p.util;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.i2p.I2PAppContext;

/**
 * Output translation stats by loading ResourceBundles from jars and wars,
 * in html or text format.
 *
 * Bundles only, does not support external resources (html files, man pages,
 * Debian po files) or the gettext properties files (with the exception of the
 * ndt property files, which are scanned as Other Resources).
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

    // Compiled Java property bundles are scanned directly from the jars/wars,
    // so the language list is derived dynamically from the source PO dirs plus
    // the ndt property files (see buildLangList()). The list below is only the
    // fallback used when the build tree is not available.
    private static final String[] BASE_LANGS = { "ar", "az", "bn", "bo", "cs", "da", "de", "el", "es", "et",
                                                 "fa", "fi", "fr", "hi", "hu", "in", "it", "ja", "ko", "nb",
                                                 "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk",
                                                 "vi", "zh" };

    // Non-Java / non-compiled resources. Property and po files are scanned for
    // existence per language; the ndt property files are included here.
    // debian/po is intentionally omitted: the directory is empty in this tree.
    private static final String[] FILES = { "core/java/src/gnu/getopt/MessagesBundle.properties",
                                            "installer/resources/readme/readme.html",               // no country variants supported
                                            "installer/resources/eepsite/docroot/help/index.html",
                                            "installer/resources/locale-man/man.po",                // non-Java
                                            "installer/resources/locale/po/messages.po",            // non-Java
                                            "apps/routerconsole/java/src/edu/internet2/ndt/locale/Tcpbw100_msgs.properties" }; // property, non-Java

    // Source trees scanned to derive the language list (relative to the build dir,
    // which is one level below the project root).
    private static final String[] PO_DIRS = { "apps/routerconsole/locale", "apps/susidns/locale",
                                              "apps/i2psnark/locale", "apps/i2ptunnel/locale",
                                              "apps/susimail/locale", "core/locale", "router/locale",
                                              "installer/resources/locale/po", "installer/resources/locale-man",
                                              "apps/routerconsole/java/src/edu/internet2/ndt/locale" };

    public TranslationStatus(I2PAppContext ctx, boolean html) {
        _context = ctx;
        _html = html;
        buf = new StringBuilder(65536);
        buf2 = new StringBuilder(4096);
        langs = buildLangList();
        counts = new ObjectCounterUnsafe<>();
        bundles = new ObjectCounterUnsafe<>();
        foundLangs = new HashSet<>(64);
    }

    /**
     * Build the language list. Scans the source PO dirs and the ndt property
     * directory for the set of locale codes actually present, then sorts them
     * alphabetically. Falls back to BASE_LANGS if the build tree is not found.
     *
     * @return immutable, alphabetically sorted list of language codes
     */
    private static List<String> buildLangList() {
        Set<String> found = new TreeSet<>();
        boolean any = false;
        for (String dir : PO_DIRS) {
            File d = new File("..", dir);
            if (!d.isDirectory())
                continue;
            File[] files = d.listFiles();
            if (files == null)
                continue;
            for (File f : files) {
                String name = f.getName();
                String code = localeFromFileName(name);
                if (code != null) {
                    found.add(code);
                    any = true;
                }
            }
        }
        if (!any)
            return Collections.unmodifiableList(Arrays.asList(BASE_LANGS));
        return Collections.unmodifiableList(new ArrayList<>(found));
    }

    /**
     * Extract the locale code from a translation file name, or null if it is
     * not a translation file. Handles messages_xx.po, man_xx.po and
     * Tcpbw100_msgs_xx.properties (and the en template, which is skipped).
     *
     * @param name file name
     * @return locale code (e.g. "ar", "zh_TW") or null
     */
    private static String localeFromFileName(String name) {
        String code = null;
        if (name.startsWith("messages_") && name.endsWith(".po"))
            code = name.substring(9, name.length() - 3);
        else if (name.startsWith("man_") && name.endsWith(".po"))
            code = name.substring(4, name.length() - 3);
        else if (name.startsWith("Tcpbw100_msgs_") && name.endsWith(".properties"))
            code = name.substring(14, name.length() - 11);
        if (code == null || code.isEmpty() || code.equals("en"))
            return null;
        return code;
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
        List<String> classes = new ArrayList<>(64);
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
            List<ResourceBundle> buns = new ArrayList<>(64);
            // key count of the English (template) bundle for this resource,
            // used as the reference for the % translated so fallback masking
            // cannot report a locale as more complete than the source.
            int enTot = -1;
            for (String name : classes) {
                name = name.substring(0, name.length() - 6);  // .class
                int c = name.indexOf('_');
                String clz = name.substring(0, c).replace("/", ".");
                if (!clz.equals(pclz)) {
                    // pass 1C: output a table for the resource
                    // output goes here, we have to make two passes to find the max
                    // number of entries to generate a true %
                    if (!buns.isEmpty()) {
                        report(pclz, max, enTot, buns);
                        resources++;
                    }
                    grandtot += max;
                    pclz = clz;
                    max = 0;
                    enTot = -1;
                    buns.clear();
                }
                String s = name.substring(c + 1);
                Locale loc = localeFromString(s);
                foundLangs.add(loc);
                ResourceBundle bun;
                try {
                    bun = ResourceBundle.getBundle(clz, loc, cl);
                } catch (Exception e) {
                    System.err.println("FAILED loading class " + clz + " lang " + loc);
                    continue;
                }
                // in this pass we just calculate the max strings
                buns.add(bun);
                int tot = bun.keySet().size() - 1; // subtract empty header string
                if (loc.getLanguage().isEmpty() || loc.getLanguage().equals("en")) {
                    // English / default template bundle
                    if (enTot < 0)
                        enTot = tot;
                }
                if (tot > max)
                    max = tot;
            }
            if (!buns.isEmpty()) {
                report(pclz, max, enTot, buns);
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
        for (Locale loc : sortedLocales(counts.objects())) {
            String s = loc.getLanguage();
            String lang = loc.getDisplayLanguage();
            String country = loc.getCountry();
            String cc = getCountryCode(loc);
            boolean isTibet = cc.equals("bo");
            String flag = "<span class=langflag><img class=tx_flag src=\"/flags.jsp?c=" + (isTibet ? "xt" : cc) + "\" width=24></span>";
            if (country.length() > 0) {
                s += '_' + country;
                country = '(' + loc.getDisplayCountry() + ')';
            }
            if (_html) {
                buf2.append(String.format(Locale.US, "<tr><td>%s %s %s</td><td>%s</td><td>%d</td>" +
                                                     "<td><span class=percentBarOuter title=\"%5.1f%%\">" +
                                                     "<span class=percentBarInner style=\"width:%5.1f%%\"></span></span></td>" +
                                                      "</tr>%n",
                                                      flag, lang, country, s, resources - bundles.count(loc), 100f * counts.count(loc) / grandtot,
                                                      100f * counts.count(loc) / grandtot));
            } else {
                buf2.append(String.format("%s\t%5.1f%%\t%s %s%n", s, 100f * counts.count(loc) / grandtot, resources - bundles.count(loc), lang, country));
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

    private void report(String clz, int max, int enTot, List<ResourceBundle> buns) {
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
        String git = "<a href=http://git.skank.i2p/i2pplus/I2P.Plus/src/branch/master/" + location + " target=_blank>" + location + "</a>";
        if (!_html) {
            buf.append("\n\nTranslations for ")
               .append(clz).append(" (").append(max).append(" strings, ")
               .append(buns.size()).append(" translations)\n")
               .append("Code\t  TX\t   %TX\tLanguage\n")
               .append("----\t----\t------\t--------\n");
        } else {
            buf.append("<table class=\"tx tx_compiled\">\n<thead>\n")
               .append("<tr class=\"tx_header ").append(classTitle).append("\">")
               .append("<th colspan=2>").append(clz).append(" (").append(max).append(" strings, ")
               .append(buns.size()).append(" translations)</th>")
               .append("<th colspan=2 class=tx_location><span>").append(git).append("</span></th></tr>\n")
               .append("<tr><th>Language</th><th>Language Code</th><th>Translated</th><th>% Translated</th></tr>\n")
               .append("</thead>\n<tbody>\n");
        }
        Set<String> missing = new TreeSet<>(langs);
        // reference total: the English template if found, otherwise the largest bundle
        int ref = enTot >= 0 ? enTot : max;
        for (ResourceBundle bun : buns) {
            int tot = bun.keySet().size() - 1; // subtract empty header string
            Locale loc = bun.getLocale();
            String lang = loc.getLanguage();
            String country = loc.getCountry();
            String cc = getCountryCode(loc);
            boolean isTibet = cc.equals("bo");
            String flag = "<span class=langflag><img class=tx_flag src=\"/flags.jsp?c=" + (isTibet ? "xt" : cc) + "\" width=24></span>";
            String dlang = loc.getDisplayLanguage();
            if (country.length() > 0) {
                lang += '_' + country;
                country = '(' + loc.getDisplayCountry() + ')';
            }
            missing.remove(lang);
            counts.add(loc, tot);
            bundles.increment(loc);
            // incomplete if the bundle has fewer real entries than the reference (en) template
            boolean incomplete = tot < ref;
            String row = incomplete ? "<tr class=incomplete>" : "<tr class=complete>";
            if (_html) {
                buf.append(String.format(Locale.US, "%s<td>%s %s %s</td><td>%s</td><td>%4d</td>" +
                                                    "<td><span class=percentBarOuter title=\"%5.1f%%\">" +
                                                    "<span class=percentBarInner style=\"width:%5.1f%%\"></span></span></td></tr>%n",
                                                     row, flag, dlang, country, lang, tot, 100f * tot / ref, 100f * tot / ref));
            } else {
                buf.append(String.format("%s\t%4d\t%5.1f%%\t%s %s%n", lang, tot, 100f * tot / ref, dlang, country));
            }
        }
        buf.append("</tbody>");

        if (!missing.isEmpty()) {
            if (_html)
                buf.append("<tfoot><tr><th class=tx_notranslate colspan=4><b>Not translated:</b>\n");
            else
                buf.append("Not translated:\n");
            for (String s : missing) {
                Locale loc = localeFromString(s);
                String dlang = loc.getDisplayLanguage();
                String country = "";
                if (s.indexOf('_') >= 0)
                    country = " (" + loc.getDisplayCountry() + ')';
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
            boolean nonJava = file.startsWith("installer/resources/locale-man/") ||
                              file.startsWith("installer/resources/locale/po/") ||
                              file.contains("ndt/locale/");
            // installer/man/po use the gettext "id" code for Indonesian, while the
            // ndt property files use the Java "in" code; only remap for the former.
            boolean remapIn = file.startsWith("installer/resources/locale-man/") ||
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
            for (String lg : langs) {
                String njlg = lg;
                if (remapIn) {
                    // installer/man/po use gettext locale codes
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
                Locale loc = localeFromString(lg);
                String lang = loc.getLanguage();
                String country = loc.getCountry();
                String dlang = loc.getDisplayLanguage();
                if (country.length() > 0)
                    country = " (" + loc.getDisplayCountry() + ')';
                String sok = (noCountries && lg.indexOf('_') >= 0) ? "n/a" : (ok ? "yes" : "no");
                boolean complete = sok.equals("yes");
                String row = complete ? "<tr class=complete>" : "<tr class=incomplete>";
                String cc = getCountryCode(loc);
                boolean isTibet = cc.equals("bo");
                String flag = "<span class=langflag><img class=tx_flag src=\"/flags.jsp?c=" + (isTibet ? "xt" : cc) + "\" width=24></span>";
                if (_html) {
                    buf.append(row).append("<td>").append(flag).append(dlang).append(country).append("</td><td>")
                       .append(lg).append("</td><td></td><td>").append("<span class=").append(sok).append(">").append(sok).append("</span></td></tr>\n");
                } else {
                    buf.append(lg).append('\t').append(sok).append('\t').append(dlang).append(country).append("\n");
                }
                if (ok || (noCountries && lg.indexOf('_') >= 0))
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

    /**
     * Parse a locale code string (e.g. "ar" or "zh_TW") into a Locale.
     *
     * @param s locale code
     * @return Locale
     */
    private static Locale localeFromString(String s) {
        int c = s.indexOf('_');
        if (c < 0)
            return new Locale(s);
        return new Locale(s.substring(0, c), s.substring(c + 1));
    }

    /**
     * Return the locales sorted alphabetically by language code (then country),
     * rather than by descending count. Used for the summary table.
     *
     * @param locales locales to sort
     * @return alphabetically sorted list
     */
    private static List<Locale> sortedLocales(Set<Locale> locales) {
        List<Locale> rv = new ArrayList<>(locales);
        Collections.sort(rv, new Comparator<Locale>() {
            @Override
            public int compare(Locale l, Locale r) {
                int rv = l.getLanguage().compareTo(r.getLanguage());
                if (rv != 0) return rv;
                return l.getCountry().compareTo(r.getCountry());
            }
        });
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
        else if (langCode.equals("in")) {countryCode = "id";}
        else if (langCode.equals("fa")) {countryCode = "ir";}
        else if (langCode.equals("iw")) {countryCode = "il";}
        else if (langCode.equals("ja")) {countryCode = "jp";}
        else if (langCode.equals("ko")) {countryCode = "kr";}
        else if (langCode.equals("nb")) {countryCode = "no";}
        else if (langCode.equals("nn")) {countryCode = "no";}
        else if (langCode.equals("sl")) {countryCode = "si";}
        else if (langCode.equals("sq")) {countryCode = "al";}
        else if (langCode.equals("sv")) {countryCode = "se";}
        else if (langCode.equals("uk")) {countryCode = "ua";}
        else if (langCode.equals("vi")) {countryCode = "vn";}
        else if (langCode.equals("zh")) {countryCode = "cn";}
        else if (!langCode.isEmpty()) {countryCode = langCode.toLowerCase();}
        return countryCode;
    }
}
