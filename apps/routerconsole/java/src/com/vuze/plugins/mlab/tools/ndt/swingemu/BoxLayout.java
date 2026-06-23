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
 * Emulation of javax.swing.BoxLayout for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a box layout manager
 * to allow the NDT tool to run in headless environments. The layout provides
 * axis constants but no actual layout functionality.</p>
 * 
 * <p>All operations are no-ops, maintaining API compatibility without requiring
 * an actual graphical display system.</p>
 * 
 */
public class BoxLayout {
    public static final int X_AXIS = 1;
    public static final int Y_AXIS = 2;
    public BoxLayout(Component c, int i ) {}
}
