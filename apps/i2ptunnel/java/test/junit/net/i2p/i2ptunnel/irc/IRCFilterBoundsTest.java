package net.i2p.i2ptunnel.irc;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the IRCFilter line filters against bare and short command lines:
 * a bare PRIVMSG or PART used to throw ArrayIndexOutOfBoundsException out
 * of the filter and kill the connection instead of dropping the line.
 *
 * @since 0.9.72+
 */
public class IRCFilterBoundsTest {

    @Test
    public void barePrivmsgDroppedNotCrash() {
        assertNull(IRCFilter.inboundFilter("PRIVMSG", new StringBuffer(), null));
    }

    @Test
    public void bareNoticeDroppedNotCrash() {
        assertNull(IRCFilter.inboundFilter("NOTICE", new StringBuffer(), null));
    }

    @Test
    public void barePartDroppedNotCrash() {
        assertNull(IRCFilter.outboundFilter("PART", new StringBuffer(), null));
    }

    @Test
    public void partWithChannelRewritten() {
        assertEquals("PART #chan :leaving",
                     IRCFilter.outboundFilter("PART #chan :bye bye", new StringBuffer(), null));
    }

    @Test
    public void quitRewritten() {
        assertEquals("QUIT :leaving",
                     IRCFilter.outboundFilter("QUIT :see ya", new StringBuffer(), null));
    }

    @Test
    public void barePrivmsgWithPrefixDroppedNotCrash() {
        assertNull(IRCFilter.inboundFilter(":server.name PRIVMSG", new StringBuffer(), null));
    }

    @Test
    public void emptyLineSurvivesFilters() {
        // must not throw on the split fields either
        IRCFilter.inboundFilter("", new StringBuffer(), null);
        IRCFilter.outboundFilter("", new StringBuffer(), null);
    }
}
