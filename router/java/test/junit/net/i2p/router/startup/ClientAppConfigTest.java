package net.i2p.router.startup;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

/**
 * Tests for ClientAppConfig construction, fields, parsing, equals, and hashCode.
 */
public class ClientAppConfigTest {

    @Test
    public void testBasicConstructor() {
        ClientAppConfig cac = new ClientAppConfig("com.example.Foo", "My App", "--arg1", 30000L, false);
        assertEquals("com.example.Foo", cac.className);
        assertEquals("My App", cac.clientName);
        assertEquals("--arg1", cac.args);
        assertEquals(30000L, cac.delay);
        assertFalse(cac.disabled);
        assertNull(cac.classpath);
        assertNull(cac.stopargs);
        assertNull(cac.uninstallargs);
    }

    @Test
    public void testFullConstructor() {
        ClientAppConfig cac = new ClientAppConfig("com.example.Bar", "Bar App", "a b c", 5000L, true, "/lib/ext.jar", "stop", "remove");
        assertEquals("com.example.Bar", cac.className);
        assertEquals("Bar App", cac.clientName);
        assertEquals("a b c", cac.args);
        assertEquals(5000L, cac.delay);
        assertTrue(cac.disabled);
        assertEquals("/lib/ext.jar", cac.classpath);
        assertEquals("stop", cac.stopargs);
        assertEquals("remove", cac.uninstallargs);
    }

    @Test
    public void testConstructorNullArgs() {
        ClientAppConfig cac = new ClientAppConfig("com.example.X", "X", null, 0, false, null, null, null);
        assertNull(cac.args);
        assertNull(cac.classpath);
        assertNull(cac.stopargs);
        assertNull(cac.uninstallargs);
    }

    @Test
    public void testConstructorNegativeDelay() {
        ClientAppConfig cac = new ClientAppConfig("com.example.Y", "Y", "", -1000L, false);
        assertEquals(-1000L, cac.delay);
    }

    @Test
    public void testConstructorZeroDelay() {
        ClientAppConfig cac = new ClientAppConfig("com.example.Z", "Z", "", 0L, true);
        assertEquals(0L, cac.delay);
        assertTrue(cac.disabled);
    }

    @Test
    public void testEqualsSameClassAndArgs() {
        ClientAppConfig a = new ClientAppConfig("com.Foo", "Name", "args", 1000L, false);
        ClientAppConfig b = new ClientAppConfig("com.Foo", "Name", "args", 2000L, true);
        assertEquals(a, b);
    }

