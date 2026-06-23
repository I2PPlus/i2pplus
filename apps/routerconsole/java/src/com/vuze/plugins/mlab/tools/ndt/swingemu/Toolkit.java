/*
 * Created on May 20, 2010
 * Created by Paul Gardner
 *
 * Copyright 2010 Vuze, Inc.  All rights reserved.
 *
 * Licensed under the GPLv2 or later.
 */



package com.vuze.plugins.mlab.tools.ndt.swingemu;

/**
 * Emulation of java.awt.Toolkit for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of the system toolkit
 * to allow the NDT tool to run in headless environments. The toolkit provides
 * clipboard access but no actual system integration.</p>
 * 
 * <p>All operations return stub implementations, maintaining API compatibility
 * without requiring an actual graphical display system.</p>
 * 
 */
public class Toolkit {

	public Clipboard
	getSystemClipboard()
	{
		return( new Clipboard());
	}
}
