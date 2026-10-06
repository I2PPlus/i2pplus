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
     * <p>The descriptor registers the welcome body under the panel's own resource id, so
     * that name is what a miss has to fall back to - not "welcome.html", which is only the
     * file that name happens to point at.
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

        @Override public InputStream getInputStream(String name) { return null; }
        @Override public String getString(String name) { return null; }
        @Override public String getString(String name, String d) { return d; }
        @Override public String getString(String name, String p, String d) { return d; }
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

        failures += constructorSetsMatch();

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
