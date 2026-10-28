package net.i2p.router.web.helpers;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the container the sidebar's router-control block is rendered in.
 *
 * <p>The restart/shutdown form is submitted into a hidden iframe so the page is
 * not reloaded, and the countdown that follows is rendered in the same run of
 * markup as the buttons. The client refreshes one element by id, so the status
 * heading and the form have to sit inside a container of their own: refreshing
 * the buttons alone would leave a countdown rendered somewhere the refresh does
 * not reach, and the sidebar would show a control whose state the router has
 * already moved past.
 *
 * <p>The container also has to stay out of the sidebar's periodic refresh
 * selector, which matches {@code .volatile}: a volatile wrapper around volatile
 * children makes that selector match the wrapper and the elements inside it, so
 * every pass replaces the same markup twice.
 *
 * @since 0.9.72+
 */
public class ConfigRestartBeanControlBoxTest {

    /** The id the client refreshes after a submission. */
    private static final String BOX = "sb_routerControlBox";

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the wrapper is a pure function of its argument.
     *
     * @since 0.9.72+
     */
    public ConfigRestartBeanControlBoxTest() {}

    /** The content is wrapped, unchanged, in a container carrying the id. */
    @Test
    public void testContentIsWrappedIntact() {
        String content = "<h4 id=sb_shutdownStatus>Restart imminent</h4>\n<form id=sb_routerControl></form>\n";
        String wrapped = ConfigRestartBean.wrapControlBox(content);
        assertTrue(wrapped.startsWith("<div id=" + BOX + ">"));
        assertTrue(wrapped.contains(content));
        assertTrue("the container is closed", wrapped.trim().endsWith("</div>"));
    }

    /** Nothing to show is nothing to render, so no empty container is emitted. */
    @Test
    public void testEmptyContentEmitsNothing() {
        assertEquals("", ConfigRestartBean.wrapControlBox(""));
        assertEquals("", ConfigRestartBean.wrapControlBox(null));
    }

    /**
     * The container is not volatile, so the sidebar's periodic refresh of
     * {@code .volatile} does not also match the children inside it.
     */
    @Test
    public void testContainerIsNotVolatile() {
        String wrapped = ConfigRestartBean.wrapControlBox("<form class=volatile></form>");
        String opener = wrapped.substring(0, wrapped.indexOf('>'));
        assertFalse("the box must not be volatile", opener.contains("volatile"));
    }

    /** The block is refreshed by id, so the id appears exactly once. */
    @Test
    public void testBoxIdAppearsOnce() {
        String wrapped = ConfigRestartBean.wrapControlBox("<form id=sb_routerControl></form>");
        int first = wrapped.indexOf(BOX);
        assertTrue(first >= 0);
        assertEquals("the id must not be duplicated", -1, wrapped.indexOf(BOX, first + 1));
    }
}
