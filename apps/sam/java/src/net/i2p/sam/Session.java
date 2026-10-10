package net.i2p.sam;

/**
 * A V3 session.
 *
 * @since 0.9.25 moved from SAMv3Handler
 */
interface Session extends SAMMessageSess {
	/**
	 * The nickname the client registered this session under, which is the key
	 * it is filed and removed by in the SessionsDB.
	 *
	 * @return the registered nickname
	 */
	String getNick();
}

