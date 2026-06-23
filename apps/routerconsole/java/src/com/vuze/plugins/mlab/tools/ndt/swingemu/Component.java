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
 * Base class for Swing component emulation in the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of Swing components to allow
 * the NDT tool to run in headless environments without requiring actual GUI components.
 * All methods are no-ops that do nothing, providing compatibility without functionality.</p>
 * 
 * <p>This emulation layer enables the NDT network testing functionality to be integrated
 * into I2P applications that don't have graphical user interfaces.</p>
 * 
 */
public class
Component
{
	public void
	setTitle(
		String	s )
	{
	}

	public void
	add(
		Component c )
	{
	}

	public void
	add( String str, Component c )
	{
	}

	public void
	add( int i, Component c )
	{
	}

	public void
	setEnabled(
		boolean	b )
	{
	}

	public void
	setVisible(
		boolean b )
	{
	}

	public void
	setEditable(
		boolean	b )
	{
	}

	public void
	setResizable(
		boolean	b )
	{
	}

	public void
	setSize(
		int	i, int j )
	{
	}

	public void
	setPreferredSize(
		Dimension d )
	{
	}

	public void
	setBorder(
		Component	c )
	{
	}

	public void
	setLayout(
		BorderLayout l )
	{
	}

	public void
	setLayout(
		BoxLayout l )
	{
	}

	public void
	setCursor(
		Cursor c )
	{
	}

	public void
	setForeground(
		Color	c )
	{
	}

	public void
	pack()
	{

	}
	public void
	repaint()
	{

	}

	public Toolkit
	getToolkit()
	{
		return( new Toolkit());
	}

	public void
	addMouseListener(
		MouseAdapter	l )
	{

	}

	public void
	addWindowListener(
		WindowAdapter l )
	{

	}
}
