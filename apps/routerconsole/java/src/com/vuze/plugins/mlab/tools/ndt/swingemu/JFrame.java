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
 * Emulation of javax.swing.JFrame for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a top-level window container
 * to allow the NDT tool to run in headless environments. The frame contains a content
 * pane but provides no actual GUI functionality.</p>
 * 
 * <p>All operations are no-ops, maintaining API compatibility without requiring
 * an actual graphical display system.</p>
 * 
 */
public class
JFrame
	extends Component
{
	private Panel content_pane = new Panel();

	public
	JFrame()
	{

	}

	public
	JFrame(
		String	s )
	{

	}

	public Panel
	getContentPane()
	{
		return( content_pane );
	}

	public void
	toBack()
	{
	}

	public void
	destroy()
	{

	}

	public void
	dispose()
	{

	}
}
