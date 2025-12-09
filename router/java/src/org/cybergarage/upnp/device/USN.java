/******************************************************************
 *
 *	CyberUPnP for Java
 *
 *	Copyright (C) Satoshi Konno 2002
 *
 *	File: USN.java
 *
 *	Revision;
 *
 *	12/09/02
 *		- first revision.
 *
 ******************************************************************/

package org.cybergarage.upnp.device;

import java.nio.charset.StandardCharsets;

public class USN {
    public static final String ROOTDEVICE = "upnp:rootdevice";

    public static final boolean isRootDevice(String usnValue) {
        if (usnValue == null) return false;
        return usnValue.endsWith(ROOTDEVICE);
    }

    public static final String getUDN(String usnValue) {
        if (usnValue == null) return "";
        int idx = usnValue.indexOf("::");
        if (idx < 0) return usnValue.trim();
        byte[] usnBytes = usnValue.getBytes(StandardCharsets.UTF_8);
        String udnValue = new String(usnBytes, 0, idx, StandardCharsets.UTF_8);
        return udnValue.trim();
    }
}
