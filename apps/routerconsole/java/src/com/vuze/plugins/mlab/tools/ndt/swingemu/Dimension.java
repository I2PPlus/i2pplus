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
 * Emulation of java.awt.Dimension for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a dimension object
 * to allow the NDT tool to run in headless environments. The dimension can be
 * constructed with width and height but provides no actual sizing functionality.</p>
 * 
 * <p>All operations are no-ops, maintaining API compatibility without requiring
 * an actual graphical display system.</p>
 * 
 */
public class Dimension {

	public Dimension( int x, int y ){}
}
