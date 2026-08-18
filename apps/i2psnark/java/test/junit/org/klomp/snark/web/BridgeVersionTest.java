package org.klomp.snark.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the I2PSnark Bridge extension version helpers.
 */
public class BridgeVersionTest {

    private static byte[] xpiWith(String manifestJson) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifestJson.getBytes("UTF-8"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("background.js"));
            zos.write("".getBytes("UTF-8"));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    public void testReadXpiVersion() throws IOException {
        byte[] xpi = xpiWith("{\"name\":\"I2PSnark Magnet Bridge\",\"version\":\"0.1.1\"}");
        assertEquals("0.1.1", BridgeVersion.readXpiVersion(new ByteArrayInputStream(xpi)));
    }

    @Test
    public void testReadXpiVersionPrettyPrinted() throws IOException {
        byte[] xpi = xpiWith("{\n  \"name\": \"I2PSnark Magnet Bridge\",\n  \"version\": \"0.1.1\"\n}");
        assertEquals("0.1.1", BridgeVersion.readXpiVersion(new ByteArrayInputStream(xpi)));
    }

    @Test
    public void testReadXpiVersionMissingManifest() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("background.js"));
            zos.write("".getBytes("UTF-8"));
            zos.closeEntry();
        }
        assertNull(BridgeVersion.readXpiVersion(new ByteArrayInputStream(baos.toByteArray())));
    }

    @Test
    public void testReadXpiVersionNotAZip() {
        assertNull(BridgeVersion.readXpiVersion(new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
    }

    @Test
    public void testReadXpiVersionNull() {
        assertNull(BridgeVersion.readXpiVersion(null));
    }

    @Test
    public void testExtractVersion() {
        assertEquals("0.1.1", BridgeVersion.extractVersion("{\"version\": \"0.1.1\"}"));
        assertEquals("0.1.1", BridgeVersion.extractVersion("{\"version\":\"0.1.1\"}"));
        assertEquals("0.1.1", BridgeVersion.extractVersion("{\"name\":\"x\",\"version\":\"0.1.1\",\"id\":\"y\"}"));
        assertNull(BridgeVersion.extractVersion("{\"name\":\"x\"}"));
        assertNull(BridgeVersion.extractVersion("{\"version\":\"\"}"));
        assertNull(BridgeVersion.extractVersion(null));
    }

    @Test
    public void testCompareNewer() {
        assertTrue(BridgeVersion.compare("0.1.0", "0.1.1") < 0);
        assertTrue(BridgeVersion.compare("0.1.1", "0.1.0") > 0);
        assertTrue(BridgeVersion.compare("0.1.9", "0.1.10") < 0);
        assertTrue(BridgeVersion.compare("0.9.99", "1.0") < 0);
        assertTrue(BridgeVersion.compare("0.2", "0.1.9") > 0);
    }

    @Test
    public void testCompareEqual() {
        assertEquals(0, BridgeVersion.compare("0.1.1", "0.1.1"));
        assertEquals(0, BridgeVersion.compare("0.1", "0.1.0"));
        assertEquals(0, BridgeVersion.compare("0.1.1-beta", "0.1.1"));
    }

    @Test
    public void testCompareDegenerate() {
        assertEquals(0, BridgeVersion.compare(null, null));
        assertTrue(BridgeVersion.compare(null, "1.0") < 0);
        assertEquals(0, BridgeVersion.compare("", ""));
        assertEquals(0, BridgeVersion.compare("foo", "bar"));
        assertTrue(BridgeVersion.compare("0.1", null) > 0);
    }

    @Test
    public void testIsUpdateAvailable() {
        assertTrue(BridgeVersion.isUpdateAvailable("0.1.0", "0.1.1"));
        assertFalse(BridgeVersion.isUpdateAvailable("0.1.1", "0.1.1"));
        assertFalse(BridgeVersion.isUpdateAvailable("0.1.2", "0.1.1"));
        assertFalse(BridgeVersion.isUpdateAvailable(null, "0.1.1"));
        assertFalse(BridgeVersion.isUpdateAvailable("0.1.0", null));
    }
}