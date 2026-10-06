package net.i2p.installer;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.lang.reflect.Constructor;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.ImageIcon;

import com.izforge.izpack.api.exception.ResourceException;
import com.izforge.izpack.api.exception.ResourceNotFoundException;
import com.izforge.izpack.api.resource.Resources;

/**
 * Tests the language-to-body mapping, which is the whole point of the panel.
 *
 * <p>No installer, no GUI: the proxy is exercised against a stub Resources that behaves the
 * way IzPack's does, which is the detail that matters - Resources.getURL throws
 * ResourceNotFoundException for an absent name rather than returning null, so "no translation
 * yet" has to be caught rather than tested for.
 */
public class LocalizedResourcesTest {

    /**
     * Serves only the names it is given, throwing for anything else, as IzPack's does.
     *
     * <p>The descriptor registers the body under the stock panel's resource id, so that is
     * what a miss has to fall back to - not "welcome.html", which is only the file that id
     * happens to point at.
     */
    private static final class Stub implements Resources {
        private final String[] present;

        Stub(String... present) {
            this.present = present;
        }

        @Override
        public URL getURL(String name) throws ResourceNotFoundException {
            for (String p : present) {
                if (p.equals(name)) {
                    try {
                        return new URL("file:///" + name);
                    } catch (MalformedURLException e) {
                        throw new AssertionError(e);
                    }
                }
            }
            throw new ResourceNotFoundException("Failed to locate resource: " + name);
        }

        /**
         * Langpack keys this stub knows, for the getString assertions.
         */
        private final java.util.Map<String, String> strings = new java.util.HashMap<>();

        @Override public InputStream getInputStream(String name) { return null; }

        /**
         * Answers langpack keys, and reproduces the trap this test exists for: an unknown key
         * comes back as the key itself, exactly as LocaleDatabase.get does. A stub that
         * returned null would let a missing translation pass unnoticed.
         */
        @Override public String getString(String name) { return strings.getOrDefault(name, name); }
        @Override public String getString(String name, String d) { return strings.getOrDefault(name, d); }
        @Override public String getString(String name, String p, String d) { return strings.getOrDefault(name, d); }
        @Override public Object getObject(String name) throws ResourceException { return null; }
        @Override public ImageIcon getImageIcon(String name, String... params) { return null; }
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;

        // A language with no translation falls back to the shared body rather than failing.
        failures += expect("HTMLHelloPanel.info",
                new LocalizedHTMLInfoPanel.LocalizedResources(
                        new Stub("HTMLHelloPanel.info"), "ces").getURL("HTMLHelloPanel.info"),
                "untranslated language falls back to the panel's own resource");

        // A supplied translation wins over the shared body.
        failures += expect("welcome_ces.html",
                new LocalizedHTMLInfoPanel.LocalizedResources(
                        new Stub("HTMLHelloPanel.info", "welcome_ces.html"), "ces")
                        .getURL("HTMLHelloPanel.info"),
                "a supplied translation is preferred");

        // Only .info is rewritten, so images and strings the panel asks for are untouched.
        failures += expect("installer.langsel.img",
                new LocalizedHTMLInfoPanel.LocalizedResources(
                        new Stub("HTMLHelloPanel.info", "welcome_ces.html", "installer.langsel.img"),
                        "ces").getURL("installer.langsel.img"),
                "a non-.info resource is not rewritten");

        // No language selected means no rewrite at all.
        failures += expect("HTMLHelloPanel.info",
                new LocalizedHTMLInfoPanel.LocalizedResources(
                        new Stub("HTMLHelloPanel.info"), null).getURL("HTMLHelloPanel.info"),
                "no language means the panel's own resource");

        failures += expect("welcome_heb.html",
                LocalizedHTMLInfoPanel.bodyResource("heb"),
                "bodyResource names the per-language file");

        // IzPack derives a panel's resource names from the class it instantiated, so in
        // production this is the name it asks with - not HTMLHelloPanel.info. Rebasing the
        // subclass prefix back is what keeps the descriptor's <res> ids resolvable; without
        // it the panel dies with "Cannot find named resource: 'LocalizedHTMLInfoPanel.info'".
        // The case above passed only because it used a name production never uses.
        // CONTRACT: this expectation is the id the descriptor must register the body under,
        // and gen-izpack-welcome.py --check reads it to verify install5.xml agrees. Keep the
        // literal here the single source of truth; the ones above are historical coverage.
        failures += expect("HTMLInfoPanel.info",   // <body-resource-id>
                new LocalizedHTMLInfoPanel.LocalizedResources(
                        new Stub("HTMLInfoPanel.info"), null).getURL(
                                "LocalizedHTMLInfoPanel.info"),
                "our class's own resource name resolves to the superclass's id");

        failures += expect("welcome_fra.html",
                new LocalizedHTMLInfoPanel.LocalizedResources(
                        new Stub("HTMLInfoPanel.info", "welcome_fra.html"), "fra")
                        .getURL("LocalizedHTMLInfoPanel.info"),
                "a translated body is found via our class's own resource name");

        failures += expect("HTMLInfoPanel.winservice",
                new LocalizedHTMLInfoPanel.LocalizedResources(
                        new Stub("HTMLInfoPanel.winservice"), "ces").getURL(
                                "LocalizedHTMLInfoPanel.winservice"),
                "a second suffix keeps the superclass prefix and is not mistaken for a body");

