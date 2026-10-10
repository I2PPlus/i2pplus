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
	/**
	 * The emulated spinner starts at 0; setValue() is the only thing that moves it.
	 */
	public SpinnerNumberModel()
	{
	}

	private int		value;

	/**
	 * The value.
	 * @param i the number to store, which getValue returns and an emulated spinner would display
	 */
	public void
	setValue(
		int	 i )
	{
		value	= i;
	}

	/**
	 * Return the value.
	 * @return the number most recently passed to setValue
	 */
	public int
	getValue()
	{
		return( value );
	}

	/**
	 * The minimum (no-op).
	 * @param i the lower bound an emulated spinner would refuse to go below, which this stub ignores
	 */
	public void
	setMinimum(
		int	i )
	{
	}
}
