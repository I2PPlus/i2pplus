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
 * Emulation of javax.swing.JSpinner for the NDT (Network Diagnostic Tool) plugin.
 * 
 * <p>This class provides a minimal stub implementation of a spinner component
 * to allow the NDT tool to run in headless environments. The spinner maintains
 * a number model and can return values but provides no actual UI functionality.</p>
 * 
 * <p>All operations except value retrieval are no-ops, maintaining API compatibility
 * without requiring an actual graphical display system.</p>
 * 
 */
public class
JSpinner
	extends Component
{
	private SpinnerNumberModel		model;

	public int
	getValue()
	{
		if ( model == null ){

			return( 0 );
		}

		return( model.getValue());
	}

	public void
	setModel(
		SpinnerNumberModel	_model )
	{
		model = _model;
	}
}
