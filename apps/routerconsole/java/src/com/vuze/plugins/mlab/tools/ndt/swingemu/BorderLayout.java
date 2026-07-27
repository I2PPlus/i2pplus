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
 * Emulation of java.awt.BorderLayout for the NDT (Network Diagnostic Tool) plugin.
 *
 * <p>This class provides a minimal stub implementation of a border layout manager
 * to allow the NDT tool to run in headless environments. The layout provides
 * region constants but no actual layout functionality.</p>
 *
 * <p>All operations are no-ops, maintaining API compatibility without requiring
 * an actual graphical display system.</p>
 *
 */
public class BorderLayout {

	/**
	 * BorderLayout.
	 */
	/** Default constructor. All region constants are no-ops. */
	public BorderLayout() {}

	/** North region constant */
	public static final int NORTH = 1;
	/** South region constant */
	public static final int SOUTH = 2;

}
