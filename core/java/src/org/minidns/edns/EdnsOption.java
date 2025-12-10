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

import java.io.DataOutputStream;
import java.io.IOException;
import org.minidns.edns.Edns.OptionCode;

/**
 * Abstract base class for EDNS options.
 */
public abstract class EdnsOption {

    /**
     * The EDNS option code.
     */
    public final int optionCode;

    /**
     * The length of the option data.
     */
    public final int optionLength;

    /**
     * The raw option data.
     */
    protected final byte[] optionData;

    /**
     * Creates a new EDNS option with the specified option code and data.
     *
     * @param optionCode the option code
     * @param optionData the option data
     */
    protected EdnsOption(int optionCode, byte[] optionData) {
        this.optionCode = optionCode;
        this.optionLength = optionData.length;
        this.optionData = optionData;
    }

    /**
     * Creates a new EDNS option with the specified data.
     * The option code is derived from the concrete implementation.
     *
     * @param optionData the option data
     */
    protected EdnsOption(byte[] optionData) {
        this.optionCode = getOptionCode().asInt;
        this.optionLength = optionData.length;
        this.optionData =  optionData;
    }

    /**
     * Writes this EDNS option to the specified data output stream.
     *
     * @param dos the data output stream to write to
     * @throws IOException if an I/O error occurs
     */
    public final void writeToDos(DataOutputStream dos) throws IOException {
        dos.writeShort(optionCode);
        dos.writeShort(optionLength);
        dos.write(optionData);
    }

    /**
     * Returns the option code for this EDNS option.
     *
     * @return the option code
     */
    public abstract OptionCode getOptionCode();

    private String toStringCache;

    @Override
    public final String toString() {
        if (toStringCache == null) {
            toStringCache = toStringInternal().toString();
        }
        return toStringCache;
    }

    /**
     * Returns the string representation of this EDNS option.
     *
     * @return the string representation
     */
    protected abstract CharSequence toStringInternal();

    private String terminalOutputCache;

    /**
     * Returns the terminal output representation of this EDNS option.
     *
     * @return the terminal output representation
     */
    public final String asTerminalOutput() {
        if (terminalOutputCache == null) {
            terminalOutputCache = asTerminalOutputInternal().toString();
        }
        return terminalOutputCache;
    }

    /**
     * Returns the terminal output representation of this EDNS option.
     *
     * @return the terminal output representation
     */
    protected abstract CharSequence asTerminalOutputInternal();

    /**
     * Parses an EDNS option from the specified option code and data.
     *
     * @param intOptionCode the option code as an integer
     * @param optionData the option data
     * @return the parsed EDNS option
     */
    public static EdnsOption parse(int intOptionCode, byte[] optionData) {
        OptionCode optionCode = OptionCode.from(intOptionCode);
        EdnsOption res;
        switch (optionCode) {
        case NSID:
            res = new Nsid(optionData);
            break;
        default:
            res = new UnknownEdnsOption(intOptionCode, optionData);
            break;
        }
        return res;
    }

}
