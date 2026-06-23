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
 * Emulation of java.awt.Panel for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a panel container
 * to allow the NDT tool to run in headless environments. The panel can contain
 * other components but provides no actual layout or rendering functionality.</p>
 * 
 * <p>All operations are no-ops, maintaining API compatibility without requiring
 * an actual graphical display system.</p>
 * 
 */
public class
Panel
	extends Component
{
	public void
	validate()
	{
	}

	public void
	remove(
		Component	c )
	{
	}
}
