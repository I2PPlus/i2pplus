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
 * Emulation of javax.swing.BorderFactory for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a border factory
 * to allow the NDT tool to run in headless environments. The factory can create
 * titled borders but provides no actual visual border functionality.</p>
 * 
 * <p>All operations return stub components, maintaining API compatibility
 * without requiring an actual graphical display system.</p>
 * 
 */
public class BorderFactory {

	public static Component
	createTitledBorder(
		String	str )
	{
		return( new Component());
	}
}
