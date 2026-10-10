/*
 * Created on May 20, 2010
 * Created by Paul Gardner
 *
 * Copyright 2010 Vuze, Inc.  All rights reserved.
 *
 * Licensed under the GPLv2 or later.
 */

package com.vuze.plugins.mlab.tools.ndt.swingemu;

import edu.internet2.ndt.Tcpbw100;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Emulation of javax.swing.text.StyledDocument for the NDT (Network Diagnostic Tool) plugin.
 *
 * <p>This class provides a functional document model that captures text content
 * from the NDT tool and logs it to the I2P logging system. Unlike most components
 * in this package, StyledDocument provides actual functionality by storing text
 * content and forwarding it to the I2P logger.</p>
 *
 * <p>This allows NDT test results and formatted text to be captured and logged
 * even in headless environments without requiring an actual graphical display.
 * The document supports text insertion and length tracking but provides no actual
 * styling or rendering functionality.</p>
 *
 */
public class
StyledDocument
{
	/**
	 * The emulated document starts as an empty StringBuilder with a logger attached;
	 * insertString() is what puts text in it.
	 */
	public StyledDocument()
	{
	}

	private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(Tcpbw100.class);
	private final StringBuilder text = new StringBuilder();

	/**
	 * Return the document length.
	 *
	 * @return the number of characters appended to the document so far
	 */
	public int
	getLength()
	{
		return text.length();
	}

	/**
	 * Insert a string at the given offset.
	 *
	 * @param offset position the text is inserted at; not honored by this emulation
	 * @param s the text to log at the I2P warn level and append to the document
	 * @param x styling attributes; ignored by this emulation
	 * @throws BadLocationException never thrown, declared to match the Swing interface
	 */
	public void
	insertString(
		int		offset,
		String	s,
		Object	x )

		throws BadLocationException
	{
		if (_log.shouldWarn())
			_log.warn(s.trim());
		text.append(s);
	}
}
