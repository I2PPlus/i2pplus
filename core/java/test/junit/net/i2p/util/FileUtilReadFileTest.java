package net.i2p.util;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Test;

/**
 * The root-directory boundary {@link FileUtil#readFile(String, String, java.io.OutputStream)}
 * enforces on every path it is asked to serve.
 * <p>
 * The console and the local server hand this method a name taken from the request
 * and a directory to serve it from, so the check is what keeps
 * {@code ../something} from reading outside that directory - and what keeps a
 * sibling directory whose name merely starts with the root's name from passing as
 * the root itself. Upstream tightened the boundary to a path separator in
 * 53a5241460, merged in fa17f27fd3.
 * <p>
 * All trees are built under {@code java.io.tmpdir} and deleted afterwards, so a
 * failing test leaves nothing in the source tree.
 *
 * @since 0.9.72+
 */
public class FileUtilReadFileTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the instance carries only the temporary tree to clean up.
     *
     * @since 0.9.72+
     */
    public FileUtilReadFileTest() {}

    /** Written into every fixture file, so a served file is recognizable. */
    private static final byte[] CONTENT = "the file the console asked for".getBytes(StandardCharsets.UTF_8);

    /** The temporary tree the current test built, deleted after it runs. */
    private File _temp;

    /**
     * Creates a temporary directory for one test and remembers it for cleanup.
     *
     * @param hint a name prefix saying which case the tree belongs to
     * @return the new directory, never null
     * @throws IOException if the temporary directory could not be created
     */
    private File tempDir(String hint) throws IOException {
        _temp = Files.createTempDirectory("fileutil-" + hint).toFile();
        return _temp;
    }

    /** Remove the tree the test built, ignoring a tree that was never made. */
    @After
    public void removeTempTree() {
        if (_temp == null) {return;}
        deleteTree(_temp);
    }

    /**
     * Deletes a file or directory and everything below it.
     *
     * @param f what to delete, never null
     */
    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    /**
     * Writes one fixture file, making its parent directories.
     *
     * @param parent the directory the file goes in, created if missing
     * @param name the file's name
     * @return the file that was written
     * @throws IOException if the file could not be written
     */
    private static File write(File parent, String name) throws IOException {
        parent.mkdirs();
        File f = new File(parent, name);
        Files.write(f.toPath(), CONTENT);
        return f;
    }

    /**
     * Reads one path through the helper.
     *
     * @param path the name to serve, relative to the root
     * @param root the directory to serve it from
     * @return the bytes the helper wrote
     * @throws IOException if the helper refused or the file could not be read
     */
    private static byte[] serve(String path, File root) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileUtil.readFile(path, root.getAbsolutePath(), out);
        return out.toByteArray();
    }

    /**
     * A file sitting in the root is served, with its bytes intact.
     *
     * @throws IOException if the fixture file could not be written
     */
    @Test
    public void servesAFileInTheRoot() throws IOException {
        File root = tempDir("root");
        write(root, "inroot.txt");
        assertArrayEquals(CONTENT, serve("inroot.txt", root));
    }

    /**
     * A file in a subdirectory of the root is served too - the boundary is the
     * root, not the depth below it.
     *
     * @throws IOException if the fixture file could not be written
     */
    @Test
    public void servesAFileInASubdirectory() throws IOException {
        File root = tempDir("sub");
        write(new File(root, "deep"), "below.txt");
        assertArrayEquals(CONTENT, serve("deep/below.txt", root));
    }

    /**
     * A sibling directory whose name starts with the root's name is not the root.
     * At HEAD the check was a bare prefix test, so {@code docs-old} passed for a
     * {@code docs} root and the file was served from outside.
     *
     * @throws IOException if the fixture tree could not be written
     */
    @Test
    public void rejectsASiblingSharingTheRootsName() throws IOException {
        File parent = tempDir("sibling");
        File root = new File(parent, "docs");
        File sibling = new File(parent, "docs-old");
        write(root, "inside.txt");
        write(sibling, "outside.txt");
        try {
            serve("../docs-old/outside.txt", root);
            fail("a sibling directory sharing the root's prefix was served");
        } catch (FileNotFoundException expected) {
            // The finding: the request escaped the root the caller named.
        }
    }

    /**
     * A plain escape is refused, and the boundary did not become so strict that
     * it now refuses the paths it should keep.
     *
     * @throws IOException if the fixture tree could not be written
     */
    @Test
    public void rejectsAPathThatEscapesTheRoot() throws IOException {
        File parent = tempDir("escape");
        File root = new File(parent, "box");
        write(root, "inside.txt");
        write(parent, "outside.txt");
        try {
            serve("../outside.txt", root);
            fail("a path above the root was served");
        } catch (FileNotFoundException expected) {
            // As intended.
        }
    }

    /**
     * A file directly under a filesystem root is served. A canonical filesystem
     * root already ends with the separator, so appending another would put two
     * separators between the root and the file, and every path below it would be
     * refused. Skipped on a machine with no plain file at the top of a
     * filesystem.
     *
     * @throws IOException if the file under the filesystem root could not be
     *                     read
     */
    @Test
    public void servesAFileUnderAFileSystemRoot() throws IOException {
        File root = null;
        File found = null;
        for (File r : File.listRoots()) {
            File[] kids = r.listFiles();
            if (kids == null) {continue;}
            for (File k : kids) {
                if (k.isFile() && k.canRead()) {
                    root = r;
                    found = k;
                    break;
                }
            }
            if (root != null) {break;}
        }
        assumeTrue("no plain file at the top of any filesystem to serve", root != null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileUtil.readFile(found.getName(), root.getPath(), out);
        assertArrayEquals("the file under the filesystem root came back wrong",
                          Files.readAllBytes(found.toPath()), out.toByteArray());
    }
}
