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
 * Emulation of javax.swing.JTextArea for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a functional text area component that captures text output
 * from the NDT tool and logs it to the I2P logging system. Unlike other components
 * in this package, JTextArea provides actual functionality by storing text content
 * and forwarding it to the I2P logger.</p>
 * 
 * <p>This allows NDT test results and status messages to be captured and logged
 * even in headless environments without requiring an actual graphical display.</p>
 * 
 */
public class
JTextArea
	extends Component
{
	private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(Tcpbw100.class);
	private final StringBuilder text = new StringBuilder();

	public
	JTextArea(
		String		str,
		int			a,
		int			b )
	{
		text.append(str);
	}

	public void
	append(
		String		str )
	{
		if (_log.shouldWarn())
			_log.warn(str.trim());
		text.append(str);
	}

	public String
	getText()
	{
		return text.toString();
	}

	public void
	selectAll()
	{
	}
}
