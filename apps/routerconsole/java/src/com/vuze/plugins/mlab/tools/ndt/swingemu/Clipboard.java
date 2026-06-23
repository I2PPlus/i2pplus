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
 * Emulation of java.awt.datatransfer.Clipboard for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a system clipboard
 * to allow the NDT tool to run in headless environments. The clipboard can
 * accept content but provides no actual clipboard functionality.</p>
 * 
 * <p>All operations are no-ops, maintaining API compatibility without requiring
 * an actual graphical display system or system clipboard access.</p>
 * 
 */
public class Clipboard {

	public void
	setContents(
		StringSelection	s,
		StringSelection	t )
	{
	}
}
