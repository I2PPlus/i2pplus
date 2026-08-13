package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

/**
 *  A record of a SAM session stored in the {@link SessionsDB}.
 *  Contains the destination, properties, and handler for a session.
 *
 *  @since 0.9.25 moved from SAMv3Handler
 */
class SessionRecord {
	private final String m_dest;
	private final Properties m_props;
	private final List<Thread> m_threads = new ArrayList<Thread>();
	private final SAMv3Handler m_handler;
	private volatile long _lastAccessed; // NOSONAR volatile is correct: assigned and read as a timestamp, not read-modify-written

	/**
	 * Create a new session record.
	 *
	 * @param dest base64-encoded destination
	 * @param props session properties
	 * @param handler the SAM handler for this session
	 */
	public SessionRecord( String dest, Properties props, SAMv3Handler handler )
	{
		m_dest = dest;
		m_props = new Properties();
		m_props.putAll(props);
		m_handler = handler;
		_lastAccessed = System.currentTimeMillis();
	}

	/**
	 *  Update the last-accessed timestamp.
	 *  Called by SessionsDB.get().
	 */
	void touch() {
		_lastAccessed = System.currentTimeMillis();
	}

	/**
	 *  @return timestamp of last access via get(), or construction time
	 */
	long getLastAccessed() {
		return _lastAccessed;
	}

	/**
	 * Copy constructor.
	 *
	 * @param in the session record to copy
	 */
	public SessionRecord( SessionRecord in )
	{
		m_dest = in.getDest();
		m_props = in.getProps();
		m_threads.addAll(in.getThreads());
		m_handler = in.getHandler();
		_lastAccessed = in._lastAccessed;
	}

	/**
	 * Get the base64-encoded destination.
	 *
	 * @return the destination string
	 */
	public String getDest()
	{
		return m_dest;
	}

	/**
	 * Get a copy of the session properties.
	 *
	 * @return a copy of the properties
	 */
	synchronized public Properties getProps()
	{
		Properties p = new Properties();
		p.putAll(m_props);
		return p;
	}

	/**
	 * Get the SAM handler for this session.
	 *
	 * @return the handler
	 */
	public SAMv3Handler getHandler()
	{
		return m_handler;
	}

	/**
	 * Get a snapshot of the threads running for this session.
	 *
	 * @return the session threads, as a copy
	 */
	synchronized public List<Thread> getThreads()
	{
		return new ArrayList<Thread>(m_threads);
	}

	/**
	 * Register a new session thread and start it.
	 *
	 * @param thread the thread to register and start
	 */
	synchronized public void startThread(Thread thread)
	{
		// drop finished threads so the list only tracks live ones
		for (Iterator<Thread> it = m_threads.iterator(); it.hasNext();) {
			if (!it.next().isAlive())
				it.remove();
		}
		m_threads.add(thread);
		thread.start();
	}
}
