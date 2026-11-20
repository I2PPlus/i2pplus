package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import i2p.susi.webmail.pop3.POP3MailBox;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import net.i2p.I2PAppContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * What a refresh asks the server for when a message is bigger than the body this
 * tree will store.
 *
 * <p>The defect: a message the server listed above the reader's ceiling was fetched
 * in full anyway, the reader refused it at that ceiling, and the message stayed a
 * message with no body - so the next refresh fetched it again, and the one after
 * that, forever. Each refresh paid the transfer and the temporary file for bytes it
 * threw away, and on a server that cannot send headers only the oversized message
 * also sat in the batch ahead of the smaller ones behind it.
 *
 * <p>The rule being pinned: a body past the ceiling is not fetched, and the header
 * still is, so the message stays listed with its subject and size. Where the server
 * cannot answer TOP, even a header request downloads the whole body, so there the
 * message is left out of the refresh instead.
 *
 * <p>No POP3 server: the mailbox is a mock handing out one UIDL and the size the
 * server listed for it, and the requests a refresh queues are captured where the
 * mailbox would be handed them.
 *
 * @since 0.9.72+
 */
public class MailCacheOversizeFetchTest {

    /** The one message every case lists. */
    private static final String UIDL = "uidl-1";

    /** A message well past the fetch-all threshold but inside the reader's ceiling. */
    private static final int ORDINARY_SIZE = 4 * 1024 * 1024;

    /** Scratch config and cache directories for the mailbox the fixture builds. */
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * A fixture group for what a refresh asks the server for.
     *
     * @since 0.9.72+
     */
    public MailCacheOversizeFetchTest() {}

    /**
     * The defect: a message bigger than the reader will store is still listed, and
     * its header is still worth having, so the refresh asks for the header and stops
     * there rather than downloading a body it would throw away and fetch again next
     * time.
     *
     * @throws Exception if the scratch directory or the message cache cannot be made
     */
    @Test
    public void anOversizedMessageIsAskedForByHeaderOnly() throws Exception {
        POP3MailBox mailbox = mailbox(POP3MailBox.MAX_BODY_BYTES + 1, true);
        List<POP3MailBox.FetchRequest> queued = captureFetches(mailbox);

        cache(mailbox).getMail(MailCache.FetchMode.ALL);

        assertEquals("the refresh queued something besides the header", 1, queued.size());
        assertTrue("the refresh still asked the server for the whole body",
                   queued.get(0).getHeaderOnly());
    }

    /**
     * On a server that cannot send headers on their own, a header request is answered
     * with the whole message, so even listing an oversized message costs the download
     * that gets refused. There it is left out of the refresh entirely.
     *
     * @throws Exception if the scratch directory or the message cache cannot be made
     */
    @Test
    public void anOversizedMessageIsLeftOutWhereHeadersCannotBeFetchedAlone() throws Exception {
        POP3MailBox mailbox = mailbox(POP3MailBox.MAX_BODY_BYTES + 1, false);
        captureFetches(mailbox);

        cache(mailbox).getMail(MailCache.FetchMode.ALL);

        verify(mailbox, never()).getBodies(any());
    }

    /**
     * The ordinary message beside it, so the ceiling is not read as a new fetch
     * threshold: a message above the size a header-only refresh fetches in full is
     * still fetched in full by a refresh that asks for bodies.
     *
     * @throws Exception if the scratch directory or the message cache cannot be made
     */
    @Test
    public void anOrdinaryMessageIsStillFetchedInFull() throws Exception {
        POP3MailBox mailbox = mailbox(ORDINARY_SIZE, true);
        List<POP3MailBox.FetchRequest> queued = captureFetches(mailbox);

        cache(mailbox).getMail(MailCache.FetchMode.ALL);

        assertEquals("the refresh queued something besides the one message", 1, queued.size());
        assertFalse("the refresh refused a message inside the ceiling",
                    queued.get(0).getHeaderOnly());
    }

    /**
     * The ceiling itself, so the number the refresh applies is the number the reader
     * enforces: the last body that fits is fetched, one byte more is not, and a
     * message the server listed no size for is fetched as before.
     */
    @Test
    public void theCeilingIsTheOnesTheReaderEnforces() {
        assertTrue(MailCache.bodyWorthFetching(POP3MailBox.MAX_BODY_BYTES));
        assertFalse(MailCache.bodyWorthFetching((long) POP3MailBox.MAX_BODY_BYTES + 1));
        assertTrue(MailCache.bodyWorthFetching(0));
    }

    /**
     * Records the fetch requests a refresh hands to the mailbox.
     *
     * @param mailbox the mailbox to watch
     * @return the requests, filled in when the refresh sends them
     */
    @SuppressWarnings("unchecked")
    private static List<POP3MailBox.FetchRequest> captureFetches(POP3MailBox mailbox) {
        List<POP3MailBox.FetchRequest> queued = new ArrayList<>();
        doAnswer(inv -> {
            queued.addAll((Collection<POP3MailBox.FetchRequest>) inv.getArgument(0));
            return null;
        }).when(mailbox).getBodies(any());
        return queued;
    }

    /**
     * A mailbox listing one message of this size, and answering TOP as asked.
     *
     * @param size the size the server lists for the message, in bytes
     * @param top whether the server advertises TOP
     * @return the mailbox the refresh talks to
     */
    private static POP3MailBox mailbox(int size, boolean top) {
        POP3MailBox mailbox = mock(POP3MailBox.class);
        when(mailbox.getUIDLs()).thenReturn(Collections.singletonList(UIDL));
        when(mailbox.getSize(UIDL)).thenReturn(size);
        when(mailbox.supportsTOP()).thenReturn(top);
        return mailbox;
    }

    /**
     * The inbox cache for this mailbox, with its disk cache under the scratch
     * directory rather than anywhere the router keeps state.
     *
     * @param mailbox the mailbox to fetch from
     * @return a cache holding no messages yet
     * @throws Exception if the scratch directory or the cache directory cannot be made
     */
    private MailCache cache(POP3MailBox mailbox) throws Exception {
        Properties props = new Properties();
        props.setProperty("i2p.dir.config", folder.getRoot().getAbsolutePath());
        props.setProperty("i2p.dir.base", folder.getRoot().getAbsolutePath());
        I2PAppContext ctx = new I2PAppContext(props);
        return new MailCache(ctx, mailbox, WebMail.DIR_FOLDER, "pop.example", 110, "user", "pass");
    }
}
