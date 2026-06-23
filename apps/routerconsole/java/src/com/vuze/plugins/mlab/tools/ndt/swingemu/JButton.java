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
 * Emulation of javax.swing.JButton for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a button component
 * to allow the NDT tool to run in headless environments. The button can have
 * an action listener but provides no actual click functionality.</p>
 * 
 * <p>All operations are no-ops, maintaining API compatibility without requiring
 * an actual graphical display system.</p>
 * 
 */
public class
JButton
	extends Component
{
	public
	JButton(
		String		name )
	{
	}

	public void
	addActionListener(
		ActionListener	l )
	{

	}
}
