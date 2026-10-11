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
 * Emulation of javax.swing.JCheckBox for the NDT (Network Diagnostic Tool) plugin.
 *
 * <p>This class provides a minimal stub implementation of a checkbox component
 * to allow the NDT tool to run in headless environments. The checkbox maintains
 * selection state but provides no actual visual representation.</p>
 *
 * <p>All operations except selection state tracking are no-ops, maintaining API
 * compatibility without requiring an actual graphical display system.</p>
 *
 */
public class
JCheckBox
    extends Component
{
    /** True when the checkbox is checked; no visual component renders this state */
    boolean selected;

    /**
     * Checkbox label.
     * @param str the text shown beside the box
     */
    public
    JCheckBox(
        String  str )
    {

    }

    /**
     * Selection state.
     * @param b true to mark the box as checked
     */
    public void
    setSelected(
        boolean b )
    {
        selected = b;
    }

    /**
     * Selection state.
     * @return true if the box is currently checked
     */
    public boolean
    isSelected()
    {
        return( selected );
    }

    /**
     * Action listener.
     * @param l action listener
     */
    public void
    addActionListener(
        ActionListener  l )
    {

    }
}
