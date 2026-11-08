package i2p.susi.dns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import net.i2p.data.Destination;

import org.junit.Test;

/**
 * Tests the static indexed blacklist lookup that replaced the per-bean
 * linear scan.
 *
 * <p>Background: {@code isBlacklisted()} split the whole blacklist content
 * into a fresh instance list for every new bean and then scanned it linearly
 * per host. The index is shared across beans, rebuilt only when the content
 * changes, and holds every trimmed line, so membership matches the old scan
 * — including blanks and comments — while the per-host check is a set lookup.
 * The bulk-list overload resolves a host's alternate forms from the
 * destination the caller already holds, with no naming-service lookups.
 *
 * @since 0.9.72+
 */
public class BlacklistBeanLinesTest {

    /** A valid destination, in base64 form. */
    private static final String DEST_B64 =
        "-KR6qyfPWXoN~F3UzzYSMIsaRy4quickbrownfoxXSzUQXQdi2Af1TV2UMH3PpPuNu-GwrqihwmLSkPFg4fv4yQQY3E10VeQVuI67dn5vlan3NGMsjqxoXTSHHt7C3nX3szXK90JSoO~tRMDl1xyqtKm94-RpIyNcLXofd0H6b02683CQIjb-7JiCpDD0zharm6SU54rhdisIUVXpi1xYgg2pKVpssL~KCp7RAGzpt2rSgz~RHFsecqGBeFwJdiko-6CYW~tcBcigM8ea57LK7JjCFVhOoYTqgk95AG04-hfehnmBtuAFHWklFyFh88x6mS9sbVPvi-am4La0G0jvUJw9a3wQ67jMr6KWQ~w~bFe~FDqoZqVXl8t88qHPIvXelvWw2Y8EMSF5PJhWw~AZfoWOA5VQVYvcmGzZIEKtFGE7bgQf3rFtJ2FAtig9XXBsoLisHbJgeVb29Ew5E7bkwxvEe9NYkIqvrKvUAt1i55we0Nkt6xlEdhBqg6xXOyIAAAA";

    /**
     * Build a bean whose content is the given lines, without touching the
     * blacklist file.
     *
     * @param entries newline-joined blacklist lines
     * @return a bean holding the content
     */
    private static BlacklistBean beanWithContent(String entries) {
        BlacklistBean bean = new BlacklistBean();
        bean.setContent(entries);
        return bean;
    }

    @Test
    public void testLinesForTrimsAndFoldsCase() {
        Set<String> lines = BlacklistBean.linesFor("Foo.i2p\n  BAR.i2p  \nbaz.i2p\n");
        assertTrue(lines.contains("foo.i2p"));
        assertTrue(lines.contains("bar.i2p"));
        assertTrue(lines.contains("baz.i2p"));
        assertFalse(lines.contains("FOO.i2P"));
    }

    @Test
    public void testLinesForKeepsBlanksAndComments() {
        // The old scan compared every trimmed line, including blanks and
        // comments, so the index must hold them too.
        Set<String> lines = BlacklistBean.linesFor("foo.i2p\n\n# a comment\n");
        assertTrue(lines.contains("foo.i2p"));
        assertTrue(lines.contains(""));
        assertTrue(lines.contains("# a comment"));
    }

    @Test
    public void testLinesForReusesSetForSameContent() {
        String content = "foo.i2p\nbar.i2p\n";
        assertSame("the shared index is reused while the content is unchanged",
                   BlacklistBean.linesFor(content), BlacklistBean.linesFor(content));
        Set<String> lines = BlacklistBean.linesFor(content + "baz.i2p\n");
        assertTrue(lines.contains("foo.i2p"));
        assertTrue(lines.contains("baz.i2p"));
        assertEquals(3, lines.size());
    }

    @Test
    public void testIsBlacklistedExactMatch() {
        BlacklistBean bean = beanWithContent("Foo.i2p\nbar.i2p\n");
        assertTrue(bean.isBlacklisted("foo.i2p"));
        assertTrue(bean.isBlacklisted("FOO.i2p"));
        assertTrue(bean.isBlacklisted("  Bar.i2p  "));
        assertFalse(bean.isBlacklisted("other.i2p"));
        assertFalse(bean.isBlacklisted(null));
    }

    @Test
    public void testIsBlacklistedSharesIndexAcrossBeans() {
        BlacklistBean first = beanWithContent("shared.i2p\n");
        BlacklistBean second = beanWithContent("shared.i2p\n");
        assertTrue(first.isBlacklisted("shared.i2p"));
        assertTrue(second.isBlacklisted("shared.i2p"));
        assertFalse(second.isBlacklisted("other.i2p"));
    }

    @Test
    public void testIsBlacklistedWithKnownDestination() throws Exception {
        Destination dest = new Destination(DEST_B64);
        String b32 = dest.calculateHash().toBase32() + ".b32.i2p";
        BlacklistBean bean = beanWithContent(dest.toBase64() + "\n");
        assertTrue("the destination's b64 form must match",
                   bean.isBlacklisted("somehost.i2p", dest));

        BlacklistBean byB32 = beanWithContent(b32 + "\n");
        assertTrue("the destination's b32 form must match",
                   byB32.isBlacklisted("somehost.i2p", dest));

        BlacklistBean byName = beanWithContent("Somehost.i2p\n");
        assertTrue("the given name itself must match",
                   byName.isBlacklisted("somehost.i2p", dest));

        BlacklistBean clean = beanWithContent("other.i2p\n");
        assertFalse("a listed unrelated host must not match",
                    clean.isBlacklisted("somehost.i2p", dest));
    }

    @Test
    public void testIsBlacklistedWithKnownDestinationNullFallback() {
        BlacklistBean bean = beanWithContent("foo.i2p\n");
        assertTrue("a null destination still matches the exact name",
                   bean.isBlacklisted("foo.i2p", null));
    }
}
