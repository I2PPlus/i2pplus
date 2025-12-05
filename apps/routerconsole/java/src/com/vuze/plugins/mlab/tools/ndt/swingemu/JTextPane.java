/*
 * Created on May 20, 2010
 * Created by Paul Gardner
 *
 * Copyright 2010 Vuze, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
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

	public void
	insertComponent(
		Component	comp )
	{
	}

	public StyledDocument
	getStyledDocument()
	{
		return( sd );
	}

	public void
	setSelectionStart(
		int	i )
	{
	}

	public void
	setSelectionEnd(
		int	i )
	{
	}
}
