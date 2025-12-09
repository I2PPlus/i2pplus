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
package org.minidns.dnsname;

import org.minidns.dnslabel.DnsLabel;

public abstract class InvalidDnsNameException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    protected final String ace;

    protected InvalidDnsNameException(String ace) {
        this.ace = ace;
    }

    /**
     * Exception thrown when a DNS label exceeds the maximum allowed length.<br>
     * DNS labels are limited to 63 characters according to DNS specifications.
     */
    public static class LabelTooLongException extends InvalidDnsNameException {
        /** Serial version UID for serialization */
        private static final long serialVersionUID = 1L;

        /** The DNS label that exceeds the maximum length */
        private final String label;

        /**
         * Creates a new LabelTooLongException.
         *
         * @param ace the ASCII-encoded domain name
         * @param label the specific label that is too long
         */
        public LabelTooLongException(String ace, String label) {
            super(ace);
            this.label = label;
        }

        @Override
        public String getMessage() {
            return "The DNS name '" + ace + "' contains the label '" + label
                    + "' which exceeds the maximum label length of " + DnsLabel.MAX_LABEL_LENGTH_IN_OCTETS + " octets by "
                    + (label.length() - DnsLabel.MAX_LABEL_LENGTH_IN_OCTETS) + " octets.";
        }
    }

    /**
     * Exception thrown when a DNS name exceeds the maximum allowed length.<br>
     * DNS names are limited to 255 octets according to DNS specifications.
     */
    public static class DNSNameTooLongException extends InvalidDnsNameException {
        /** Serial version UID for serialization */
        private static final long serialVersionUID = 1L;

        /** The byte representation of the DNS name that exceeds the maximum length */
        private final byte[] bytes;

        /**
         * Creates a new DNSNameTooLongException.
         *
         * @param ace the ASCII-encoded domain name
         * @param bytes the byte representation of the DNS name
         */
        public DNSNameTooLongException(String ace, byte[] bytes) {
            super(ace);
            this.bytes = bytes;
        }

        @Override
        public String getMessage() {
            return "The DNS name '" + ace + "' exceeds the maximum name length of "
                    + DnsName.MAX_DNSNAME_LENGTH_IN_OCTETS + " octets by "
                    + (bytes.length - DnsName.MAX_DNSNAME_LENGTH_IN_OCTETS) + " octets.";
        }
    }
}
