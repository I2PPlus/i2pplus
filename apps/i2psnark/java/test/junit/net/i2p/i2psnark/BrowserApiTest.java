package net.i2p.i2psnark;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.Set;

import org.junit.Test;
import static org.junit.Assert.*;

import org.klomp.snark.SnarkManager;
import org.klomp.snark.web.BrowserApiInstaller;
import org.klomp.snark.web.I2PSnarkServlet;

/**
 * Tests for the browser API (nonce-free magnet/torrent add): the allowed-hosts
 * allowlist parsing, the /_add authorization decision, the info-hash
 * validators, and the Linux .desktop / mimeapps.list handler registration.
 *
 * @since 0.9.71+
 */
public class BrowserApiTest {

    // ----- /_add authorization decision -----

    @Test
    public void testAuthorizationRequiresEnabled() {
        assertFalse(I2PSnarkServlet.browserApiAuthorized(false, true, null, null));
        assertFalse(I2PSnarkServlet.browserApiAuthorized(false, true, "k", "k"));
    }

    @Test
    public void testAuthorizationAllowsAllowedHostWhenEnabled() {
        assertTrue(I2PSnarkServlet.browserApiAuthorized(true, true, null, null));
        assertTrue(I2PSnarkServlet.browserApiAuthorized(true, true, "wrong", "k"));
    }

    @Test
    public void testAuthorizationRejectsUnknownHostWithoutKey() {
        assertFalse(I2PSnarkServlet.browserApiAuthorized(true, false, null, null));
        assertFalse(I2PSnarkServlet.browserApiAuthorized(true, false, "", "k"));
    }

    @Test
    public void testAuthorizationAcceptsMatchingKey() {
        assertTrue(I2PSnarkServlet.browserApiAuthorized(true, false, "SecretKey", "SecretKey"));
        assertFalse(I2PSnarkServlet.browserApiAuthorized(true, false, "SecretKey", "secretkey"));
        assertFalse(I2PSnarkServlet.browserApiAuthorized(true, false, "SecretKey", "otherkey"));
    }

    @Test
    public void testAuthorizationRejectsWhenNoKeyConfigured() {
        assertFalse(I2PSnarkServlet.browserApiAuthorized(true, false, "anything", null));
        assertFalse(I2PSnarkServlet.browserApiAuthorized(true, false, "anything", ""));
    }

    // ----- info hash validation -----

    @Test
    public void testValidHexInfoHash() {
        assertTrue(I2PSnarkServlet.isValidHexInfoHash("0123456789abcdef0123456789abcdef01234567"));
        assertFalse(I2PSnarkServlet.isValidHexInfoHash("0123456789abcdef0123456789abcdef0123456"));
        assertFalse(I2PSnarkServlet.isValidHexInfoHash("0123456789abcdef0123456789abcdef0123456g"));
        assertFalse(I2PSnarkServlet.isValidHexInfoHash(""));
        assertFalse(I2PSnarkServlet.isValidHexInfoHash(null));
    }

    @Test
    public void testValidBase32InfoHash() {
        assertTrue(I2PSnarkServlet.isValidBase32InfoHash("abcdefghijklmnopqrstuvwxyz234567"));
        assertFalse(I2PSnarkServlet.isValidBase32InfoHash("abcdefghijklmnopqrstuvwxyz23456"));
        assertFalse(I2PSnarkServlet.isValidBase32InfoHash("abcdefghijklmnopqrstuvwxyz234568"));
        assertFalse(I2PSnarkServlet.isValidBase32InfoHash("abcdefghijklmnopqrstuvwxyz234560"));
        assertFalse(I2PSnarkServlet.isValidBase32InfoHash(""));
    }

