package org.klomp.snark.standalone;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jetty.util.log.Log;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.apps.systray.UrlLauncher;
import net.i2p.data.DataHelper;
import net.i2p.jetty.I2PLogger;
import net.i2p.jetty.JettyStart;

/**
 *  @since moved from ../web and fixed in 0.9.27
 */
public class RunStandalone {

    private final JettyStart _jettyStart;
    private final I2PAppContext _context;
    private String _host;
    private int _port;
    private static RunStandalone _instance;
    static final File APP_CONFIG_FILE = new File("i2psnark-appctx.config");

    private RunStandalone(String args[]) throws Exception {
        Properties p = new Properties();

        if (APP_CONFIG_FILE.exists()) {
            try {DataHelper.loadProps(p, APP_CONFIG_FILE);}
            catch (IOException ioe) {}
        }

        _context = new I2PAppContext(p);
        // Do this after we have a context
        // To take effect, must be set before any Jetty classes are loaded
        try {Log.setLog(new I2PLogger(_context));}
        catch (Throwable t) {
            System.err.println("INFO: I2P Jetty logging class not found, logging to stdout");
        }
        File base = _context.getBaseDir();
        File xml = new File(base, "jetty-i2psnark.xml");
        _jettyStart = new JettyStart(_context, null, new String[] { xml.getAbsolutePath() } );
        _host = getHostFromJettyConfig();
        _port = getPortFromJettyConfig();

        if (args.length > 1) {_port = Integer.parseInt(args[1]);}
        if (args.length > 0) {_host = args[0];}
    }

    /**
     *  Usage: RunStandalone [host [port]] (but must match what's in the jetty-i2psnark.xml file)
     */
    public synchronized static void main(String args[]) {
        try {
            RunStandalone runner = new RunStandalone(args);
            runner.start();
            _instance = runner;
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void start() {
        try {
            String url = "http://" + _host + ':' + _port + "/i2psnark/";
            System.out.println(" • Starting I2P+ I2PSnark Standalone " + CoreVersion.VERSION + " at " + url);
            System.out.println(" • Revision: " + getSnarkRevision());
            _jettyStart.startup();
            try {Thread.sleep(1000);}
            catch (InterruptedException ie) {}
            String p = _context.getProperty("routerconsole.browser");
            if (!("/bin/false".equals(p) || "NUL".equals(p))) {
                UrlLauncher launch = new UrlLauncher(_context, null, new String[] { url } );
                launch.startup();
            }
        } catch (Exception e) {e.printStackTrace();}
    }

    public void stop() {_jettyStart.shutdown(null);}

    /** @since 0.9.27 */
    public synchronized static void shutdown() {
        if (_instance != null) {_instance.stop();}
        try {Thread.sleep(3000);} // JettyStart.shutdown() is threaded
        catch (InterruptedException ie) {}
        System.exit(1);
    }

    /** @since 0.9.67+ */
    public String getSnarkRevision() {
        File base = _context.getBaseDir();
        File jar = new File(base, "i2psnark.jar");
        String rev = "", date = "";
        try {
            Manifest manifest = new Manifest(new URL("jar:" + jar.toURI().toURL().toString() + "!/META-INF/MANIFEST.MF").openStream());
            Attributes att = manifest.getMainAttributes();
            rev = att.getValue("Base-Revision");
            date = att.getValue("Build-Date");
        } catch (IOException e) {}
        return rev.isEmpty() ? "unknown" : rev + " (Built: " + date + ")";
    }

    /** @since 0.9.67+ */
    public String getHostFromJettyConfig() {
        try {
            File base = _context.getBaseDir();
            File xml = new File(base, "jetty-i2psnark.xml");
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xml);
            doc.getDocumentElement().normalize();

            NodeList nList = doc.getElementsByTagName("Set");
            for (int temp = 0; temp < nList.getLength(); temp++) {
                Node nNode = nList.item(temp);
                if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                    if ("host".equals(nNode.getAttributes().getNamedItem("name").getNodeValue())) {
                        return nNode.getTextContent();
                    }
                }
            }
        } catch (Exception e) {}
        return "127.0.0.1";
    }

    /** @since 0.9.67+ */
    public int getPortFromJettyConfig() {
        try {
            File base = _context.getBaseDir();
            File xml = new File(base, "jetty-i2psnark.xml");
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xml);
            doc.getDocumentElement().normalize();

            NodeList nList = doc.getElementsByTagName("Set");
            for (int temp = 0; temp < nList.getLength(); temp++) {
                Node nNode = nList.item(temp);
                if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                    if ("port".equals(nNode.getAttributes().getNamedItem("name").getNodeValue())) {
                        return Integer.parseInt(nNode.getTextContent());
                    }
                }
            }
        } catch (Exception e) {}
        return 8002;
    }

}