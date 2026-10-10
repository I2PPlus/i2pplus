package com.docuverse.identicon;

import java.net.InetAddress;
import java.security.MessageDigest;

/**
 * Utility methods useful for implementing identicon functionality. Methods are
 * class methods for convenience.
 * <p>
 * Key method of interest is {@link #getIdenticonCode} which converts IP address
 * into identicon code.<br>
 * <strong>IMPORTANT</strong>: <code>inetSalt</code> value must be set to
 * reasonably long random string prior to invoking this method.
 * </p>
 *
 * @author don
 */
public class IdenticonUtil {
	/**
	 * Constructor. The mask and salt below are static settings shared by every
	 * caller, so an instance holds no state of its own.
	 */
	public IdenticonUtil() {}

	private static final int DEFAULT_IDENTICON_SIZE = 16;

	private static final int MINIMUM_IDENTICON_SIZE = 15;

	private static final int MAXIMUM_IDENTICON_SIZE = 64;

	private static final int DEFAULT_INET_MASK = 0xffffffff;

	private static int inetMask = DEFAULT_INET_MASK;

	private static String inetSalt;

	/**
	 * Returns current IP address mask. Default is 0xffffffff.
	 *
	 * @return current IP address mask
	 */
	public static int getInetMask() {
		return inetMask;
	}

	/**
	 * Sets current IP address mask. Default is 0xffffffff.
	 *
	 * @param inetMask bits kept from the 32 bit IP address before hashing, 0xffffffff for all
	 */
	public static void setInetMask(int inetMask) {
		IdenticonUtil.inetMask = inetMask;
	}

	/**
	 * Returns current inetSalt value.
	 *
	 * @return the inet salt
	 */
	public static String getInetSalt() {
		return inetSalt;
	}

	/**
	 * Sets current inetSalt value.
	 *
	 * @param inetSalt string mixed into the hashed IP; set it before getIdenticonCode()
	 */
	public static void setInetSalt(String inetSalt) {
		IdenticonUtil.inetSalt = inetSalt;
	}

	/**
	 * Returns identicon code for given IP address.
	 * <p>
	 * Current implementation uses first four bytes of SHA1(int(mask(ip))+salt)
	 * where mask(ip) uses inetMask to remove unwanted bits from IP address.
	 * Also, since salt is a string for convenience sake, int(mask(ip)) is
	 * converetd into a string and combined with inetSalt prior to hashing.
	 * </p>
	 *
	 * @param inetAddr
	 *            IP address
	 * @return identicon code for <code>inetAddr</code>
	 * @throws Exception if inetSalt has not been set yet
	 */
	public static int getIdenticonCode(InetAddress inetAddr) throws Exception {
		if (inetSalt == null)
			throw new Exception(
					"inetSalt must be set prior to retrieving identicon code");

		byte[] ip = inetAddr.getAddress();
		int ipInt = (((ip[0] & 0xFF) << 24) | ((ip[1] & 0xFF) << 16)
				| ((ip[2] & 0xFF) << 8) | (ip[3] & 0xFF))
				& inetMask;
		StringBuilder s = new StringBuilder();
		s.append(ipInt);
		s.append('+');
		s.append(inetSalt);
		MessageDigest md;
		md = MessageDigest.getInstance("SHA1");
		byte[] hashedIp = md.digest(s.toString().getBytes("UTF-8"));
		int code = ((hashedIp[0] & 0xFF) << 24) | ((hashedIp[1] & 0xFF) << 16)
				| ((hashedIp[2] & 0xFF) << 8) | (hashedIp[3] & 0xFF);
		return code;
	}

	/**
	 * Returns identicon code specified as an input parameter or derived from an
	 * IP address.
	 * <p>
	 * This method is a convenience method intended to be used by servlets like
	 * below:
	 * </p>
	 *
	 * <pre>
	 * int code = IdenticonUtil.getIdenticonCode(request.getParameter(&quot;code&quot;), request
	 * 		.getRemoteAddr());
	 * </pre>
	 *
	 * @param codeParam
	 *            code parameter, if <code>null</code> remoteAddr parameter
	 *            will be used to determine the value.
	 * @param remoteAddr
	 *            HTTP requester's IP address. Optional if code was specified.
	 * @return the code
	 */
	public static int getIdenticonCode(String codeParam, String remoteAddr) {
		int code = 0;
		try {
			if (codeParam != null) {
				code = Integer.parseInt(codeParam);
			} else {
				code = IdenticonUtil.getIdenticonCode(InetAddress
						.getByName(remoteAddr));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return code;
	}

	/**
	 * Resolve the pixel size of a requested identicon. A supplied value is clamped
	 * to the 15 to 64 pixel range the renderer supports; a null or unparseable one
	 * leaves the 16 pixel default in place.
	 *
	 * @param param the requested width and height in pixels, clamped to the supported range
	 * @return the identicon size
	 */
	public static int getIdenticonSize(String param) {
		int size = DEFAULT_IDENTICON_SIZE;
		try {
			String sizeParam = param;
			if (sizeParam != null) {
				size = Integer.parseInt(sizeParam);
				if (size < MINIMUM_IDENTICON_SIZE)
					size = MINIMUM_IDENTICON_SIZE;
				else if (size > MAXIMUM_IDENTICON_SIZE)
					size = MAXIMUM_IDENTICON_SIZE;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return size;
	}

	/**
	 * Build the weak validator a client revalidates an identicon against, so the
	 * rendered image is refetched only when its code, size, or version changes.
	 *
	 * @param code the identicon code, rendered as hexadecimal
	 * @param size the identicon size in pixels, rendered as decimal
	 * @param version the cache-busting version number, rendered after a 'v'
	 * @return the identicon e tag
	 */
	public static String getIdenticonETag(int code, int size, int version) {
		StringBuilder s = new StringBuilder("W/\"");
		s.append(Integer.toHexString(code));
		s.append('@');
		s.append(size);
		s.append('v');
		s.append(version);
		s.append('\"');
		return s.toString();
	}
}
