package net.i2p.router.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.router.news.NewsEntry;
import net.i2p.router.news.NewsManager;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that a news entry's title reaches the console as text.
 * <p>
 * The title is written into the page by the news renderer and by the sidebar
 * heading list. It used to be written as received, so whoever holds a news
 * signing key put live markup in the operator's console: a {@code <style>} that
 * restyles every page showing the news, or a {@code <meta http-equiv=refresh>}
 * that navigates it. The console CSP keeps inline script out, which is the only
 * reason this was not worse. The entry's content is markup by design and stays
 * unescaped; these pin both halves of that distinction.
 *
 * @since 0.9.72+
 */
public class NewsTitleEscapingTest {

    /** Markup a news signer can put in an entry title. */
    private static final String MARKUP = "<style>body{color:red}</style>" +
                                         "<meta http-equiv=refresh content=\"0;url=/peers\">";

    /** Builds and tears down its own news manager and entry for each case. */
    public NewsTitleEscapingTest() {}

    /** Context the news manager is registered in. */
    private I2PAppContext _ctx;

    /** The manager registered for this test, unregistered afterwards. */
    private NewsManager _nmgr;

    /**
     * Registers one entry whose title is markup, skipping the class when no
     * client app manager is available to register it in.
     */
    @Before
    public void setUp() {
        _ctx = I2PAppContext.getGlobalContext();
        ClientAppManager cmgr = _ctx.clientAppManager();
        Assume.assumeTrue("No client app manager", cmgr != null);
        _nmgr = new NewsManager(_ctx, cmgr, null);
        // startup() loads the persisted news and registers the manager
        _nmgr.startup();
        Assume.assumeTrue("Another news manager is already registered",
                          cmgr.getRegisteredApp(NewsManager.APP_NAME) == _nmgr);
        NewsEntry entry = new NewsEntry();
        entry.id = "news-title-escaping";
        entry.title = MARKUP;
        entry.content = "<b>body</b>";
        entry.updated = _ctx.clock().now();
        _nmgr.addEntries(Collections.singletonList(entry));
    }

    /** Takes the news manager back out of the context. */
    @After
    public void tearDown() {
        ClientAppManager cmgr = _ctx.clientAppManager();
        if (cmgr != null) {cmgr.unregister(_nmgr);}
    }

    /**
     * The title's markup is escaped, so no element from it is live in the page.
     */
    @Test
    public void titleMarkupIsEscaped() {
        String out = NewsFeedHelper.getEntries(_ctx, 0, 1, 0);
        assertTrue("the title reached the page unescaped", out.contains("&lt;style&gt;"));
        assertFalse("a style element from the title is live", out.contains("<style>"));
        assertFalse("a refresh from the title is live", out.contains("<meta http-equiv"));
    }

    /**
     * The entry body is markup by design and is still written as markup.
     */
    @Test
    public void entryContentStaysMarkup() {
        String out = NewsFeedHelper.getEntries(_ctx, 0, 1, 0);
        assertTrue("the entry body was escaped as well", out.contains("<b>body</b>"));
    }
}
