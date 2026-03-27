package net.i2p.router.startup;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Tests for WorkingDir static utility methods.
 * Tests copyFile and migrateFileXML which are package-private and self-contained.
 */
public class WorkingDirTest {

    @Test
    public void testCopyFileBasic() throws IOException {
        File src = File.createTempFile("wdir-src", ".txt");
        src.deleteOnExit();
        File dst = File.createTempFile("wdir-dst", ".txt");
        dst.deleteOnExit();

        FileOutputStream fos = new FileOutputStream(src);
        fos.write("hello world".getBytes("UTF-8"));
        fos.close();

        assertTrue(WorkingDir.copyFile(src, dst));
        assertTrue(dst.exists());
        assertTrue(dst.length() > 0);
    }

    @Test
    public void testCopyFilePreservesTimestamp() throws IOException {
        File src = File.createTempFile("wdir-ts", ".txt");
        src.deleteOnExit();
        File dst = File.createTempFile("wdir-ts-dst", ".txt");
        dst.deleteOnExit();

        FileOutputStream fos = new FileOutputStream(src);
        fos.write("data".getBytes("UTF-8"));
        fos.close();

        long targetTime = 1600000000000L;
        src.setLastModified(targetTime);

        assertTrue(WorkingDir.copyFile(src, dst));
        assertEquals(targetTime, dst.lastModified());
    }

    @Test
    public void testCopyFileNonexistentSource() {
        File src = new File("/tmp/nonexistent-" + System.nanoTime() + ".txt");
        File dst = new File("/tmp/dst-" + System.nanoTime() + ".txt");
        dst.deleteOnExit();
        assertFalse("Copy of nonexistent file should return false", WorkingDir.copyFile(src, dst));
    }

    @Test
    public void testCopyFileOverwritesExisting() throws IOException {
        File src = File.createTempFile("wdir-over", ".txt");
        src.deleteOnExit();
        File dst = File.createTempFile("wdir-over-dst", ".txt");
        dst.deleteOnExit();

        FileOutputStream fos = new FileOutputStream(dst);
        fos.write("old content that is longer".getBytes("UTF-8"));
        fos.close();
        long oldLen = dst.length();

        fos = new FileOutputStream(src);
        fos.write("new".getBytes("UTF-8"));
        fos.close();

        assertTrue(WorkingDir.copyFile(src, dst));
        assertTrue(dst.length() < oldLen);
    }

    @Test
    public void testMigrateFileXMLSimple() throws IOException {
        File src = File.createTempFile("wdir-xml", ".xml");
        src.deleteOnExit();
        File dst = File.createTempFile("wdir-xml-out", ".xml");
        dst.deleteOnExit();

        FileOutputStream fos = new FileOutputStream(src);
        fos.write("<path>./eepsite/</path>\n".getBytes("UTF-8"));
        fos.close();

        WorkingDir.migrateFileXML(src, dst, "./eepsite/", "/new/path/eepsite/", null, null);
        assertTrue(dst.exists());
    }

    @Test
    public void testMigrateFileXMLDoubleReplace() throws IOException {
        File src = File.createTempFile("wdir-xml2", ".xml");
        src.deleteOnExit();
        File dst = File.createTempFile("wdir-xml2-out", ".xml");
        dst.deleteOnExit();

        FileOutputStream fos = new FileOutputStream(src);
        fos.write("aaa bbb\n".getBytes("UTF-8"));
        fos.close();

        WorkingDir.migrateFileXML(src, dst, "aaa", "XXX", "bbb", "YYY");
        assertTrue(dst.exists());
    }

    @Test
    public void testMigrateFileXMLNullSecondReplacement() throws IOException {
        File src = File.createTempFile("wdir-xml3", ".xml");
        src.deleteOnExit();
        File dst = File.createTempFile("wdir-xml3-out", ".xml");
        dst.deleteOnExit();

        FileOutputStream fos = new FileOutputStream(src);
        fos.write("hello world\n".getBytes("UTF-8"));
        fos.close();

        WorkingDir.migrateFileXML(src, dst, "world", "universe", null, null);
        assertTrue(dst.exists());
    }

    @Test
    public void testMigrateJettyXmlNonexistentSource() {
        File srcDir = new File("/tmp/nonexistent-src-" + System.nanoTime());
        File dstDir = new File("/tmp/nonexistent-dst-" + System.nanoTime());
        dstDir.deleteOnExit();
        // Should return true when source doesn't exist (nothing to do)
        assertTrue(WorkingDir.migrateJettyXml(srcDir, dstDir, "jetty.xml", "old", "new"));
    }
}
