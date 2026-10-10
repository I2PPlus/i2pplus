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
 */
public class
Component
{
	/**
	 * Constructor. A bare instance is meaningful here: the stub methods are
	 * no-ops, so an emulated component needs no state to be substitutable
	 * wherever a real Swing component is used.
	 */
	public Component()
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param s the title a real component would be given; discarded here
	 */
	public void
	setTitle(
		String	s )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param c the child a real component would adopt; discarded here
	 */
	public void
	add(
		Component c )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param str the layout constraint that would place c; discarded here
	 * @param c the child a real component would adopt; discarded here
	 */
	public void
	add( String str, Component c )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param i the index the child would be inserted at; discarded here
	 * @param c the child a real component would adopt; discarded here
	 */
	public void
	add( int i, Component c )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param b true would allow the component to take input; discarded here
	 */
	public void
	setEnabled(
		boolean	b )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param b true would show the component; discarded here
	 */
	public void
	setVisible(
		boolean b )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param b true would let the user edit the component's text; discarded here
	 */
	public void
	setEditable(
		boolean	b )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param b true would let the user resize the component; discarded here
	 */
	public void
	setResizable(
		boolean	b )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param i the width in pixels a real component would be given; discarded here
	 * @param j the height in pixels a real component would be given; discarded here
	 */
	public void
	setSize(
		int	i, int j )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param d the preferred size a real component would be given; discarded here
	 */
	public void
	setPreferredSize(
		Dimension d )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param c the border a real component would be given; discarded here
	 */
	public void
	setBorder(
		Component	c )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param l the layout manager a real component would adopt; discarded here
	 */
	public void
	setLayout(
		BorderLayout l )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param l the layout manager a real component would adopt; discarded here
	 */
	public void
	setLayout(
		BoxLayout l )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param c the mouse cursor a real component would show; discarded here
	 */
	public void
	setCursor(
		Cursor c )
	{
	}

	/**
	 * No-op stub.
	 *
	 * @param c the foreground colour a real component would paint with; discarded here
	 */
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

	/**
	 * Creates a stub toolkit; this AWT emulation has no real one.
	 * @return new Toolkit stub
	 */
	public Toolkit
	getToolkit()
	{
		return( new Toolkit());
	}

	/**
	 * No-op stub.
	 *
	 * @param l the mouse listener a real component would register; discarded here
	 */
	public void
	addMouseListener(
		MouseAdapter	l )
	{

	}

	/**
	 * No-op stub.
	 *
	 * @param l the window listener a real component would register; discarded here
	 */
	public void
	addWindowListener(
		WindowAdapter l )
	{

	}
}
