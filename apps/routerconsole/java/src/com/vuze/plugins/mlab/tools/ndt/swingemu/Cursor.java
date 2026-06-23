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
 * Emulation of java.awt.Cursor for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of cursor constants
 * to allow the NDT tool to run in headless environments. The class provides
 * cursor constants but no actual cursor functionality.</p>
 * 
 * <p>All operations are no-ops or return null values, maintaining API compatibility
 * without requiring an actual graphical display system.</p>
 * 
 */
public class Cursor {
    public static final Cursor HAND_CURSOR = null;
    public Cursor(Cursor c) {}
}
