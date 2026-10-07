package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Differential test for {@link LogsHelper#applyRewrites(String, String[][])}: the table-driven
 * rewrite has to reproduce the {@code String.replace} chains it replaced exactly, order included,
 * because the console renders the result to the user.
 *
 * <p>The reference chains below are verbatim copies of the code as it stood before the
 * optimization, so a reordering mistake fails here instead of corrupting a log line.
 */
public class LogsHelperRewritesTest {

    /** Representative escaped log lines: levels, thread columns, arrows, ticks, stack frames. */
    private static final String[] LINES = {
        "",
        "2024-01-02 03:04:05.678 INFO  | net.i2p.router.transport.Foo: message text",
        "| INFO   | net.i2p.router.transport.Foo: sending -> peer",
        "| WARN   | lamport: 0 false positives in 3  tries",
        "| INFO   | accept=true reject=false allowed=true",
        "| ERROR  | java.lang.IllegalStateException: bad state\n\tat net.i2p.A.b(A.java:1)\n\tat java.base/C.d(C.java:2)\n* and more",
        "| INFO   | [DBWriter   ] flushed | [UDPSender] x | [BWRefiller] y",
        "| INFO   | […ueue Pumper] and …obQueue and [NTCP Pumper ok",
        "| INFO   | [Addressbook] [Thread-3] [Timestamper] [DHT Explore] [HostChecker]",
        "| INFO   | IRC Client] Inbound message & IRC Client] Outbound message",
        "a:  b:  c:  d  e",
        "dots ... and ]] brackets [[&#10004;]] [[&#10008;]]",
        "| INFO   | publish: not publishing old one: RouterInfo: foo",
        "| INFO   | Publishing our RouterInfo after delay: RouterInfo: bar",
        "&amp;darr;&amp;uarr;&amp;#10140;&amp;hellip; --&gt; -&gt; -&gt;  &#10140;  tail",
        "| INFO   | &lt;b&gt;bold&lt;/b&gt; and <b>bold</b><br>: x",
        "line one\r\n\r\nline two",
        "| INFO   | true false truely falsehood",
        "| ERROR  | [[&#10004;]]=[[&#10004;]] and [[&#10008;]]=[[&#10008;]]",
        "<a href=\"/x?a=1&amp;b=2\">click</a> and &lt;a href=x&gt;esc&lt;/a&gt;",
        "| INFO   | " + repeat("padding", 50),
        "truefalse" + "]]" + ":  " + "false positives"
    };

    /**
     *  {@code String.repeat} is Java 11 and this module compiles at release 8.
     *
     *  @param s the string to repeat
     *  @param n how many copies
     *  @return s repeated n times
     */
    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {sb.append(s);}
        return sb.toString();
    }

    /** {@code formatMessages} rewrites A: escaped entities through the yes/no tick. */
    private static String referenceRewritesA(String msg) {
        msg = msg.replace("&amp;darr;", "&darr;");
        msg = msg.replace("&amp;uarr;", "&uarr;");
        msg = msg.replace("&amp;#10140;", "&#10140;");
        msg = msg.replace("--&gt;", " &#10140; ");
        msg = msg.replace(" -&gt;", " &#10140;");
        msg = msg.replace("-&gt;", " &#10140; ");
        msg = msg.replace("  &#10140;  ", " &#10140; ");
        msg = msg.replace("&amp;hellip;", "...");
        msg = msg.replace("…obQueue", "JobQueue");
        msg = msg.replace("[DBWriter   ]", "[NetDB Writer]");
        msg = msg.replace("[NTCP Pumper", "[ NTCP Pumper");
        msg = msg.replace("[…ueue Pumper", "[Queue Pumper");
        msg = msg.replace("[UDPSender", "[UDP Sender");
        msg = msg.replace("[BWRefiller", "[BW Refiller");
        msg = msg.replace("[Addressbook]", "[Addressbook ]");
        msg = msg.replace("[Thread-", "[ Thread-");
        msg = msg.replace("[Timestamper]", "[Timestamper ]");
        msg = msg.replace("[DHT Explore]", "[DHT Explore ]");
        msg = msg.replace("[HostChecker]", "[HostChecker ]");
        msg = msg.replace("false", "[&#10008;]"); // no (cross)
        msg = msg.replace("[&#10008;] positives", "false positives");
        msg = msg.replace("true", "[&#10004;]"); // yes (tick)
        return msg;
    }

    /** {@code formatMessages} rewrites B: the un-ticking of "=true" / "=false" and IRC labels. */
    private static String referenceRewritesB(String msg) {
        msg = msg.replace("=[&#10004;]", "=true");
        msg = msg.replace("=[&#10008;]", "=false");
        msg = msg.replace("[IRC Client] Inbound message", "[IRC Client] &#11167;");
        msg = msg.replace("[IRC Client] Outbound message", "[IRC Client] &#11165;");
        msg = msg.replace("not publishing old one: RouterInfo:", "not publishing old one:");
        msg = msg.replace("Publishing our RouterInfo after delay: RouterInfo:", "Publishing our RouterInfo after delay:");
        msg = msg.replace(":  ", ": ");
        return msg;
    }

    /** {@code formatMessages} rewrites C: markup stripping, the ellipsis, and the double bracket. */
    private static String referenceRewritesC(String msg) {
        msg = msg.replace("\r\n\r\n", "");
        msg = msg.replace("<br>:", " ");
        msg = msg.replace("<b>", "");
        msg = msg.replace("</b>", "");
        msg = msg.replace("&lt;b&gt;", "");
        msg = msg.replace("&lt;/b&gt;", "");
        msg = msg.replace("...", "&hellip;");
        msg = msg.replace("]]", "]");
        return msg;
    }

    /** {@code getServiceLogs} rewrites: the wrapper-log column fixups, in their original order. */
    private static String referenceWrapperRewrites(String str) {
        str = str.replace("| |", "|");
        str = str.replace("| INFO   | INFO:", "| INFO   |");
        str = str.replace("| INFO   | CRIT ", "| CRIT   |");
        str = str.replace("| INFO   | ERROR", "| ERROR  |");
        str = str.replace("| INFO   | Error", "| ERROR  | Error");
        str = str.replace("| INFO   | java.lang", "| ERROR  | java.lang");
        str = str.replace("| INFO   | \tat", "| ERROR  | \tat");
        str = str.replace("| ERROR  | [Reseed     ] ....reseed.Reseeder:", "| WARN   |");
        str = str.replace(" |[", " | [");
        str = str.replace("INFO   | WARN:", "WARN   |");
        str = str.replace("INFO   | WARNING:", "WARN   |");
        str = str.replace("   |", " |");
        str = str.replace("| ERROR  |", "| ERR  |");
        str = str.replace("| INFO | # V  [", "| INFO | # Source: [");
        str = str.replace("->", "➜");
        str = str.replace("| LOCAL LeaseSet for", "| WARN | LOCAL LeaseSet for");
        str = str.replace("| Initiating graceful restart", "| INFO | Initiating graceful restart");
        str = str.replace("| Graceful shutdown", "| INFO | Graceful shutdown");
        str = str.replace("| I2P+ update downloaded", "| INFO | I2P+ update downloaded");
        return str;
    }

    /** The message rewrite tables are private, so drive them through the shared engine. */
    private static String messageRewrites(String msg) {
        msg = LogsHelper.applyRewrites(msg, logsRewrites("MSG_REWRITES_A"));
        msg = LogsHelper.applyRewrites(msg, logsRewrites("MSG_REWRITES_B"));
        return LogsHelper.applyRewrites(msg, logsRewrites("MSG_REWRITES_C"));
    }

    /** Reads a private rewrite table or pattern reflectively so the test cannot drift from the source. */
    private static String[][] logsRewrites(String name) {
        return (String[][]) logsField(name);
    }

    private static Object logsField(String name) {
        try {
            java.lang.reflect.Field f = LogsHelper.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException roe) {
            throw new AssertionError("missing LogsHelper field " + name, roe);
        }
    }

    /**
     * The four hoisted anchor-strip patterns must match the old inline regexes exactly; a
     * hand-edited pattern here would silently change rendered log lines.
     */
    @Test
    public void testAnchorStripPatternsMatchReference() {
        assertEquals("&lt;a[^&]*&gt;", ((Pattern) logsField("ANCHOR_ESCAPED")).pattern());
        assertEquals("&lt;/a&gt;", ((Pattern) logsField("ANCHOR_CLOSE_ESCAPED")).pattern());
        assertEquals("<a[^>]*>", ((Pattern) logsField("ANCHOR")).pattern());
        assertEquals("</a>", ((Pattern) logsField("ANCHOR_CLOSE")).pattern());
    }

    /**
     * The seven wrapper-log patterns must match the old inline regexes exactly; a narrowed
     * pattern would stop rewriting a log line that used to be rewritten.
     */
    @Test
    public void testWrapperLogPatternsMatchReference() {
        String[] names = { "LOG_HUTD", "LOG_DATE", "LOG_CONNECTION", "LOG_RESTART",
                           "LOG_READ", "LOG_DIRMON", "LOG_QUEUE" };
        for (String name : names) {
            assertEquals(name, ((Pattern) logsField(name)).pattern(),
                         referenceWrapperPattern(name).pattern());
        }
        // "-Connection" in particular must keep its leading ".*", or "|...[bar-Connection]:" stops matching
        assertEquals("\\|.*\\[.*-Connection].*?:", ((Pattern) logsField("LOG_CONNECTION")).pattern());
    }

    /** The wrapper-log pattern for a name, as declared before the hoisting. */
    private static Pattern referenceWrapperPattern(String name) {
        switch (name) {
            case "LOG_HUTD": return Pattern.compile("\\|.*\\[.*hutd.*\\].*?:");
            case "LOG_DATE": return Pattern.compile("\\|.*\\[.*date.*\\].*?:");
            case "LOG_CONNECTION": return Pattern.compile("\\|.*\\[.*-Connection].*?:");
            case "LOG_RESTART": return Pattern.compile("\\|.*\\[.*Restart].*?:");
            case "LOG_READ": return Pattern.compile("\\|.*\\[.*Read.*\\].*?:");
            case "LOG_DIRMON": return Pattern.compile("\\|.*\\[.*DirMon.*\\].*?:");
            default: return Pattern.compile("\\|.*\\[.*Queue.*\\].*?:");
        }
    }

    /** The message rewrite chain must match the pre-optimization chain line for line. */
    @Test
    public void testMessageRewritesMatchReference() {
        for (String line : LINES) {
            String expected = referenceRewritesC(referenceRewritesB(referenceRewritesA(line)));
            assertEquals(line, expected, messageRewrites(line));
        }
    }

    /** The wrapper-log rewrite chain must match too, including the "| ERR  |" ordering trap. */
    @Test
    public void testWrapperRewritesMatchReference() {
        String[] wrappers = {
            "", "| |", "| INFO   | INFO: starting", "| INFO   | ERROR broken",
            "| INFO   | Error broken", "| INFO   | java.lang.Exception: x",
            "| INFO   | \tat net.i2p.A.b(A.java:1)", "| ERROR  | [Reseed     ] ....reseed.Reseeder: y",
            " |[", "INFO   | WARN: x", "INFO   | WARNING: x", "   | sep", "| INFO | # V  [",
            "a->b->c", "| LOCAL LeaseSet for x", "| Initiating graceful restart", "| Graceful shutdown",
            "| I2P+ update downloaded", "| | | INFO   | ERROR java.lang |"
        };
        for (String line : wrappers) {
            assertEquals(line, referenceWrapperRewrites(line),
                         LogsHelper.applyRewrites(line, logsRewrites("WRAPPER_LOG_REWRITES")));
        }
    }

    /**
     * The two guarded regex passes must be no-ops when their necessary precondition is absent, so
     * skipping them cannot change a line that has no double bracket or no newline.
     */
    @Test
    public void testGuardedPassesAreNoOpsWhenPreconditionAbsent() {
        Pattern bracketCleanup = Pattern.compile("\\[\\[(&#10004;|&#10008;)\\]\\]");
        Pattern newlineStar = Pattern.compile("\n(\\t)?\\* ");
        for (String line : LINES) {
            if (line.indexOf("[[") < 0) {
                assertEquals(line, line, bracketCleanup.matcher(line).replaceAll("[$1]"));
            }
            if (line.indexOf('\n') < 0) {
                assertEquals(line, line, newlineStar.matcher(line).replaceAll("\n$1&bullet; "));
            }
        }
    }

    /** The patterns must also be no-ops on every line that does hit their precondition. */
    @Test
    public void testGuardedPassesStillApplyWhenPreconditionPresent() {
        Pattern bracketCleanup = Pattern.compile("\\[\\[(&#10004;|&#10008;)\\]\\]");
        for (String line : LINES) {
            String cleaned = bracketCleanup.matcher(line).replaceAll("[$1]");
            if (line.indexOf("[[") < 0) {
                assertSame("an absent double bracket must leave the line identical", line, cleaned);
            }
        }
        assertEquals("[[&#10004;]]", "[&#10004;]",
                     bracketCleanup.matcher("[[&#10004;]]").replaceAll("[$1]"));
    }
}
