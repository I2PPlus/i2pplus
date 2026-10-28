package net.i2p.router.web.helpers;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins how long the update manager's status line stays in the sidebar.
 *
 * <p>The sidebar re-renders every few seconds, so a finished update message -
 * the plugin-updated line among them, and a download failure - would otherwise
 * sit in the nav indefinitely, long after the restart it was describing. The
 * line is shown while an update is in progress, and while a router update sits
 * downloaded waiting for a restart, and for {@link
 * SidebarHelper#STATUS_NOTICE_TTL_MS} from when the manager set it in every
 * other case.
 *
 * @since 0.9.72+
 */
public class SidebarHelperUpdateNoticeTest {

    /** The window the tests measure against. */
    private static final long TTL = SidebarHelper.STATUS_NOTICE_TTL_MS;

    /**
     * A time to measure from. Zero is reserved for "no stamp at all", so the
     * age cases need a real one.
     */
    private static final long BASE = 1600000000000L;

    /** No test state; each test calls the pure decision directly. */
    public SidebarHelperUpdateNoticeTest() {}

    /** A finished message is shown the moment it is set. */
    @Test
    public void testFreshMessageIsShown() {
        assertTrue(SidebarHelper.showUpdateStatus("<b>Plugin updated</b>", false, false, 1000L, 1000L));
    }

    /** A finished message is shown right up to the end of the window. */
    @Test
    public void testShownOneMsBeforeTheDeadline() {
        assertTrue(SidebarHelper.showUpdateStatus("Plugin updated", false, false, BASE, BASE + TTL - 1));
    }

    /** The window is half-open: at exactly the TTL the message is gone. */
    @Test
    public void testGoneAtTheDeadline() {
        assertFalse(SidebarHelper.showUpdateStatus("Plugin updated", false, false, BASE, BASE + TTL));
    }

    /** Well past the window, the plugin-update line has said its piece. */
    @Test
    public void testGoneAfterTheDeadline() {
        assertFalse(SidebarHelper.showUpdateStatus("Plugin updated", false, false, BASE, BASE + TTL + 1));
        assertFalse(SidebarHelper.showUpdateStatus("Plugin updated", false, false, BASE,
                                                   BASE + TTL + 60 * 60 * 1000L));
    }

    /** Progress is what the line is for, so an in-progress message never ages out. */
    @Test
    public void testInProgressOutlivesTheWindow() {
        assertTrue(SidebarHelper.showUpdateStatus("Downloading", true, false, BASE, BASE + TTL + 60 * 60 * 1000L));
    }

    /**
     * A downloaded router update is installed by a restart, so its message
     * stays with the nav's "restart to install" heading for as long as the
     * update is waiting, long past the window.
     */
    @Test
    public void testDownloadedUpdateOutlivesTheWindow() {
        assertTrue(SidebarHelper.showUpdateStatus("Update downloaded", false, true, BASE, BASE + TTL));
        assertTrue(SidebarHelper.showUpdateStatus("Update downloaded", false, true, BASE,
                                                  BASE + TTL + 24 * 60 * 60 * 1000L));
    }

    /**
     * The marker is what persists, not the age: with nothing downloaded, the
     * same old message is dropped on schedule.
     */
    @Test
    public void testNoDownloadedUpdateExpiresOnSchedule() {
        assertFalse(SidebarHelper.showUpdateStatus("Update downloaded", false, false, BASE, BASE + TTL + 1));
    }

    /** Nothing to say is nothing to render, at any age, marker or no marker. */
    @Test
    public void testEmptyAndNullShowNothing() {
        assertFalse(SidebarHelper.showUpdateStatus("", false, false, 0L, 0L));
        assertFalse(SidebarHelper.showUpdateStatus(null, false, false, 0L, 0L));
        assertFalse(SidebarHelper.showUpdateStatus("", true, false, 0L, 0L));
        assertFalse(SidebarHelper.showUpdateStatus(null, true, false, 0L, 0L));
        assertFalse(SidebarHelper.showUpdateStatus("", false, true, 0L, 0L));
        assertFalse(SidebarHelper.showUpdateStatus(null, false, true, 0L, 0L));
    }

    /** A manager with no stamp - a build from before the stamp - keeps the old behaviour. */
    @Test
    public void testUnknownAgeIsShown() {
        assertTrue(SidebarHelper.showUpdateStatus("Plugin updated", false, false, 0L, Long.MAX_VALUE));
        assertTrue(SidebarHelper.showUpdateStatus("Plugin updated", false, false, -1L, Long.MAX_VALUE));
    }

    /** The window is five minutes: long enough to read, short enough to be gone. */
    @Test
    public void testWindowIsFiveMinutes() {
        assertEquals(5 * 60 * 1000L, TTL);
    }
}
