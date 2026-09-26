package net.i2p.router.web;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import net.i2p.I2PAppContext;
import net.i2p.util.FileSuffixFilter;
import org.apache.tomcat.SimpleInstanceManager;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 *  Adds to the webapp classpath as specified in webapps.config.
 *  This allows us to reference classes that are not in the classpath
 *  specified in wrapper.config, since old installations have
 *  individual jars and not lib/*.jar specified in wrapper.config.
 *
 *  A sample line in webapps.config is:
 *     webapps.appname.classpath=foo.jar,$I2P/lib/bar.jar
 *  Unless $I2P is specified the path will be relative to $I2P/lib for
 *  webapps in the installation and appDir/plugins/appname/lib for plugins.
 *
 *  webapps.config is used because setting Class-Path in MANIFEST.MF does not
 *  work for jetty wars, and because WebAppContext.addClassPath() is not
 *  usable here. Jars that are already on the system class path are skipped
 *  to avoid duplicate statics; /susimail is the exception.
 *
 *  @since 0.7.12
 */
public class WebAppConfiguration implements Configuration {

    private static final String CLASSPATH = ".classpath";

    /**
     *  This was the interface in Jetty 5, in Jetty 6 was configureClassLoader(),
     *  now it's configure()
     */
    private void configureClassPath(WebAppContext wac) throws Exception {
        String ctxPath = wac.getContextPath();
        if (ctxPath.equals("/"))
            return;
        String appName = ctxPath.substring(1);

        I2PAppContext i2pContext = I2PAppContext.getGlobalContext();
        File libDir = i2pContext.getLibDir();
        // Get the plugin name that WebAppStarter stuck in here for us
        String pluginName = wac.getInitParameter(WebAppStarter.PARAM_PLUGIN_NAME);
        if (pluginName == null)
            pluginName = ctxPath;
        File pluginDir = new File(i2pContext.getConfigDir(), PluginStarter.PLUGIN_DIR);
        pluginDir = new File(pluginDir, pluginName);

        File dir = libDir;
        String cp;
        if (ctxPath.equals("/susidns")) {
            // Old installs don't have this in their wrapper.config classpath
            cp = "addressbook.jar";
            // Java 11+ fix to prevent dup contexts
            wac.setParentLoaderPriority(true);
        } else if (pluginDir.exists()) {
            File consoleDir = new File(pluginDir, "console");
            Properties props = RouterConsoleRunner.webAppProperties(consoleDir.getAbsolutePath());
            cp = props.getProperty(RouterConsoleRunner.PREFIX + appName + CLASSPATH);
            dir = pluginDir;
        } else {
            Properties props = RouterConsoleRunner.webAppProperties(i2pContext);
            cp = props.getProperty(RouterConsoleRunner.PREFIX + appName + CLASSPATH);
        }
        if (cp == null)
            return;
        StringTokenizer tok = new StringTokenizer(cp, " ,");
        StringBuilder buf = new StringBuilder();
        Set<URI> systemCP = getSystemClassPath(i2pContext);
        while (tok.hasMoreTokens()) {
            if (buf.length() > 0)
                buf.append(',');
            String elem = tok.nextToken().trim();
            String path;
            if (elem.startsWith("$I2P"))
                path = i2pContext.getBaseDir().getAbsolutePath() + elem.substring(4);
            else if (elem.startsWith("$PLUGIN"))
                path = dir.getAbsolutePath() + elem.substring(7);
            else
                path = dir.getAbsolutePath() + '/' + elem;
            // As of Jetty 6, we can't add dups to the class path, or
            // else it screws up statics
            // This is not a complete solution: on Windows the no-wrapper
            // classpath is set by the launchi2p.jar (i2p.exe) manifest and is
            // not detected by getSystemClassPath(), so a dup can still be
            // added there.
            File jfile = new File(path);
            File jdir = jfile.getParentFile();
            if (systemCP.contains(jfile.toURI()) ||
                (jdir != null && systemCP.contains(jdir.toURI()))) {
                // Already on the system class path, so adding it again is
                // redundant. /susimail is the exception (ticket #957):
                // the duplicate is kept there.
                if (!ctxPath.equals("/susimail"))
                    continue;
            }
            buf.append(path);
        }
        if (buf.length() <= 0)
            return;
        ClassLoader cl = wac.getClassLoader();
        if (cl != null && cl instanceof WebAppClassLoader) {
            WebAppClassLoader wacl = (WebAppClassLoader) cl;
            wacl.addClassPath(buf.toString());
        } else {
            // Not a WebAppClassLoader, so fall back to the extra classpath
            // attribute, which is read when the class loader is created.
            wac.setExtraClasspath(buf.toString());
        }
    }

    /**
     * Convert URL to URI so there's no blocking equals(),
     * not that there's really any hostnames in here,
     * but keep findbugs happy.
     * @return the system class path
     * @since 0.9
     */
    private static Set<URI> getSystemClassPath(I2PAppContext ctx) {
        Set<URI> rv = new HashSet<>(32);
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        if (loader instanceof URLClassLoader) {
            // through Java 8, not available in Java 9
            URLClassLoader urlClassLoader = (URLClassLoader) loader;
            URL[] urls = urlClassLoader.getURLs();
            for (int i = 0; i < urls.length; i++) {
                try {
                    rv.add(urls[i].toURI());
                } catch (URISyntaxException use) { /* ignored */ }
            }
        } else {
            // Java 9 - assume everything in lib/ is in the classpath
            // except addressbook.jar
            File libDir = ctx.getLibDir();
            File[] files = libDir.listFiles(new FileSuffixFilter(".jar"));
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    String name = files[i].getName();
                    if (!name.equals("addressbook.jar"))
                        rv.add(files[i].toURI());
                }
            }
        }
        return rv;
    }

    /**
     *  Jetty 7 Configuration hook. Nothing to undo - this configuration
     *  only adjusts the class path, which Jetty owns.
     *  @since Jetty 7
     */
    public void deconfigure(WebAppContext context) {
        // no state to release
    }
    /** @since Jetty 7 */
    public void configure(WebAppContext context) throws Exception {
        configureClassPath(context);
        // One InstanceManager for the whole context, not one per handler.
        // http://stackoverflow.com/questions/17529936/issues-while-using-jetty-embedded-to-handle-jsp-jasperexception-unable-to-com
        // https://github.com/jetty-project/embedded-jetty-jsp/blob/master/src/main/java/org/eclipse/jetty/demo/Main.java
        context.getServletContext().setAttribute("org.apache.tomcat.InstanceManager", new SimpleInstanceManager());
    }

    /** @since Jetty 7 */
    @Override
    public void cloneConfigure(WebAppContext template, WebAppContext context) {
        // no state, nothing to be done
    }

    /**
     *  Jetty 7 Configuration hook. Nothing to destroy - this configuration
     *  holds no resources.
     *  @since Jetty 7
     */
    @Override
    public void destroy(WebAppContext context) {
        // no state to release
    }
    /**
     *  Jetty 7 Configuration hook. Runs before {@link #configure} with no
     *  class path work to do, so this is an intentional no-op.
     *  @since Jetty 7
     */
    @Override
    public void preConfigure(WebAppContext context) {
        // no pre-configuration required
    }
    /**
     *  Jetty 7 Configuration hook. Runs after {@link #configure} with no
     *  follow-up work, so this is an intentional no-op.
     *  @since Jetty 7
     */
    @Override
    public void postConfigure(WebAppContext context) {
        // no post-configuration required
    }}
