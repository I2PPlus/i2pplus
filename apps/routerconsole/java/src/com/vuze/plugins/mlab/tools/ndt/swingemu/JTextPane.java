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
 * Emulation of javax.swing.JTextPane for the NDT (Network Diagnostic Tool) plugin.
 *
 * <p>This class provides a minimal stub implementation of a styled text component
 * to allow the NDT tool to run in headless environments. The text pane supports
 * document operations but provides no actual text rendering or styling functionality.</p>
 *
 * <p>It contains a StyledDocument that can capture text content, but all visual
 * operations are no-ops, maintaining API compatibility without requiring an actual
 * graphical display system.</p>
 *
 */
public class
JTextPane
	extends Component
{
	private StyledDocument sd = new StyledDocument();

	/** Insert a component (no-op). */
	public void
	insertComponent(
		Component	comp )
	{
	}

	/** Return the styled document. */
	public StyledDocument
	getStyledDocument()
	{
		return( sd );
	}

	/** The selection start (no-op). */
	public void
	setSelectionStart(
		int	i )
	{
	}

	/** The selection end (no-op). */
	public void
	setSelectionEnd(
		int	i )
	{
	}
}
