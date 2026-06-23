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
 * Emulation of javax.swing.JPanel for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a generic container component
 * to allow the NDT tool to run in headless environments. The panel can contain other
 * components but provides no actual layout or rendering functionality.</p>
 * 
 * <p>All operations are no-ops, maintaining API compatibility without requiring
 * an actual graphical display system.</p>
 * 
 */
public class
JPanel
	extends Component
{
	@Override
	public void
	add( Component c )
	{

	}

	@Override
	public void
	add( String str, Component c )
	{

	}
}
