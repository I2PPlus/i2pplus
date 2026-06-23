/*
 * Created on May 20, 2010
 * Created by Paul Gardner
 *
 * Copyright 2010 Vuze, Inc.  All rights reserved.
 *
 * Licensed under the GPLv2 or later.
 */



package com.vuze.plugins.mlab.tools.ndt.swingemu;

import java.net.URL;

/**
 * Emulation of javax.swing.JApplet for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of an applet container
 * to allow the NDT tool to run in headless environments. The applet extends JFrame
 * functionality but provides no actual applet context or browser integration.</p>
 * 
 * <p>All operations are no-ops that return null values, maintaining API compatibility
 * without requiring an actual graphical display system or browser environment.</p>
 * 
 */
public class JApplet extends JFrame{

	public URL
	getCodeBase()
	{
		return( null );
	}

	public AppletContext
	getAppletContext()
	{
		return( null );
	}

	public String
	getParameter(
		String	name )
	{
		return( null );
	}

	public void
	showStatus(
		String		str )
	{
		//System.out.println( "status: " + str );
	}

	public void
	start()
	{

	}
}
