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

	/**
	 * Set the progress bar string.
	 *
	 * @param str the string to display
	 */
	public void
	setString(
		String	str )
	{
	}

	/**
	 * Set the progress bar value.
	 *
	 * @param i the value
	 */
	public void
	setValue(
		int	i )
	{
	}

	/**
	 * Set the minimum value.
	 *
	 * @param i the minimum value
	 */
	public void
	setMinimum(
		int	i )
	{
	}

	/**
	 * Set the maximum value.
	 *
	 * @param i the maximum value
	 */
	public void
	setMaximum(
		int	i )
	{
	}

	/**
	 * Set whether the progress bar string should be painted.
	 *
	 * @param b true if the string should be painted
	 */
	public void
	setStringPainted(
		boolean	b )
	{
	}

	/**
	 * Set the indeterminate state.
	 *
	 * @param b true if indeterminate
	 */
	public void
	setIndeterminate(
		boolean	b )
	{
		indeterminate = b;
	}

	/**
	 * Check if the progress bar is indeterminate.
	 *
	 * @return true if indeterminate
	 */
	public boolean
	isIndeterminate()
	{
		return( indeterminate );
	}
}
