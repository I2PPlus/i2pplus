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
 * Emulation of javax.swing.JOptionPane for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of standard dialog boxes
 * to allow the NDT tool to run in headless environments. All dialog methods are
 * no-ops that silently discard messages, maintaining API compatibility without
 * requiring an actual graphical display system.</p>
 * 
 * <p>This allows the NDT tool to display informational messages without interrupting
 * execution in server or headless environments.</p>
 * 
 */
public class
JOptionPane
{
	public static final int INFORMATION_MESSAGE = 0;

	public static void
	showMessageDialog(
		Object	wha,
		String	str )
	{
	}

	public static void
	showMessageDialog(
		Object	wha,
		String	str1,
		String	str2,
		int		a )
	{
	}

}
