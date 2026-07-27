/*
 * Created on May 20, 2010
 * Created by Paul Gardner
 *
 * Copyright 2010 Vuze, Inc.  All rights reserved.
 *
 * Licensed under the GPLv2 or later.
 */

package com.vuze.plugins.mlab.tools.ndt.swingemu;

import java.net.URL;

/**
 * Minimal implementation of applet context for swing emulation.
 */
public class AppletContext {
    /**
     * Construct the AppletContext.
     */
    public AppletContext() {}

    /**
     * Request that the browser show the document at the given URL.
     *
     * @param url the URL to show
     */
    public void showDocument(URL url) {}
}
