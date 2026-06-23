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
 * Emulation of javax.swing.JProgressBar for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a progress bar component
 * to allow the NDT tool to run in headless environments. The progress bar maintains
 * value and indeterminate state but provides no actual visual representation.</p>
 * 
 * <p>All operations except state tracking are no-ops, maintaining API compatibility
 * without requiring an actual graphical display system.</p>
 * 
 */
public class
JProgressBar
	extends Component
{
	private boolean indeterminate;

	public void
	setString(
		String	str )
	{
	}

	public void
	setValue(
		int	i )
	{
	}

	public void
	setMinimum(
		int	i )
	{
	}

	public void
	setMaximum(
		int	i )
	{
	}

	public void
	setStringPainted(
		boolean	b )
	{
	}

	public void
	setIndeterminate(
		boolean	b )
	{
		indeterminate = b;
	}

	public boolean
	isIndeterminate()
	{
		return( indeterminate );
	}
}