    @Test
    public void testEqualsDifferentClass() {
        ClientAppConfig a = new ClientAppConfig("com.Foo", "Name", "args", 1000L, false);
        ClientAppConfig b = new ClientAppConfig("com.Bar", "Name", "args", 1000L, false);
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsDifferentName() {
        ClientAppConfig a = new ClientAppConfig("com.Foo", "Name1", "args", 1000L, false);
        ClientAppConfig b = new ClientAppConfig("com.Foo", "Name2", "args", 1000L, false);
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsDifferentArgs() {
        ClientAppConfig a = new ClientAppConfig("com.Foo", "Name", "arg1", 1000L, false);
        ClientAppConfig b = new ClientAppConfig("com.Foo", "Name", "arg2", 1000L, false);
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsNull() {
        ClientAppConfig a = new ClientAppConfig("com.Foo", "Name", "args", 1000L, false);
        assertNotEquals(a, null);
    }

    @Test
    public void testEqualsSameObject() {
        ClientAppConfig a = new ClientAppConfig("com.Foo", "Name", "args", 1000L, false);
        assertEquals(a, a);
    }

    @Test
    public void testEqualsNullClassAndArgs() {
        ClientAppConfig a = new ClientAppConfig(null, null, null, 0, false);
        ClientAppConfig b = new ClientAppConfig(null, null, null, 0, false);
        assertEquals(a, b);
    }

    @Test
    public void testHashCodeConsistency() {
        ClientAppConfig a = new ClientAppConfig("com.Foo", "Name", "args", 1000L, false);
        ClientAppConfig b = new ClientAppConfig("com.Foo", "Name", "args", 2000L, true);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testHashCodeDifferentClass() {
        ClientAppConfig a = new ClientAppConfig("com.Foo", "N", "A", 0, false);
        ClientAppConfig b = new ClientAppConfig("com.Bar", "N", "A", 0, false);
        assertNotEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testGetClientAppsFromEmptyFile() throws IOException {
        File tmp = File.createTempFile("empty-clients", ".config");
        tmp.deleteOnExit();
        List<ClientAppConfig> apps = ClientAppConfig.getClientApps(tmp);
        assertNotNull(apps);
        assertTrue(apps.isEmpty());
    }

    @Test
    public void testGetClientAppsFromNonexistentFile() throws IOException {
        File tmp = new File("/tmp/nonexistent-" + System.nanoTime() + ".config");
        List<ClientAppConfig> apps = ClientAppConfig.getClientApps(tmp);
        assertNotNull(apps);
        assertTrue(apps.isEmpty());
    }

    @Test
    public void testGetClientAppsFromProperties() throws IOException {
        File tmp = File.createTempFile("clients-test", ".config");
        tmp.deleteOnExit();
        Properties props = new Properties();
        props.setProperty("clientApp.0.main", "com.example.App1");
        props.setProperty("clientApp.0.name", "App One");
        props.setProperty("clientApp.0.args", "--flag");
        props.setProperty("clientApp.0.delay", "30");
        props.setProperty("clientApp.0.startOnLoad", "true");
        props.setProperty("clientApp.1.main", "com.example.App2");
        props.setProperty("clientApp.1.name", "App Two");
        props.setProperty("clientApp.1.startOnLoad", "false");
        FileOutputStream fos = new FileOutputStream(tmp);
        props.store(fos, "test");
        fos.close();

        List<ClientAppConfig> apps = ClientAppConfig.getClientApps(tmp);
        assertEquals(2, apps.size());

        ClientAppConfig app0 = apps.get(0);
        assertEquals("com.example.App1", app0.className);
        assertEquals("App One", app0.clientName);
        assertEquals("--flag", app0.args);
        assertEquals(30000L, app0.delay);
        assertFalse(app0.disabled);

        ClientAppConfig app1 = apps.get(1);
        assertEquals("com.example.App2", app1.className);
        assertEquals("App Two", app1.clientName);
        assertTrue(app1.disabled);
    }

    @Test
    public void testGetClientAppsOnBootOverridesDelay() throws IOException {
        File tmp = File.createTempFile("clients-onboot", ".config");
        tmp.deleteOnExit();
        Properties props = new Properties();
        props.setProperty("clientApp.0.main", "com.example.BootApp");
        props.setProperty("clientApp.0.name", "Boot App");
        props.setProperty("clientApp.0.delay", "120");
        props.setProperty("clientApp.0.onBoot", "true");
        FileOutputStream fos = new FileOutputStream(tmp);
        props.store(fos, "test");
        fos.close();

        List<ClientAppConfig> apps = ClientAppConfig.getClientApps(tmp);
        assertEquals(1, apps.size());
        assertEquals("onBoot=true should force delay to 0", 0L, apps.get(0).delay);
    }

    @Test
    public void testGetClientAppsI2PTunnelSpecialDelay() throws IOException {
        File tmp = File.createTempFile("clients-i2ptunnel", ".config");
        tmp.deleteOnExit();
        Properties props = new Properties();
        props.setProperty("clientApp.0.main", "net.i2p.i2ptunnel.TunnelControllerGroup");
        props.setProperty("clientApp.0.name", "I2PTunnel");
        FileOutputStream fos = new FileOutputStream(tmp);
        props.store(fos, "test");
        fos.close();

        List<ClientAppConfig> apps = ClientAppConfig.getClientApps(tmp);
        assertEquals(1, apps.size());
        assertEquals("I2PTunnel should get negative delay for fast start", -1000L, apps.get(0).delay);
    }

    @Test
    public void testGetClientAppsDefaultDelay() throws IOException {
        File tmp = File.createTempFile("clients-default", ".config");
        tmp.deleteOnExit();
        Properties props = new Properties();
        props.setProperty("clientApp.0.main", "com.example.NormalApp");
        props.setProperty("clientApp.0.name", "Normal");
        FileOutputStream fos = new FileOutputStream(tmp);
        props.store(fos, "test");
        fos.close();

        List<ClientAppConfig> apps = ClientAppConfig.getClientApps(tmp);
        assertEquals(1, apps.size());
        assertEquals("Default delay should be 20000ms", 20000L, apps.get(0).delay);
    }

    @Test
    public void testGetClientAppsNonConsecutiveIndex() throws IOException {
        File tmp = File.createTempFile("clients-gap", ".config");
        tmp.deleteOnExit();
        Properties props = new Properties();
        props.setProperty("clientApp.0.main", "com.example.App0");
        props.setProperty("clientApp.0.name", "App0");
        // Skip index 1
        props.setProperty("clientApp.2.main", "com.example.App2");
        props.setProperty("clientApp.2.name", "App2");
        FileOutputStream fos = new FileOutputStream(tmp);
        props.store(fos, "test");
        fos.close();

        List<ClientAppConfig> apps = ClientAppConfig.getClientApps(tmp);
        assertEquals("Should stop at first missing consecutive index", 1, apps.size());
    }

    @Test
    public void testGetClientAppsClasspathAndStopargs() throws IOException {
        File tmp = File.createTempFile("clients-plugin", ".config");
        tmp.deleteOnExit();
        Properties props = new Properties();
        props.setProperty("clientApp.0.main", "com.example.Plugin");
        props.setProperty("clientApp.0.name", "Plugin");
        props.setProperty("clientApp.0.classpath", "/extra.jar,/lib.jar");
        props.setProperty("clientApp.0.stopargs", "stop now");
        props.setProperty("clientApp.0.uninstallargs", "remove all");
        FileOutputStream fos = new FileOutputStream(tmp);
        props.store(fos, "test");
        fos.close();

        List<ClientAppConfig> apps = ClientAppConfig.getClientApps(tmp);
        assertEquals(1, apps.size());
        assertEquals("/extra.jar,/lib.jar", apps.get(0).classpath);
        assertEquals("stop now", apps.get(0).stopargs);
        assertEquals("remove all", apps.get(0).uninstallargs);
    }
}
