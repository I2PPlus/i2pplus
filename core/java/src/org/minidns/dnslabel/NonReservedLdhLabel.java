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
 * A Non-Reserved LDH label (NR-LDH label), which do <em>not</em> have "--" in the third and fourth position.
 *
 */
public final class NonReservedLdhLabel extends LdhLabel {

    /**
     * Create label
     * @param label the LDH label text to wrap, already checked against the non-reserved position rules
     */
    NonReservedLdhLabel(String label) {
        super(label);
        assert isNonReservedLdhLabelInternal(label);
    }

    /**
     * isNonReservedLdhLabel.
     * @param label the LDH label text to test
     * @return true if label is non-reserved
     */
    public static boolean isNonReservedLdhLabel(String label) {
        if (!isLdhLabel(label)) {
            return false;
        }
        return isNonReservedLdhLabelInternal(label);
    }

    /**
     * Does this text satisfy the non-reserved rule, having already been
     * checked as an LDH label?
     *
     * @param label the already-validated LDH label text to test
     * @return true if label is non-reserved
     */
    static boolean isNonReservedLdhLabelInternal(String label) {
        return !ReservedLdhLabel.isReservedLdhLabelInternal(label);
    }
}
