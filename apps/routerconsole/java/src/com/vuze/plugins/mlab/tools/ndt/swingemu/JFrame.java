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
 * Emulation of javax.swing.JFrame for the NDT (Network Diagnostic Tool) plugin.
 *
 * <p>This class provides a minimal stub implementation of a top-level window container
 * to allow the NDT tool to run in headless environments. The frame contains a content
 * pane but provides no actual GUI functionality.</p>
 *
 * <p>All operations are no-ops, maintaining API compatibility without requiring
 * an actual graphical display system.</p>
 *
 */
public class
JFrame
	extends Component
{
	private Panel content_pane = new Panel();

	/**
	 * Creates an empty headless frame, with only an empty content pane attached.
	 */
	public
	JFrame()
	{

	}

	/**
	 * Creates a frame carrying the given title, which this headless stub never displays.
	 *
	 * @param s the title text this frame is constructed with, which this headless
	 *        stub never displays
	 */
	public
	JFrame(
		String	s )
	{

	}

	/**
	 * Return the content pane.
	 *
	 * @return the single Panel this frame reports as its content, allocated at
	 *         construction and never replaced
	 */
	public Panel
	getContentPane()
	{
		return( content_pane );
	}

	/** Send frame to back (no-op). */
	public void
	toBack()
	{
	}

	/** Destroy the frame (no-op). */
	public void
	destroy()
	{

	}

	/** Dispose the frame (no-op). */
	public void
	dispose()
	{
	}
}
