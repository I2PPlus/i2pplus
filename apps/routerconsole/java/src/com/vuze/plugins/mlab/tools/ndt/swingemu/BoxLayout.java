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
    /** Horizontal axis constant */
    public static final int X_AXIS = 1;
    /** Vertical axis constant */
    public static final int Y_AXIS = 2;
    /**
     * Create a new BoxLayout.
     *
     * @param c the component to lay out, ignored by this stub
     * @param i the axis, one of X_AXIS or Y_AXIS, also ignored
     */
    public BoxLayout(Component c, int i ) {}
}
