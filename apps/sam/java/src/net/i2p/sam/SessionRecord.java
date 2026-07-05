package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

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
	private ThreadGroup m_threadgroup;
	private final SAMv3Handler m_handler;

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
		m_threadgroup = in.getThreadGroup();
		m_handler = in.getHandler();
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
	 * Get the thread group for this session.
	 *
	 * @return the thread group, or null if not yet created
	 */
	synchronized public ThreadGroup getThreadGroup()
	{
		return m_threadgroup;
	}

	/**
	 * Create a thread group for this session if one does not already exist.
	 *
	 * @param name the name of the thread group
	 */
	synchronized public void createThreadGroup(String name)
	{
		if (m_threadgroup == null)
			m_threadgroup = new ThreadGroup(name);
	}
}
