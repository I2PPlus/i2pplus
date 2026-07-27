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
	/** No-op stub. */
	public void
	setTitle(
		String	s )
	{
	}

	/** No-op stub. */
	public void
	add(
		Component c )
	{
	}

	/** No-op stub. */
	public void
	add( String str, Component c )
	{
	}

	/** No-op stub. */
	public void
	add( int i, Component c )
	{
	}

	/** No-op stub. */
	public void
	setEnabled(
		boolean	b )
	{
	}

	/** No-op stub. */
	public void
	setVisible(
		boolean b )
	{
	}

	/** No-op stub. */
	public void
	setEditable(
		boolean	b )
	{
	}

	/** No-op stub. */
	public void
	setResizable(
		boolean	b )
	{
	}

	/** No-op stub. */
	public void
	setSize(
		int	i, int j )
	{
	}

	/** No-op stub. */
	public void
	setPreferredSize(
		Dimension d )
	{
	}

	/** No-op stub. */
	public void
	setBorder(
		Component	c )
	{
	}

	/** No-op stub. */
	public void
	setLayout(
		BorderLayout l )
	{
	}

	/** No-op stub. */
	public void
	setLayout(
		BoxLayout l )
	{
	}

	/** No-op stub. */
	public void
	setCursor(
		Cursor c )
	{
	}

	/** No-op stub. */
	public void
	setForeground(
		Color	c )
	{
	}

	/** No-op stub. */
	public void
	pack()
	{

	}
	/** No-op stub. */
	public void
	repaint()
	{

	}

	/** @return new Toolkit stub */
	public Toolkit
	getToolkit()
	{
		return( new Toolkit());
	}

	/** No-op stub. */
	public void
	addMouseListener(
		MouseAdapter	l )
	{

	}

	/** No-op stub. */
	public void
	addWindowListener(
		WindowAdapter l )
	{

	}
}
