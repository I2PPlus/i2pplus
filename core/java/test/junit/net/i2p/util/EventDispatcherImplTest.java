package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Set;

public class EventDispatcherImplTest {

    @Test
    public void testNotifyAndGetEvent() {
        EventDispatcherImpl ed = new EventDispatcherImpl();
        ed.notifyEvent("test", "value");
        assertEquals("value", ed.getEventValue("test"));
    }

    @Test
    public void testGetEventUnknown() {
        EventDispatcherImpl ed = new EventDispatcherImpl();
        assertNull(ed.getEventValue("unknown"));
    }

    @Test
    public void testGetEvents() {
        EventDispatcherImpl ed = new EventDispatcherImpl();
        ed.notifyEvent("a", 1);
        ed.notifyEvent("b", 2);
        Set<String> events = ed.getEvents();
        assertTrue(events.contains("a"));
        assertTrue(events.contains("b"));
    }

    @Test
    public void testIgnoreEvents() {
        EventDispatcherImpl ed = new EventDispatcherImpl();
        ed.notifyEvent("test", "value");
        ed.ignoreEvents();
        assertNull(ed.getEventValue("test"));
        assertTrue(ed.getEvents().isEmpty());
    }

    @Test
    public void testUnIgnoreEvents() {
        EventDispatcherImpl ed = new EventDispatcherImpl();
        ed.ignoreEvents();
        ed.unIgnoreEvents();
        // After un-ignoring, new events should be visible
        ed.notifyEvent("test", "value");
        assertEquals("value", ed.getEventValue("test"));
    }

    @Test
    public void testGetEventDispatcher() {
        EventDispatcherImpl ed = new EventDispatcherImpl();
        assertSame(ed, ed.getEventDispatcher());
    }

    @Test
    public void testAttachDetach() {
        EventDispatcherImpl parent = new EventDispatcherImpl();
        EventDispatcherImpl child = new EventDispatcherImpl();
        parent.attachEventDispatcher(child);
        parent.notifyEvent("test", "value");
        // Child should also receive the event
        assertEquals("value", child.getEventValue("test"));
        parent.detachEventDispatcher(child);
    }

    @Test
    public void testGetEventsReturnsCopy() {
        EventDispatcherImpl ed = new EventDispatcherImpl();
        ed.notifyEvent("a", 1);
        Set<String> events = ed.getEvents();
        ed.notifyEvent("b", 2);
        assertFalse(events.contains("b"));
    }
}
