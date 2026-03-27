package net.i2p.addressbook;

import static org.junit.Assert.*;

import net.i2p.client.naming.HostTxtEntry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class HostTxtParserTest {

    @Rule public TemporaryFolder tmpDir = new TemporaryFolder();

    private static final String DEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    // --- parse(String, false) with valid name=dest ---

    @Test
    public void parseSimpleEntry() {
        HostTxtEntry he = HostTxtParser.parse("example.i2p=" + DEST_A, false);
        assertNotNull(he);
        assertEquals("example.i2p", he.getName());
        assertEquals(DEST_A, he.getDest());
        assertNull(he.getProps());
    }

    @Test
    public void parseNameLowercased() {
        HostTxtEntry he = HostTxtParser.parse("Example.I2P=" + DEST_A, false);
        assertNotNull(he);
        assertEquals("example.i2p", he.getName());
    }

    @Test
    public void parseWithWhitespaceTrimmed() {
        HostTxtEntry he = HostTxtParser.parse("  example.i2p = " + DEST_A + "  ", false);
        assertNotNull(he);
        assertEquals("example.i2p", he.getName());
        assertEquals(DEST_A, he.getDest());
    }

    // --- parse(String, false) with comments ---

    @Test
    public void parseCommentLineHashReturnsNull() {
        assertNull(HostTxtParser.parse("# this is a comment", false));
    }

    @Test
    public void parseCommentLineSemicolonReturnsNull() {
        assertNull(HostTxtParser.parse("; old style comment", false));
    }

    @Test
    public void parseInlineComment() {
        HostTxtEntry he = HostTxtParser.parse("example.i2p=" + DEST_A + " # comment", false);
        assertNotNull(he);
        assertEquals("example.i2p", he.getName());
        assertEquals(DEST_A, he.getDest());
    }

    // --- parse(String, false) with properties ---

    @Test
    public void parseWithProperties() {
        HostTxtEntry he = HostTxtParser.parse("example.i2p=" + DEST_A + "#!key1=val1", false);
        assertNotNull(he);
        assertEquals("example.i2p", he.getName());
        assertEquals(DEST_A, he.getDest());
        Properties props = he.getProps();
        assertNotNull(props);
        assertEquals("val1", props.getProperty("key1"));
    }

    @Test
    public void parseWithMultipleProperties() {
        HostTxtEntry he = HostTxtParser.parse("example.i2p=" + DEST_A + "#!key1=val1#key2=val2", false);
        assertNotNull(he);
        Properties props = he.getProps();
        assertNotNull(props);
        assertEquals("val1", props.getProperty("key1"));
        assertEquals("val2", props.getProperty("key2"));
    }

    @Test
    public void parseCommandOnlyReturnsNullWhenNotAllowed() {
        assertNull(HostTxtParser.parse("#!action=remove", false));
    }

    // --- parse(String, false) with empty/invalid lines ---

    @Test
    public void parseEmptyLineReturnsNull() {
        assertNull(HostTxtParser.parse("", false));
    }

    @Test
    public void parseNoEqualsReturnsNull() {
        assertNull(HostTxtParser.parse("noequalshere", false));
    }

    @Test
    public void parseEmptyNameReturnsNull() {
        assertNull(HostTxtParser.parse("=" + DEST_A, false));
    }

    @Test
    public void parseEmptyDestReturnsNull() {
        assertNull(HostTxtParser.parse("example.i2p=", false));
    }

    // --- parse(String, true) for command-only lines ---

    @Test
    public void parseCommandOnlyAllowed() {
        HostTxtEntry he = HostTxtParser.parse("#!action=remove#dest=" + DEST_A + "#name=example.i2p", true);
        assertNotNull(he);
        assertNull(he.getName());
        assertNull(he.getDest());
        Properties props = he.getProps();
        assertNotNull(props);
        assertEquals("remove", props.getProperty("action"));
        assertEquals("example.i2p", props.getProperty("name"));
    }

    @Test
    public void parseCommandOnlyDisallowed() {
        assertNull(HostTxtParser.parse("#!action=remove#!name=foo", false));
    }

    @Test
    public void parseNormalEntryAllowedWhenCommandOnlyTrue() {
        HostTxtEntry he = HostTxtParser.parse("example.i2p=" + DEST_A, true);
        assertNotNull(he);
        assertEquals("example.i2p", he.getName());
        assertEquals(DEST_A, he.getDest());
    }

    // --- parse(File) round-trip ---

    @Test
    public void roundTripFile() throws IOException {
        File f = tmpDir.newFile("hosts.txt");
        Map<String, HostTxtEntry> original = new HashMap<>();
        original.put("alpha.i2p", new HostTxtEntry("alpha.i2p", DEST_A));
        original.put("beta.i2p", new HostTxtEntry("beta.i2p", DEST_B));
        HostTxtParser.write(original, f);
        Map<String, HostTxtEntry> parsed = HostTxtParser.parse(f);
        assertEquals(2, parsed.size());
        assertNotNull(parsed.get("alpha.i2p"));
        assertNotNull(parsed.get("beta.i2p"));
        assertEquals(DEST_A, parsed.get("alpha.i2p").getDest());
        assertEquals(DEST_B, parsed.get("beta.i2p").getDest());
        assertEquals("alpha.i2p", parsed.get("alpha.i2p").getName());
        assertEquals("beta.i2p", parsed.get("beta.i2p").getName());
    }

    @Test
    public void parseFileSkipsCommentLines() throws IOException {
        File f = tmpDir.newFile("hosts.txt");
        writeFile(f, "# comment\n; old comment\nexample.i2p=" + DEST_A + "\n");
        Map<String, HostTxtEntry> parsed = HostTxtParser.parse(f);
        assertEquals(1, parsed.size());
        assertEquals("example.i2p", parsed.get("example.i2p").getName());
    }

    @Test
    public void parseFileEmptyReturnsEmptyMap() throws IOException {
        File f = tmpDir.newFile("empty.txt");
        writeFile(f, "");
        Map<String, HostTxtEntry> parsed = HostTxtParser.parse(f);
        assertTrue(parsed.isEmpty());
    }

    private void writeFile(File f, String content) throws IOException {
        FileWriter fw = new FileWriter(f);
        fw.write(content);
        fw.close();
    }
}
