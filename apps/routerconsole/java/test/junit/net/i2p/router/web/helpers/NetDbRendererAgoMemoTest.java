package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link NetDbRenderer.AgoMemo}, the per-render memo behind the netdb "{0} ago" cells.
 *
 * <p>{@code Translate.getString} rebuilds a {@link java.text.MessageFormat} and re-parses the
 * pattern on every call, and the netdb row renderer asks for this text once or twice per known
 * router. The memo has to be exact: keyed on the pattern key rather than the result it would hand
 * back stale text for a different age, and safe under the parallel render path.
 */
public class NetDbRendererAgoMemoTest {

    /** A translator that records how often the expensive path actually ran. */
    private static final class Counting {
        final AtomicInteger calls = new AtomicInteger();

        String apply(String duration) {
            calls.incrementAndGet();
            return duration + " ago";
        }
    }

    /** The memo must hand back exactly what the translator produced. */
    @Test
    public void testMatchesTranslator() {
        NetDbRenderer.AgoMemo memo = new NetDbRenderer.AgoMemo();
        Counting counting = new Counting();
        assertEquals("5m ago", memo.ago("5m", "en", counting::apply));
        assertEquals("1h 2m ago", memo.ago("1h 2m", "en", counting::apply));
        assertEquals(2, counting.calls.get());
    }

    /** A repeated age must not re-enter the translator, which is the whole point of the memo. */
    @Test
    public void testRepeatedDurationIsMemoized() {
        NetDbRenderer.AgoMemo memo = new NetDbRenderer.AgoMemo();
        Counting counting = new Counting();
        for (int i = 0; i < 100; i++) {
            assertEquals("5m ago", memo.ago("5m", "en", counting::apply));
        }
        assertEquals(1, counting.calls.get());
    }

    /**
     * Dynamic ages must stay distinct: the cache is keyed on the pattern key, so two different
     * durations cannot collide on one memoized result.
     */
    @Test
    public void testDistinctDurationsStayDistinct() {
        NetDbRenderer.AgoMemo memo = new NetDbRenderer.AgoMemo();
        Counting counting = new Counting();
        List<String> seen = new ArrayList<>();
        for (String duration : new String[] { "1s", "2s", "3m", "1h", "1d" }) {
            String result = memo.ago(duration, "en", counting::apply);
            assertEquals(duration + " ago", result);
            seen.add(result);
        }
        assertEquals(5, new java.util.HashSet<>(seen).size());
        assertEquals(5, counting.calls.get());
    }

    /** A language change must invalidate every entry, or rows would show the previous language. */
    @Test
    public void testLanguageChangeInvalidates() {
        NetDbRenderer.AgoMemo memo = new NetDbRenderer.AgoMemo();
        assertEquals("5m ago", memo.ago("5m", "en", d -> d + " ago"));
        assertEquals("5m il y a", memo.ago("5m", "fr", d -> d + " il y a"));
        assertNotEquals("the memo must not serve the previous language", "5m ago",
                        memo.ago("5m", "fr", d -> d + " il y a"));
        // switching back must not resurrect the discarded entries either
        assertEquals("5m ago", memo.ago("5m", "en", d -> d + " ago"));
    }

    /** A null duration or translator yields null rather than poisoning the cache. */
    @Test
    public void testNullsAreNotCached() {
        NetDbRenderer.AgoMemo memo = new NetDbRenderer.AgoMemo();
        Counting counting = new Counting();
        assertNull(memo.ago(null, "en", counting::apply));
        assertNull(memo.ago("5m", "en", null));
        assertEquals(0, counting.calls.get());
        assertEquals("5m ago", memo.ago("5m", "en", counting::apply));
    }

    /**
     * The netdb renderer runs its rows on the common pool, so the memo has to hold up under
     * concurrent readers: every thread must observe its own correct translation.
     */
    @Test
    public void testConcurrentUseIsConsistent() throws InterruptedException {
        NetDbRenderer.AgoMemo memo = new NetDbRenderer.AgoMemo();
        String[] durations = { "1s", "5s", "30s", "1m", "5m", "1h", "1d" };
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int pass = 0; pass < 200; pass++) {
                        for (String duration : durations) {
                            String result = memo.ago(duration, "en", d -> d + " ago");
                            if (!result.equals(duration + " ago")) {
                                failures.add(new AssertionError(duration + " -> " + result));
                            }
                        }
                    }
                } catch (Throwable th) {
                    failures.add(th);
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        done.await();
        assertEquals("concurrent memo reads must agree", Collections.emptyList(), failures);
    }
}
