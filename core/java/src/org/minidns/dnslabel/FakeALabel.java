/*
 * Copyright 2015-2020 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package org.minidns.dnslabel;

/**
 * Represents a fake A-label in DNS labels.<br>
 * A fake A-label is a special type of XN-label used for handling
 * certain edge cases in internationalized domain names.
 *
 * @author MiniDNS Project
 */
public final class FakeALabel extends XnLabel {

    /**
     * Creates a new FakeALabel with the specified label.
     *
     * @param label the DNS label string
     */
    protected FakeALabel(String label) {
        super(label);
    }

}