    @Test
    public void testValidV2InfoHash() {
        assertTrue(I2PSnarkServlet.isValidV2InfoHash("1220" + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertFalse(I2PSnarkServlet.isValidV2InfoHash("1221" + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertFalse(I2PSnarkServlet.isValidV2InfoHash("1220" + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde"));
        assertFalse(I2PSnarkServlet.isValidV2InfoHash("0123456789abcdef0123456789abcdef01234567"));
    }

    // ----- allowed hosts parsing -----

    @Test
    public void testIsLoopbackHost() {
        assertTrue(SnarkManager.isLoopbackHost("127.0.0.1"));
        assertTrue(SnarkManager.isLoopbackHost("127.99.1.1"));
        assertTrue(SnarkManager.isLoopbackHost("::1"));
        assertTrue(SnarkManager.isLoopbackHost("0:0:0:0:0:0:0:1"));
        assertTrue(SnarkManager.isLoopbackHost("localhost"));
        assertFalse(SnarkManager.isLoopbackHost("10.0.0.1"));
        assertFalse(SnarkManager.isLoopbackHost("192.168.1.5"));
        assertFalse(SnarkManager.isLoopbackHost("example.com"));
        assertFalse(SnarkManager.isLoopbackHost(null));
        assertFalse(SnarkManager.isLoopbackHost(""));
    }

    @Test
    public void testResolveBrowserApiHostsSkipsLoopbackAndGarbage() throws Exception {
        Set<InetAddress> hosts =
                SnarkManager.resolveBrowserApiHosts("10.0.0.1, 192.168.1.5 ,localhost, 127.0.0.1, not.a.host.invalid");
        assertEquals(2, hosts.size());
        assertTrue(hosts.contains(InetAddress.getByName("10.0.0.1")));
        assertTrue(hosts.contains(InetAddress.getByName("192.168.1.5")));
    }

    @Test
    public void testResolveBrowserApiHostsEmpty() {
        assertTrue(SnarkManager.resolveBrowserApiHosts(null).isEmpty());
        assertTrue(SnarkManager.resolveBrowserApiHosts("").isEmpty());
        assertTrue(SnarkManager.resolveBrowserApiHosts("  , , ").isEmpty());
    }

    // ----- Linux handler registration -----

    @Test
    public void testInstallLinuxWritesDesktopFile() throws Exception {
        File home = Files.createTempDirectory("snarkbrowser").toFile();
        try {
            BrowserApiInstaller.installLinux("http://127.0.0.1:7657/i2psnark", home);
            File desktop = new File(home, ".local/share/applications/i2psnark-browserapi.desktop");
            assertTrue(desktop.isFile());
            String content = new String(Files.readAllBytes(desktop.toPath()), "UTF-8");
            assertTrue(content.contains("[Desktop Entry]"));
            assertTrue(content.contains("Type=Application"));
            assertTrue(content.contains("Exec="));
            assertTrue(content.contains("--url \"http://127.0.0.1:7657/i2psnark\""));
            assertTrue(content.contains("org.klomp.snark.MagnetHandler"));
            assertTrue(content.contains("%u"));
            assertTrue(content.contains("MimeType=x-scheme-handler/magnet;application/x-bittorrent;"));
            assertFalse(content.contains("NoDisplay"));
        } finally {
            deleteRecursive(home);
        }
    }

    @Test
    public void testInstallLinuxRegistersMimeapps() throws Exception {
        File home = Files.createTempDirectory("snarkbrowser").toFile();
        try {
            File mimeapps = new File(home, ".config/mimeapps.list");
            Files.createDirectories(mimeapps.getParentFile().toPath());
            Files.write(mimeapps.toPath(),
                "[Default Applications]\n".getBytes("UTF-8"));
            BrowserApiInstaller.installLinux("http://127.0.0.1:7657/i2psnark", home);
            String content = new String(Files.readAllBytes(mimeapps.toPath()), "UTF-8");
            assertTrue(content.contains("x-scheme-handler/magnet=i2psnark-browserapi.desktop"));
            assertTrue(content.contains("application/x-bittorrent=i2psnark-browserapi.desktop"));
        } finally {
            deleteRecursive(home);
        }
    }

    @Test
    public void testInstallLinuxPreservesOtherMimeappsSections() throws Exception {
        File home = Files.createTempDirectory("snarkbrowser").toFile();
        try {
            File mimeapps = new File(home, ".config/mimeapps.list");
            Files.createDirectories(mimeapps.getParentFile().toPath());
            Files.write(mimeapps.toPath(),
                ("[Default Applications]\n" +
                 "text/plain=gedit.desktop\n" +
                 "x-scheme-handler/magnet=other-app.desktop\n" +
                 "\n" +
                 "[Added Associations]\n" +
                 "text/plain=gedit.desktop\n").getBytes("UTF-8"));
            BrowserApiInstaller.installLinux("http://127.0.0.1:7657/i2psnark", home);
            String content = new String(Files.readAllBytes(mimeapps.toPath()), "UTF-8");
            assertTrue(content.contains("text/plain=gedit.desktop"));
            assertTrue(content.contains("[Added Associations]"));
            assertTrue(content.contains("x-scheme-handler/magnet=i2psnark-browserapi.desktop"));
            assertFalse(content.contains("x-scheme-handler/magnet=other-app.desktop"));
        } finally {
            deleteRecursive(home);
        }
    }

    // ----- magnet handler page -----

    @Test
    public void testIsFirefoxFamilyUserAgent() {
        assertTrue(I2PSnarkServlet.isFirefoxFamilyUserAgent("Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"));
        assertTrue(I2PSnarkServlet.isFirefoxFamilyUserAgent("Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"));
        assertTrue(I2PSnarkServlet.isFirefoxFamilyUserAgent("Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0 LibreWolf/115.0.0"));
        assertTrue(I2PSnarkServlet.isFirefoxFamilyUserAgent("Mozilla/5.0 (Android; rv:128.0) Gecko/128.0 Firefox/128.0"));
        assertFalse(I2PSnarkServlet.isFirefoxFamilyUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"));
        assertFalse(I2PSnarkServlet.isFirefoxFamilyUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"));
        assertFalse(I2PSnarkServlet.isFirefoxFamilyUserAgent(null));
        assertFalse(I2PSnarkServlet.isFirefoxFamilyUserAgent(""));
    }

    @Test
    public void testIsWindowsUserAgent() {
        assertTrue(I2PSnarkServlet.isWindowsUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"));
        assertTrue(I2PSnarkServlet.isWindowsUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Edg/124.0.0.0"));
        assertTrue(I2PSnarkServlet.isWindowsUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"));
        assertFalse(I2PSnarkServlet.isWindowsUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"));
        assertFalse(I2PSnarkServlet.isWindowsUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"));
        assertFalse(I2PSnarkServlet.isWindowsUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 LibreWolf/115.0.0"));
        assertFalse(I2PSnarkServlet.isWindowsUserAgent(null));
        assertFalse(I2PSnarkServlet.isWindowsUserAgent(""));
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
