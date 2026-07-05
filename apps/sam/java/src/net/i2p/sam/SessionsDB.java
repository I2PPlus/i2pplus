package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashMap;

/**
 *  A database of SAM sessions, mapping nicknames to {@link SessionRecord}s.
 *  Provides synchronized access for concurrent SAM handlers.
 *
 *  @since 0.9.25 moved from SAMv3Handler
 */
class SessionsDB {

	/**
	 * Exception thrown when attempting to create a session with an existing ID.
	 * @since 0.9.25
	 */
	static class ExistingIdException extends Exception {
		private static final long serialVersionUID = 0x1;
	}

	/**
	 * Exception thrown when attempting to create a session with an existing destination.
	 * @since 0.9.25
	 */
	static class ExistingDestException extends Exception {
		private static final long serialVersionUID = 0x1;
	}

	private final HashMap<String, SessionRecord> map;

	/**
	 * Create a new empty sessions database.
	 */
	public SessionsDB() {
		map = new HashMap<>();
	}

	/**
	 * Store a session record. Both the nick and destination must be unique.
	 *
	 * @param nick the session nickname
	 * @param session the session record to store
	 * @throws ExistingIdException if a session with this nick already exists
	 * @throws ExistingDestException if a session with this destination already exists
	 */
	public synchronized void put(String nick, SessionRecord session)
		throws ExistingIdException, ExistingDestException
	{
		if ( map.containsKey(nick) ) {
			throw new ExistingIdException();
		}
		for ( SessionRecord r : map.values() ) {
			if (r.getDest().equals(session.getDest())) {
				throw new ExistingDestException();
			}
		}
		session.createThreadGroup("SAM session "+nick);
		map.put(nick, session);
	}

	/**
	 * Store a session record, allowing duplicate destinations.
	 * Only the nick must be unique.
	 *
	 * @param nick the session nickname
	 * @param session the session record to store
	 * @throws ExistingIdException if a session with this nick already exists
	 * @since 0.9.25
	 */
	public synchronized void putDupDestOK(String nick, SessionRecord session)
		throws ExistingIdException
	{
		if (map.containsKey(nick)) {
			throw new ExistingIdException();
		}
		session.createThreadGroup("SAM session "+nick);
		map.put(nick, session);
	}

	/**
	 * Remove a session record by nickname.
	 *
	 * @param nick the session nickname
	 * @return true if removed, false if not found
	 */
	synchronized public boolean del( String nick )
	{
		return map.remove(nick) != null;
	}

	/**
	 * Get a session record by nickname.
	 *
	 * @param nick the session nickname
	 * @return the session record, or null if not found
	 */
	synchronized public SessionRecord get(String nick)
	{
		return map.get(nick);
	}

	/**
	 * Check if a session with the given nickname exists.
	 *
	 * @param nick the session nickname
	 * @return true if a session with this nick exists
	 */
	synchronized public boolean containsKey( String nick )
	{
		return map.containsKey(nick);
	}
}
