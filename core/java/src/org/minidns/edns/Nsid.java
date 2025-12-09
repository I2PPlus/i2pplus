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
package org.minidns.edns;

import java.nio.charset.StandardCharsets;

import org.minidns.edns.Edns.OptionCode;
import org.minidns.util.Hex;

/**
 * EDNS NSID (Name Server Identifier) option.
 * 
 * @see <a href="https://tools.ietf.org/html/rfc5001">RFC 5001 - DNS Name Server Identifier (NSID) Option</a>
 */
public class Nsid extends EdnsOption {

    /**
     * NSID request instance with empty payload.
     */
    public static final Nsid REQUEST = new Nsid();

    /**
     * Private constructor for the REQUEST instance.
     */
    private Nsid() {
        this(new byte[0]);
    }

    /**
     * Creates a new NSID option with the specified payload.
     *
     * @param payload the NSID payload data
     */
    public Nsid(byte[] payload) {
        super(payload);
    }

    @Override
    public OptionCode getOptionCode() {
        return OptionCode.NSID;
    }

    @Override
    protected CharSequence toStringInternal() {
        String res = OptionCode.NSID + ": ";
        res += new String(optionData, StandardCharsets.US_ASCII);
        return res;
    }

    @Override
    protected CharSequence asTerminalOutputInternal() {
        return Hex.from(optionData);
    }

}
