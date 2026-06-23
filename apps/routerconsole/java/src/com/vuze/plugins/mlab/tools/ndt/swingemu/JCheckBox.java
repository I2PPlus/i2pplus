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
 * Emulation of javax.swing.JCheckBox for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a checkbox component
 * to allow the NDT tool to run in headless environments. The checkbox maintains
 * selection state but provides no actual visual representation.</p>
 * 
 * <p>All operations except selection state tracking are no-ops, maintaining API
 * compatibility without requiring an actual graphical display system.</p>
 * 
 */
public class
JCheckBox
	extends Component
{
	boolean	selected;

	public
	JCheckBox(
		String	str )
	{

	}

	public void
	setSelected(
		boolean	b )
	{
		selected = b;
	}

	public boolean
	isSelected()
	{
		return( selected );
	}

	public void
	addActionListener(
		ActionListener	l )
	{

	}
}
