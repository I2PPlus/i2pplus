package net.i2p.router;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pins the Tuner's reflector target table against the classes as they are
 * actually compiled.
 * <p>The tuner reaches streaming and i2ptunnel statics by name, because neither
 * module is on the router's compile classpath. A name bound to the wrong class
 * fails the lookup, the failure is swallowed into a sentinel, and the param
 * tunes nothing while its getter hands back a fallback that reads like a live
 * value — which is how setDecayFactor, setRTOMultiplier and
 * getIOMaxPoolUtilization shipped broken. Every bound name is checked here for
 * existing, statically, on the class it is bound to.
 * <p>Those classes are not on the router test classpath, so they are resolved
 * from the module build output under {@code i2p.build.root} when the current
 * classloader cannot see them. A module that was never built in this
 * environment is reported as skipped rather than failed; a class that is visible
 * and does not declare the name is a hard failure.
 */
public class TunerReflectorTargetTest {

    /** Module build output to fall back to, keyed by package prefix. */
    private static final String[][] MODULE_DIRS = {
        {"net.i2p.client.streaming", "apps/streaming/java/build/obj"},
        {"net.i2p.i2ptunnel", "apps/i2ptunnel/java/build/obj"},
    };

    /**
     * Every bound name must be a static method on the class it is bound to, with
     * the shape its reflector calls it with: a setter takes one argument and
     * returns nothing, a getter takes nothing and returns something.
     */
    @Test
    public void everyBoundNameIsAStaticOnItsClass() {
        Map<String, String> targets = Tuner.reflectorTargets();
        assertFalse("the target table must not be empty", targets.isEmpty());
        List<String> skipped = new ArrayList<String>();
        Map<String, Class<?>> classes = loadTargets(targets, skipped);
        List<String> problems = new ArrayList<String>();
        for (Map.Entry<String, String> e : targets.entrySet()) {
            Class<?> c = classes.get(e.getValue());
            if (c == null)
                continue;
            Method m = findStatic(c, e.getKey());
            if (m == null) {
                problems.add(e.getKey() + " is not a static of " + e.getValue());
                continue;
            }
            if (e.getKey().startsWith("set") &&
                (m.getParameterCount() != 1 || m.getReturnType() != void.class))
                problems.add(e.getKey() + " on " + e.getValue() +
                             " is not a void one-argument setter");
            if (e.getKey().startsWith("get") &&
                (m.getParameterCount() != 0 || m.getReturnType() == void.class))
                problems.add(e.getKey() + " on " + e.getValue() +
                             " is not a zero-argument getter");
        }
        if (!skipped.isEmpty())
            System.out.println("TunerReflectorTargetTest skipped, module not built: " + skipped);
        if (!problems.isEmpty())
            fail("Tuner reflector targets do not match the compiled classes:" + join(problems) +
                 "\n* Checked: " + (targets.size() - skipped.size()) +
                 " of " + targets.size() + " bound names");
    }

    /**
     * The names that shipped bound to the wrong class, pinned to the classes that
     * really declare them. The socket manager is the class a call site reaches
     * for by instinct, and it is not the one that declares these.
     */
    @Test
    public void theReboundNamesStayOnTheClassesThatDeclareThem() {
        Map<String, String> targets = Tuner.reflectorTargets();
        String impl = "net.i2p.client.streaming.impl.";
        assertEquals(impl + "SimpleBandwidthEstimator", targets.get("setDecayFactor"));
        assertEquals(impl + "ConnectionOptions", targets.get("setRTOMultiplier"));
        assertEquals(impl + "ConnectionOptions", targets.get("getRTOMultiplier"));
        String tcg = "net.i2p.i2ptunnel.TunnelControllerGroup";
        assertEquals(tcg, targets.get("getIOMaxPoolUtilization"));
    }

    /**
     * A name the tuner calls but the table does not bind has no target, so the
     * failure is reported once instead of turning into a silent no-op. This is
     * the one case that logs a WARN from the suite, and it is the point.
     */
    @Test
    public void unboundNameHasNoTarget() {
        assertFalse(Tuner.reflectorTargets().containsKey("setNotBoundAnywhere"));
        assertNull(Tuner.reflectorTargetClass("setNotBoundAnywhere"));
    }

    /**
     * Loads each bound class once, from the classpath or the module build output.
     *
     * @param targets bound method name to fully-qualified class name, as the
     *                Tuner binds them
     * @param skipped filled in with the bound class names this environment cannot
     *                see, e.g. a module that was never built
     * @return the classes that were found, keyed by their fully-qualified name
     */
    private static Map<String, Class<?>> loadTargets(Map<String, String> targets,
                                                     List<String> skipped) {
        Map<String, Class<?>> loaded = new LinkedHashMap<String, Class<?>>();
        for (String className : new LinkedHashSet<String>(targets.values())) {
            Class<?> c = load(className);
            if (c == null)
                skipped.add(className);
            else
                loaded.put(className, c);
        }
        return loaded;
    }

    /**
     * Finds the class by name, falling back to the module's compiled output so the
     * check is not limited to what the router test classpath happens to carry. The
     * test classloader is the parent, so the module class can link against core.
     *
     * @param className fully-qualified name of the class to resolve
     * @return the class, or null if neither the classpath nor the module build
     *         output under {@code i2p.build.root} carries it
     */
    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            // fall through to the build tree
        }
        String root = System.getProperty("i2p.build.root");
        if (root == null)
            return null;
        for (String[] m : MODULE_DIRS) {
            if (!className.startsWith(m[0]))
                continue;
            File dir = new File(root, m[1]);
            if (!dir.isDirectory())
                return null;
            try {
                ClassLoader parent = TunerReflectorTargetTest.class.getClassLoader();
                URLClassLoader loader = new URLClassLoader(new URL[]{dir.toURI().toURL()}, parent);
                // uninitialized: reading the declarations must not run module statics
                return Class.forName(className, false, loader);
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * The static method of that name declared by the class or any superclass.
     *
     * @param c the class whose declaration chain is searched, up to Object
     * @param name exact method name to match
     * @return the first static method with that name declared by c or a
     *         superclass, or null if none declares one
     */
    private static Method findStatic(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers()))
                    return m;
            }
        }
        return null;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items)
            sb.append("\n* ").append(s);
        return sb.toString();
    }
}
