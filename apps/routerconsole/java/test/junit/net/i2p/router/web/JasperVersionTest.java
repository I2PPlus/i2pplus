package net.i2p.router.web;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Conformance tests for the Tomcat/Jasper version shown on the router information page.
 *
 * <p>{@link RouterConsoleRunner#TOMCAT_VERSION} is a literal, because the Jasper jars
 * we ship are merged out of the checked-in apache-tomcat tree by apps/jetty/build.xml,
 * which throws the upstream manifest away — nothing at run time knows what release the
 * classes came from. A literal copied by hand is exactly the kind of string a version
 * bump leaves behind, and a stale one on /info is a support ticket: the reader is told
 * to look for a CVE in a release that is not installed.
 *
 * <p>So the number lives in four places, and tools/scripts/update-tomcat.sh rewrites
 * all four in one run. These tests fail if any of them is left behind, which is what
 * makes the literal safe to keep: a bump done by hand, or a script run that only
 * half-applies, goes red here instead of shipping the wrong version.
 *
 * @since 0.9.72+
 */
public class JasperVersionTest {

    /** The pin the ant build and the packaging targets read. */
    private static final Pattern ANT_PIN =
        Pattern.compile("<property name=\"tomcat\\.ver\" value=\"([^\"]+)\"");

    /** The pin the app build.gradle files read. */
    private static final Pattern GRADLE_PIN =
        Pattern.compile("(?m)^[ \\t]*tomcatVersion[ \\t]*=[ \\t]*(\\S+)");

    /** A Tomcat release as a version directory name. */
    private static final Pattern DIR_VERSION = Pattern.compile("^apache-tomcat-(.+)$");

    /** A Tomcat release as we report it: three dot-separated numbers. */
    private static final Pattern RELEASE = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    /**
     * The shipped constant is a well-formed release, not an empty string or a
     * placeholder, and not the JSP spec version the page used to print.
     */
    @Test
    public void testConstantIsARelease() {
        String ver = RouterConsoleRunner.TOMCAT_VERSION;
        assertNotNull(ver);
        assertTrue("\"" + ver + "\" is not a Tomcat release", RELEASE.matcher(ver).matches());
    }

    /**
     * The ant pin, the gradle pin and the checked-in apps/jetty/apache-tomcat-*
     * directory all name the same release the console reports.
     *
     * @throws IOException if a pin file cannot be read
     */
    @Test
    public void testConstantMatchesEveryPin() throws IOException {
        File root = repoRoot();
        String reported = RouterConsoleRunner.TOMCAT_VERSION;

        String ant = first(ANT_PIN, read(new File(root, "build.xml")));
        assertNotNull("no tomcat.ver property in " + new File(root, "build.xml"), ant);
        assertEquals("build.xml pins a different Tomcat than /info reports", reported, ant);

        File gradleProps = new File(root, "gradle.properties");
        String gradle = first(GRADLE_PIN, read(gradleProps));
        assertNotNull("no tomcatVersion in " + gradleProps, gradle);
        assertEquals("gradle.properties pins a different Tomcat than /info reports",
                     reported, gradle);

        File jettyDir = new File(root, "apps/jetty");
        File[] vendored = jettyDir.listFiles((dir, name) -> name.startsWith("apache-tomcat-"));
        assertTrue("no apache-tomcat-* directory in " + jettyDir,
                   vendored != null && vendored.length > 0);
        for (File dir : vendored) {
            Matcher m = DIR_VERSION.matcher(dir.getName());
            assertTrue(dir.getName(), m.matches());
            assertEquals("the vendored " + dir.getName() + " disagrees with /info",
                         reported, m.group(1));
        }
    }

    /**
     * Walks up from the test's working directory to the checkout holding the pins.
     * ant runs the suite from apps/routerconsole/java and gradle from apps/routerconsole,
     * so the root is never more than a few levels up.
     *
     * @return the directory holding both build.xml and gradle.properties
     * @throws IOException if no such directory is found above the working directory
     */
    private static File repoRoot() throws IOException {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            File build = new File(dir, "build.xml");
            if (new File(dir, "gradle.properties").isFile() && build.isFile() &&
                read(build).contains("tomcat.ver")) {
                return dir;
            }
        }
        throw new IOException("no build.xml with a tomcat.ver property above " +
                              new File("").getAbsoluteFile());
    }

    /**
     * @param file the file to read
     * @return its text, decoded as UTF-8
     * @throws IOException if the file cannot be read
     */
    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * @param pattern the pattern to look for
     * @param text the text to search
     * @return the first capture group, or null if the pattern does not match
     */
    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