        // A langpack key the panel owns resolves to the stock key, not the raw key name. This
        // is the proxy's own contract only: the panel's header label never came through here
        // (IzPanel.getString reads installData.getMessages() directly), which is why the panel
        // passes writeConfig=false and builds no header at all. This case guards the rewrite
        // for anything that does ask the proxy for a panel-prefixed string.
        Stub withHeadline = new Stub("HTMLInfoPanel.info");
        withHeadline.strings.put("HTMLInfoPanel.headline", "Information");
        failures += expect("Information",
                new LocalizedHTMLInfoPanel.LocalizedResources(withHeadline, null)
                        .getString("LocalizedHTMLInfoPanel.headline"),
                "a panel-prefixed string key resolves to the stock langpack key");

        // An unrelated string must not be rewritten onto the panel prefix.
        failures += expect("unrelated.value",
                new LocalizedHTMLInfoPanel.LocalizedResources(new Stub(), null)
                        .getString("unrelated.value", "unrelated.value"),
                "a string this panel does not own is untouched");

        failures += constructorSetsMatch();
        failures += siblingPanelsResolve();

        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("LocalizedResourcesTest OK");
    }


    /**
     * Our panel must offer exactly the constructor signatures the class it replaces offers.
     *
     * <p>IzPack instantiates panels through picocontainer, which autowires by parameter type
     * and cannot satisfy a primitive boolean - there is no binding for it. HTMLInfoPanel
     * declares a five-argument constructor as well as the six-argument one, and the container
     * picks the five-argument one; a subclass declaring only the six-argument form builds
     * cleanly and then fails the first time the panel is shown, with
     * "unsatisfied dependency 'class java.lang.Boolean'". Comparing the two sets catches that
     * here rather than in front of a user.
     */
    private static int constructorSetsMatch() {
        Set<String> ours = signatures(LocalizedHTMLInfoPanel.class);
        Set<String> stock = signatures(com.izforge.izpack.panels.htmlinfo.HTMLInfoPanel.class);
        if (ours.equals(stock)) {
            System.out.println("  ok: constructor signatures match HTMLInfoPanel");
            return 0;
        }
        System.out.println("FAIL: constructor signatures differ from HTMLInfoPanel");
        System.out.println("  ours : " + ours);
        System.out.println("  stock: " + stock);
        return 1;
    }

    /**
     * The console and automation siblings must resolve under IzPack's own naming rule.
     *
     * <p>IzPack finds a panel's console counterpart by rewriting the trailing {@code Panel} of the
     * declared class name to {@code ConsolePanel}, and its automation helper the same way. Nothing
     * in this repository references either class, so a rename or a missing file is invisible to
     * the compiler and to the GUI installer - it only shows up as a console or {@code -options-auto}
     * install dying on the welcome panel:
     *
     * <pre>
     *   SEVERE: IzPackException: Console implementation not found for panel:
     *           net.i2p.installer.LocalizedHTMLInfoPanel
     * </pre>
     *
     * <p>That is exactly how this was found: IzPack 4 uses the stock {@code HelloPanel} and was
     * never affected, so only the IzPack 5 path broke, and only in text mode. The resolver is
     * asked directly here rather than reimplementing its rule, so the test fails if IzPack ever
     * changes the convention.
     *
     * <p>The two classes are named directly rather than looked up by string, which makes their
     * absence a compile error instead of a test failure. That is the better trade here: the class
     * name <em>is</em> the contract IzPack relies on, so a missing file should stop the build
     * outright rather than surface as a runtime assertion.
     */
    private static int siblingPanelsResolve() {
        String declared = "net.i2p.installer.LocalizedHTMLInfoPanel";
        int failures = 0;

        Class<?> console;
        try {
            console = com.izforge.izpack.installer.util.PanelHelper.getConsolePanel(declared);
        } catch (RuntimeException e) {
            console = null;
        }
        if (console == LocalizedHTMLInfoConsolePanel.class) {
            System.out.println("  ok: console panel resolves to " + console.getSimpleName());
        } else {
            System.out.println("FAIL: console panel for " + declared + " resolved to " + console);
            System.out.println("  expected " + LocalizedHTMLInfoConsolePanel.class.getName());
            System.out.println("  a null here means -console and -options-auto cannot run");
            failures++;
        }

        Class<?> automation;
        try {
            automation = com.izforge.izpack.installer.util.PanelHelper.getAutomatedPanel(declared);
        } catch (RuntimeException e) {
            automation = null;
        }
        if (automation == LocalizedHTMLInfoPanelAutomationHelper.class) {
            System.out.println("  ok: automation helper resolves to "
                               + automation.getSimpleName());
        } else {
            System.out.println("FAIL: automation helper for " + declared
                               + " resolved to " + automation);
            System.out.println("  expected "
                               + LocalizedHTMLInfoPanelAutomationHelper.class.getName());
            failures++;
        }

        return failures;
    }

    /** Parameter type names of every declared constructor, as a comparable set. */
    private static Set<String> signatures(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            // Parameter types only: the declaring class is expected to differ, it is a
            // subclass. What has to match is which signatures exist and what they ask for.
            StringBuilder sb = new StringBuilder();
            for (Class<?> p : c.getParameterTypes()) {
                sb.append(p.getName()).append(',');
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static int expect(String want, URL got, String what) {
        String g = got == null ? "null" : got.toString();
        if (!g.endsWith("/" + want)) {
            System.out.println("FAIL: " + what + " - wanted " + want + ", got " + g);
            return 1;
        }
        System.out.println("  ok: " + what);
        return 0;
    }

    private static int expect(String want, String got, String what) {
        if (!want.equals(got)) {
            System.out.println("FAIL: " + what + " - wanted " + want + ", got " + got);
            return 1;
        }
        System.out.println("  ok: " + what);
        return 0;
    }
}
