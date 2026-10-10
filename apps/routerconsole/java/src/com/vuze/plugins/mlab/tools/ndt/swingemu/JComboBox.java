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
 * Emulation of javax.swing.JComboBox for the NDT (Network Diagnostic Tool) plugin.
 *
 * <p>This class provides a minimal stub implementation of a dropdown combo box
 * component to allow the NDT tool to run in headless environments. The combo box
 * maintains selection index but provides no actual dropdown functionality.</p>
 *
 * <p>All operations except index tracking are no-ops, maintaining API compatibility
 * without requiring an actual graphical display system.</p>
 *
 */
public class
JComboBox
	extends Component
{
	/**
	 * Constructor. The selection index defaults to the stub's unset state and the
	 * dropdown methods are no-ops, so a bare instance is usable immediately.
	 */
	public JComboBox()
	{
	}

	private int index;

	/**
	 * Selected index.
	 *
	 * @param i the zero-based item index to record as the current selection
	 */
	public void
	setSelectedIndex(
		int	i )
	{
		index = i;
	}

	/**
	 * Return the selected index.
	 *
	 * @return the index last passed to setSelectedIndex(), or 0 if none was
	 */
	public int
	getSelectedIndex()
	{
		return( index );
	}

	/**
	 * Add an item (no-op).
	 *
	 * @param str the item to add, ignored because items cannot be added here
	 */
	public void
	addItem(
		String	str )
	{

	}
}
