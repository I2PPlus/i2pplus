package net.i2p.i2ptunnel.irc;

/*
 * free (adj.): unencumbered; not under the control of others Written
 * by human & jrandom in 2004 and released into the public domain with
 * no warranty of any kind, either expressed or implied.  It probably
 * won't make your computer catch on fire, or eat your children, but
 * it might.  Use at your own risk.
 */

import java.util.Set;
import net.i2p.util.EventDispatcher;

/**
 * An implementation of the EventDispatcher interface for
 * receiving events via in-line {@link #notifyEvent} only.
 * Does not support chaining to additional dispatchers.
 * Does not support {@link #waitEventValue}.
 * Does not support ignoring.
 *
 * @since 0.8.9
 */
public abstract class EventReceiver implements EventDispatcher {
    /**
     * Constructor for subclasses that receive events through notifyEvent().
     */

    public EventReceiver() {}

    @Override
    public EventDispatcher getEventDispatcher() {
        return this;
    }

    /**
     * Subscribe to IRC events.
     *
     * @throws UnsupportedOperationException always; the receiver is its own dispatcher
     */
    @Override
    public void attachEventDispatcher(EventDispatcher ev) {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsubscribe from IRC events.
     *
     * @throws UnsupportedOperationException always; the receiver is its own dispatcher
     */
    @Override
    public void detachEventDispatcher(EventDispatcher ev) {
        throw new UnsupportedOperationException();
    }

    @Override
    public abstract void notifyEvent(String eventName, Object args);

    /**
     * Value of the last fired event.
     *
     * @return never returns, as a receiver stores no event values
     * @throws UnsupportedOperationException always; a receiver stores no event values
     */
    @Override
    public Object getEventValue(String name) {
        throw new UnsupportedOperationException();
    }

    /**
     * All registered event names.
     *
     * @return never returns, as the receiver registers no event names
     * @throws UnsupportedOperationException always; the registry is never populated
     */
    @Override
    public Set<String> getEvents() {
        throw new UnsupportedOperationException();
    }

    /**
     * Stop waiting for events.
     *
     * @throws UnsupportedOperationException always; there is nothing to ignore
     */
    @Override
    public void ignoreEvents() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unIgnoreEvents() {}

    /**
     * Block until the event fires and return its value.
     *
     * @throws UnsupportedOperationException always; a receiver cannot block on an event
     */
    @Override
    public Object waitEventValue(String name) {
        throw new UnsupportedOperationException();
    }
}
