/*
 * Created on May 20, 2010
 * Created by Paul Gardner
 *
 * Copyright 2010 Vuze, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
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
