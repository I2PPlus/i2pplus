/*
 *  Test that "Made with IzPack" is never rendered, and that the welcome panel shows no
 *  heading, for any language.
 *
 *  IzPack 5 ships a langpack for every language it knows, and each one carries its
 *  own translation of installer.madewith - the title of the etched border around
 *  the installer's log window. Those packs are compiled into the installer from the
 *  compiler's own jar and cannot be replaced: staging a blanked copy in the working
 *  directory has no effect, because the compiler reads the jar in preference. So the
 *  string is still present in the shipped bytes.
 *
 *  What matters is whether it is rendered, and that is a property of the runtime
 *  merge rather than of the file. This test performs the same two steps the
 *  installer performs:
 *
 *    1. load the langpack the user selected, through LocaleDatabase
 *    2. add CustomLangPack over it, the way
 *       AbstractInstallDataProvider.addCustomLangpack does
 *
 *  and then asks for the string. LocaleDatabase extends TreeMap and add is
 *  putAll, so the CustomLangPack wins. Asserting that here means the claim is
 *  checked rather than reasoned about, and it fails loudly if a future IzPack
 *  changes the merge order.
 *
 *  Run by "ant izpack-branding-check" against the built installer jar.
 */

package net.i2p.installer;

import com.izforge.izpack.api.data.LocaleDatabase;
import com.izforge.izpack.api.data.Panel;
import com.izforge.izpack.api.data.InstallData;
import com.izforge.izpack.api.resource.Messages;
import com.izforge.izpack.installer.util.PanelHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class BrandingSuppressedTest {

    /** Languages whose pack the IzPack distribution supplies, i.e. not ours. */
    private static final String[] UPSTREAM = {
        "cat", "ces", "dan", "deu", "ell", "eng", "fin", "fra", "hun", "ita",
        "jpn", "kor", "nld", "nor", "pol", "ron", "rus", "slk", "spa", "swe",
        "tur", "ukr",
    };

    private static final String MADEWITH = "installer.madewith";

    /** The welcome panel's declared class, as install5.xml declares it. */
    private static final String WELCOME_PANEL = "net.i2p.installer.LocalizedHTMLInfoPanel";

    public static void main(String[] args) throws Exception {
        List<String> failures = new ArrayList<>();
        int checked = suppressBranding(failures);
        int headingChecked = suppressWelcomeHeading(failures);

        System.out.println();
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("FAIL: " + f);
            }
            System.out.println(failures.size() + " check(s) failed");
            System.exit(1);
        }
        System.out.println("PASS: \"Made with IzPack\" is not rendered for any of the "
                           + checked + " upstream langpack(s), and the welcome heading "
                           + "is blank in all " + headingChecked + " packaged langpack(s)");
    }

    /** installer.madewith, for the upstream languages only: ours are blanked at generation. */
    private static int suppressBranding(List<String> failures) {
        ClassLoader loader = BrandingSuppressedTest.class.getClassLoader();
        int checked = 0;

        for (String iso3 : UPSTREAM) {
            InputStream pack = loader.getResourceAsStream(
                    "resources/langpacks/" + iso3 + ".xml");
            if (pack == null) {
                System.out.println(iso3 + ": not packaged, skipped");
                continue;
            }
            // One parse per pack: LocaleDatabase consumes and closes the stream, so
            // reading "before" through a second LocaleDatabase would fail.
            LocaleDatabase db = new LocaleDatabase(pack, null);
            String before = db.get(MADEWITH);
            if (before == null || before.trim().isEmpty()) {
                System.out.println(iso3 + ": langpack already blank");
                continue;
            }

            LocaleDatabase merged = withCustomLangpack(db, loader);
            if (merged == null) {
                failures.add(iso3 + ": no CustomLangPack.xml to override the langpack");
                continue;
            }

            String after = merged.get(MADEWITH);
            checked++;
            if (after == null || after.trim().isEmpty()) {
                System.out.println(iso3 + ": suppressed (langpack said \""
                                   + clip(before) + "\")");
            } else {
                failures.add(iso3 + ": still renders " + after);
            }
        }
        return checked;
    }

    /**
     * LocalizedHTMLInfoPanel.info, for every packaged langpack.
     *
     * <p>This one is not a translation of an upstream string and is not blanked in our own
     * packs; no langpack defines it at all. It is the heading HTMLInfoPanel builds from
     * PanelHelper.getPanelTitleMessageKey, which reads installData.getMessages() directly
     * and therefore bypasses the panel's resource proxy - so the only thing standing between
     * the user and the literal text "LocalizedHTMLInfoPanel.info" painted above the welcome
     * text is the entry in CustomLangPack. Every packaged langpack is checked, not just the
     * upstream ones, because the merge is what makes one entry cover all of them and that is
     * the claim worth proving.
     */
    private static int suppressWelcomeHeading(List<String> failures) throws Exception {
        ClassLoader loader = BrandingSuppressedTest.class.getClassLoader();
        List<String> codes = packagedLangpacks();
        int checked = 0;

        for (String iso3 : codes) {
            LocaleDatabase merged = withCustomLangpack(load(iso3, loader), loader);
            if (merged == null) {
                failures.add(iso3 + ": no CustomLangPack.xml to override the langpack");
                continue;
            }
            String text = welcomeHeading(merged);
            checked++;
            if (text == null || text.trim().isEmpty()) {
                // expected: the label is built but paints nothing
                continue;
            }
            failures.add(iso3 + ": welcome heading renders \"" + clip(text) + "\"");
        }
        System.out.println("welcome heading checked in " + checked + " packaged langpack(s)");
        return checked;
    }

    /**
     * Every {@code <iso3>.xml} the installer carries under resources/langpacks.
     *
     * <p>The classpath is scanned rather than this class's own code source, because the test
     * class itself is compiled to a separate directory and so never shares a code source with
     * the installer jar. Scanning also tolerates the jar being unpacked, which is how a
     * developer running this by hand usually has it.
     */
    private static List<String> packagedLangpacks() throws Exception {
        List<String> out = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path")
                                 .split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(entry);
            java.io.File dir = f.isDirectory() ? f : null;
            java.util.jar.JarFile jf = null;
            if (dir == null) {
                if (!f.isFile() || !f.getName().endsWith(".jar")) {
                    continue;
                }
                try {
                    jf = new java.util.jar.JarFile(f);
                    dir = null;
                } catch (java.util.zip.ZipException e) {
                    continue;
                }
            }
            try {
                if (jf != null) {
                    for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                        String n = e.nextElement().getName();
                        String code = langpackCode(n);
                        if (code != null) {
                            out.add(code);
                        }
                    }
                } else {
                    java.io.File[] packs = new java.io.File(
                            dir, "resources/langpacks").listFiles();
                    if (packs == null) {
                        continue;
                    }
                    for (java.io.File p : packs) {
                        String code = langpackCode(p.getName());
                        if (code != null) {
                            out.add(code);
                        }
                    }
                }
            } finally {
                if (jf != null) {
                    jf.close();
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** The iso3 code of a langpack path, or null for anything else. */
    private static String langpackCode(String name) {
        String base = name;
        if (base.startsWith("resources/langpacks/")) {
            base = base.substring("resources/langpacks/".length());
        }
        if (base.indexOf('/') >= 0 || !base.endsWith(".xml")) {
            return null;
        }
        return base.substring(0, base.length() - 4);
    }

    private static LocaleDatabase load(String iso3, ClassLoader loader) throws Exception {
        try (InputStream in = loader.getResourceAsStream(
                "resources/langpacks/" + iso3 + ".xml")) {
            return new LocaleDatabase(in, null);
        }
    }

    /** The merge AbstractInstallDataProvider.addCustomLangpack performs. */
    private static LocaleDatabase withCustomLangpack(LocaleDatabase db, ClassLoader loader) {
        try (InputStream custom = loader.getResourceAsStream("resources/CustomLangPack.xml")) {
            if (custom == null) {
                return null;
            }
            db.add(new LocaleDatabase(custom, null));
            return db;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What HTMLInfoPanel's constructor puts in the header label: PanelHelper derives the key
     * from the class name it finds in the descriptor, then IzPanel.getString answers it out
     * of installData.getMessages(). Reproduced here so the test measures the real lookup
     * rather than a hand-written key that could drift from it.
     */
    private static String welcomeHeading(LocaleDatabase db) {
        final Messages messages = db;
        InstallData data = (InstallData) Proxy.newProxyInstance(
                BrandingSuppressedTest.class.getClassLoader(),
                new Class<?>[] { InstallData.class },
                new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] a) {
                        if ("getMessages".equals(m.getName())) {
                            return messages;
                        }
                        throw new UnsupportedOperationException(m.getName());
                    }
                });
        Panel panel = new Panel();
        panel.setClassName(WELCOME_PANEL);
        return data.getMessages().get(
                PanelHelper.getPanelTitleMessageKey(panel, "info", data));
    }

    private static String clip(String s) {
        String flat = s.replace('\n', ' ').trim();
        return flat.length() > 32 ? flat.substring(0, 32) + "..." : flat;
    }
}
