package net.i2p.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * A queue of messages, where each has an ID number.
 * Provide the ID back to the clear call, so you don't
 * erase messages you haven't seen yet.
 * <p>
 * Thread-safe.
 *
 * @since 0.9.33 adapted from SnarkManager
 */
public class UIMessages {

    /** Most messages kept before the oldest is dropped. */
    private final int _maxSize;
    /** Id the next message will get. */
    private int _count;
    /** The messages still being shown. */
    private final LinkedList<Message> _messages;
    /** Rendered messages, rebuilt when they change. */
    private List<String> _cachedStrings;
    /** Message count the cache was built at. */
    private int _cachedCount = -1;

    /**
     * Message buffer with the given capacity.
     *
     * @param maxSize the maximum number of messages to keep
     */
    public UIMessages(int maxSize) {
        if (maxSize < 1) throw new IllegalArgumentException();
        _maxSize = maxSize;
        _messages = new LinkedList<>();
    }

    /**
     * Will remove an old message if over the max size.
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing.
     *
     * @param message text to queue, HTML-escaped before storage
     * @return the message id
     */
    public int addMessage(String message) {
        return addMessageNoEscape(message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
    }

    /**
     * Use if it includes a link.
     * Does not escape '&lt;' and '&gt;' before queueing.
     *
     * @param message text to queue verbatim, so it may carry markup such as a link
     * @return the message id
     */
    public synchronized int addMessageNoEscape(String message) {
        return addMessageNoEscape(message, 0);
    }

    /**
     * The same, with a lifetime. A message with a lifetime leaves the queue by itself
     * once it is that old, so a brief event can be shown without sitting in front of the
     * user until the buffer wraps or they clear it. Messages queued without one are
     * unaffected.
     *
     * @param message text to queue verbatim, so it may carry markup such as a link
     * @param lifetime how long the message stays in ms, or 0 to keep it until the buffer
     *                 wraps or it is cleared
     * @return the message id
     * @since 0.9.72+
     */
    public synchronized int addMessageNoEscape(String message, long lifetime) {
        pruneExpired();
        long expires = lifetime > 0 ? System.currentTimeMillis() + lifetime : 0;
        _messages.offer(new Message(_count++, message, expires));
        while (_messages.size() > _maxSize) {
            _messages.poll();
        }
        _cachedStrings = null;
        _cachedCount = -1;
        return _count;
    }

    /**
     * Drop the messages that have run out their lifetime. Run on every read and write, so
     * an expired message is gone from the next render without the queue needing a timer
     * of its own.
     */
    private void pruneExpired() {
        long now = System.currentTimeMillis();
        boolean modified = false;
        for (Iterator<Message> iter = _messages.iterator(); iter.hasNext(); ) {
            if (iter.next().isExpired(now)) {
                iter.remove();
                modified = true;
            }
        }
        if (modified) {
            _cachedStrings = null;
            _cachedCount = -1;
        }
    }

    /**
     * The ID of the last message added, or -1 if never.
     * @return the last message i d
     */
    public synchronized int getLastMessageID() {
        return _count - 1;
    }

    /**
     * Newest last, or empty list.
     * Provide id of last one back to {@link #clearThrough}.
     *
     * @return a new list of the retained messages, newest last; empty if none
     */
    public synchronized List<Message> getMessages() {
        pruneExpired();
        if (_messages.peekLast() == null) return Collections.emptyList();
        return new ArrayList<>(_messages);
    }

    /**
     * Newest last, or empty list.
     *
     * @return a copy (cached)
     * @since 0.9.46
     */
    public synchronized List<String> getMessageStrings() {
        pruneExpired();
        if (_messages.peekLast() == null) return Collections.emptyList();
        // Return cached version if available and messages haven't changed
        if (_cachedStrings != null && _cachedCount == _count - 1) {
            return _cachedStrings;
        }
        // Build cache
        List<String> rv = new ArrayList<>(_messages.size());
        for (Message m : _messages) {
            rv.add(m.message);
        }
        _cachedStrings = rv;
        _cachedCount = _count - 1;
        return rv;
    }

    /**
     * Remove all messages from the queue.
     */
    public synchronized void clear() {
        _messages.clear();
        _cachedStrings = null;
        _cachedCount = -1;
    }

    /**
     * Remove all messages up to and including the given id.
     *
     * @param id message id to clear through
     */
    public synchronized void clearThrough(int id) {
        Message m = _messages.peekLast();
        if (m == null) {
            // nothing to do
        } else if (m.id <= id) {
            // easy way
            _messages.clear();
            _cachedStrings = null;
            _cachedCount = -1;
        } else {
            boolean modified = false;
            for (Iterator<Message> iter = _messages.iterator(); iter.hasNext(); ) {
                Message msg = iter.next();
                if (msg.id > id) break;
                iter.remove();
                modified = true;
            }
            if (modified) {
                _cachedStrings = null;
                _cachedCount = -1;
            }
        }
    }

    /**
     * Check if there are any messages.
     *
     * @return true if no messages remain, false otherwise
     * @since 0.9.46
     */
    public synchronized boolean isEmpty() {
        pruneExpired();
        return _messages.peekLast() == null;
    }

    /**
     * Represents a single UI message with a unique identifier.
     * <p>Each message contains a sequential ID number and the message text.
     * The ID is used to track which messages have been viewed and to
     * selectively clear messages from the queue.</p>
     */
    public static class Message {
        /** Sequential identifier. */
        public final int id;
        /** The message text. */
        public final String message;
        /** When this message leaves the queue on its own, 0 for never. */
        private final long _expires;

        /**
         * Records one message with its id and its expiry.
         *
         * @param i the message's id
         * @param msg the text to show
         * @param expires when the message stops being shown, in ms, or 0 for never
         */
        private Message(int i, String msg, long expires) {
            id = i;
            message = msg;
            _expires = expires;
        }

        /**
         * Whether this message has run out the lifetime it was queued with.
         *
         * @param now the current time in ms
         * @return true if the message should no longer be shown
         */
        boolean isExpired(long now) {
            return _expires > 0 && now >= _expires;
        }

        /**
         * Message text.
         */
        @Override
        public String toString() {
            return message;
        }
    }
}
