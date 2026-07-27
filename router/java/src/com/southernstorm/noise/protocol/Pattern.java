/*
 * Copyright (C) 2016 Southern Storm Software, Pty Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.southernstorm.noise.protocol;

/**
 * Information about all supported handshake patterns.
 */
class Pattern {

	/** Private constructor prevents instantiation */
	private Pattern() {}

	/** Token code: S */
	public static final short S = 1;
	/** Token code: E */
	public static final short E = 2;
	/** Token code: EE */
	public static final short EE = 3;
	/** Token code: ES */
	public static final short ES = 4;
	/** Token code: SE */
	public static final short SE = 5;
	/** Token code: SS */
	public static final short SS = 6;
	/** Token code: F */
	public static final short F = 7;
	/** Token code: FF */
	public static final short FF = 8;
	/** Token code: FLIP_DIR */
	public static final short FLIP_DIR = 255;

	/** Flag bit: local static */
	public static final short FLAG_LOCAL_STATIC = 0x0001;
	/** Flag bit: local ephemeral */
	public static final short FLAG_LOCAL_EPHEMERAL = 0x0002;
	/** Flag bit: local required */
	public static final short FLAG_LOCAL_REQUIRED = 0x0004;
	/** Flag bit: local ephemeral required */
	public static final short FLAG_LOCAL_EPHEM_REQ = 0x0008;
	/** Flag bit: local hybrid */
	public static final short FLAG_LOCAL_HYBRID = 0x0010;
	/** Flag bit: local hybrid required */
	public static final short FLAG_LOCAL_HYBRID_REQ = 0x0020;
	/** Flag bit: remote static */
	public static final short FLAG_REMOTE_STATIC = 0x0100;
	/** Flag bit: remote ephemeral */
	public static final short FLAG_REMOTE_EPHEMERAL = 0x0200;
	/** Flag bit: remote required */
	public static final short FLAG_REMOTE_REQUIRED = 0x0400;
	/** Flag bit: remote ephemeral required */
	public static final short FLAG_REMOTE_EPHEM_REQ = 0x0800;
	/** Flag bit: remote hybrid */
	public static final short FLAG_REMOTE_HYBRID = 0x1000;
	/** Flag bit: remote hybrid required */
	public static final short FLAG_REMOTE_HYBRID_REQ = 0x2000;

	private static final short[] noise_pattern_N = {
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES
	};

	private static final short[] noise_pattern_XK = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    FLIP_DIR,
	    E,
	    EE,
	    FLIP_DIR,
	    S,
	    SE
	};

	private static final short[] noise_pattern_IK = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    S,
	    SS,
	    FLIP_DIR,
	    E,
	    EE,
	    SE
	};

	/**
	 * @since 0.9.67
	 */
	private static final short[] noise_pattern_IKhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    F,
	    S,
	    SS,
	    FLIP_DIR,
	    E,
	    EE,
	    F,
	    FF,
	    SE
	};

	/**
	 * @since 0.9.69
	 */
	private static final short[] noise_pattern_XKhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    F,
	    FLIP_DIR,
	    E,
	    EE,
	    F,
	    FF,
	    FLIP_DIR,
	    S,
	    SE
	};

	/**
	 * Look up the description information for a pattern.
	 *
	 * @param name The name of the pattern.
	 * @return The pattern description or null.
	 */
	public static short[] lookup(String name)
	{
		if (name.equals("N"))
			return noise_pattern_N;
		else if (name.equals("XK"))
			return noise_pattern_XK;
		else if (name.equals("IK"))
			return noise_pattern_IK;
		else if (name.equals("IKhfs"))
			return noise_pattern_IKhfs;
		else if (name.equals("XKhfs"))
			return noise_pattern_XKhfs;
		return null;
	}

	/**
	 * Reverses the local and remote flags for a pattern.
	 *
	 * @param flags The flags, assuming that the initiator is "local".
	 * @return The reversed flags, with the responder now being "local".
	 */
	public static short reverseFlags(short flags)
	{
		return (short)(((flags >> 8) & 0x00FF) | ((flags << 8) & 0xFF00));
	}
}
