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
 * Emulation of javax.swing.SpinnerNumberModel for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a spinner number model
 * to allow the NDT tool to run in headless environments. The model maintains
 * numeric value but provides no actual spinner functionality.</p>
 * 
 * <p>All operations except value tracking are no-ops, maintaining API compatibility
 * without requiring an actual graphical display system.</p>
 * 
 */
public class
SpinnerNumberModel
{
	private int		value;

	public void
	setValue(
		int	 i )
	{
		value	= i;
	}

	public int
	getValue()
	{
		return( value );
	}

	public void
	setMinimum(
		int	i )
	{
	}
}
