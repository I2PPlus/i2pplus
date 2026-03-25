package net.i2p.addressbook;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConfigParserTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    // --- stripComments tests ---

    @Test
    public void stripCommentHash() {
        assertEquals("", ConfigParser.stripComments("# this is a comment"));
    }

    @Test
    public void stripCommentSemicolon() {
        assertEquals("", ConfigParser.stripComments("; comment line"));
    }

    @Test
    public void stripCommentSemicolonNoSpace() {
        assertEquals(";nospace", ConfigParser.stripComments(";nospace"));
    }

    @Test
    public void stripInlineComment() {
        assertEquals("key=value ", ConfigParser.stripComments("key=value # inline"));
    }

    @Test
    public void stripNoComment() {
        assertEquals("key=value", ConfigParser.stripComments("key=value"));
    }

    @Test
    public void stripEmptyLine() {
        assertEquals("", ConfigParser.stripComments(""));
    }

    @Test
    public void stripCommentHashAtEnd() {
        assertEquals("abc", ConfigParser.stripComments("abc#"));
    }

    // --- parse(File) tests ---

    @Test
    public void parseKeyValuePairs() throws IOException {
        File f = tmpDir.newFile("config.txt");
        writeFile(f, "host=example.i2p\nport=1234\n");
        Map<String, String> map = ConfigParser.parse(f);
        assertEquals(2, map.size());
        assertEquals("example.i2p", map.get("host"));
        assertEquals("1234", map.get("port"));
    }

    @Test
    public void parseCommentsAndBlankLines() throws IOException {
        File f = tmpDir.newFile("config.txt");
        writeFile(f, "# header comment\n; old-style\n\nkey=val\n# another\nkey2=val2\n");
        Map<String, String> map = ConfigParser.parse(f);
        assertEquals(2, map.size());
        assertEquals("val", map.get("key"));
        assertEquals("val2", map.get("key2"));
    }

    @Test
    public void parseKeyToLowercase() throws IOException {
        File f = tmpDir.newFile("config.txt");
        writeFile(f, "Host=Example.I2P\n");
        Map<String, String> map = ConfigParser.parse(f);
        assertEquals("Example.I2P", map.get("host"));
    }

    @Test
    public void parseInlineComments() throws IOException {
        File f = tmpDir.newFile("config.txt");
        writeFile(f, "url=http://x.i2p/hosts.txt # subscription url\n");
        Map<String, String> map = ConfigParser.parse(f);
        assertEquals("http://x.i2p/hosts.txt", map.get("url"));
    }

    @Test
    public void parseEmptyFile() throws IOException {
        File f = tmpDir.newFile("empty.txt");
        writeFile(f, "");
        Map<String, String> map = ConfigParser.parse(f);
        assertTrue(map.isEmpty());
    }

    @Test
    public void parseIgnoresLinesWithoutEquals() throws IOException {
        File f = tmpDir.newFile("config.txt");
        writeFile(f, "goodkey=goodval\nnoequals\nanother=ok\n");
        Map<String, String> map = ConfigParser.parse(f);
        assertEquals(2, map.size());
        assertEquals("goodval", map.get("goodkey"));
        assertEquals("ok", map.get("another"));
    }

    // --- parse(File, Map) tests ---

    @Test
    public void parseWithDefaults() throws IOException {
        File f = tmpDir.newFile("config.txt");
        writeFile(f, "host=example.i2p\n");
        Map<String, String> defaults = new HashMap<>();
        defaults.put("port", "8080");
        defaults.put("host", "default.i2p");
        Map<String, String> map = ConfigParser.parse(f, defaults);
        assertEquals("example.i2p", map.get("host"));
        assertEquals("8080", map.get("port"));
    }

    @Test
    public void parseWithDefaultsMigratesLocalToMaster() throws IOException {
        File f = tmpDir.newFile("config.txt");
        writeFile(f, "local_addressbook=/path/to/book\n");
        Map<String, String> defaults = new HashMap<>();
        Map<String, String> map = ConfigParser.parse(f, defaults);
        assertNull(map.get("local_addressbook"));
        assertEquals("/path/to/book", map.get("master_addressbook"));
    }

    // --- parseSubscriptions tests ---

    @Test
    public void parseSubscriptionsFromFile() throws IOException {
        File f = tmpDir.newFile("subscriptions.txt");
        writeFile(f, "http://a.i2p/hosts.txt\n# comment\nhttp://b.i2p/hosts.txt\n\n");
        List<String> defaults = new ArrayList<>();
        defaults.add("http://default.i2p/hosts.txt");
        List<String> result = ConfigParser.parseSubscriptions(f, defaults);
        assertEquals(2, result.size());
        assertEquals("http://a.i2p/hosts.txt", result.get(0));
        assertEquals("http://b.i2p/hosts.txt", result.get(1));
    }

    @Test
    public void parseSubscriptionsMissingFileUsesDefaults() throws IOException {
        File f = new File(tmpDir.getRoot(), "nonexistent.txt");
        List<String> defaults = new ArrayList<>();
        defaults.add("http://default.i2p/hosts.txt");
        List<String> result = ConfigParser.parseSubscriptions(f, defaults);
        assertEquals(defaults, result);
        assertTrue(f.exists());
    }

    // --- Round-trip test ---

    @Test
    public void roundTripMap() throws IOException {
        File f = tmpDir.newFile("roundtrip.txt");
        Map<String, String> original = new HashMap<>();
        original.put("alpha", "one");
        original.put("beta", "two");
        original.put("gamma", "three");
        ConfigParser.write(original, f);
        Map<String, String> parsed = ConfigParser.parse(f);
        assertEquals(original, parsed);
    }

    private void writeFile(File f, String content) throws IOException {
        FileWriter fw = new FileWriter(f);
        fw.write(content);
        fw.close();
    }
}
