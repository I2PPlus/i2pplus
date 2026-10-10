/*
 * Copyright 2015-2024 the original author or authors
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
 * A reserved LDH label (R-LDH label), which have the property that they contain "--" in the third and fourth characters.
 *
 */
public class ReservedLdhLabel extends LdhLabel {

    /**
     * ReservedLdhLabel.
     *
     * @param label the LDH label string, which must carry "--" in its third and fourth characters
     */
    protected ReservedLdhLabel(String label) {
        super(label);
        assert isReservedLdhLabelInternal(label);
    }

    /**
     * isReservedLdhLabel.
     *
     * @param label the candidate label string, checked for LDH syntax before the reserved
     *        "--" pattern is tested
     * @return true if the string is a well-formed LDH label with "--" in its third and fourth
     *         characters
     */
    public static boolean isReservedLdhLabel(String label) {
        if (!isLdhLabel(label)) {
            return false;
        }
        return isReservedLdhLabelInternal(label);
    }

    /**
     * isReservedLdhLabelInternal.
     *
     * @param label the label string, assumed to be already known to be a well-formed LDH label
     * @return true if it is at least four characters long and its third and fourth characters
     *         are both '-'
     */
    static boolean isReservedLdhLabelInternal(String label) {
        return label.length() >= 4 && label.charAt(2) == '-' && label.charAt(3) == '-';
    }
}
