package com.docuverse.identicon;

/**
 * Helper methods shared by the identicon servlets, for building the weak ETag
 * a client revalidates rendered images against. The class is not instantiated.
 */
public class IdenticonUtil {

	/** Utility class. */
	private IdenticonUtil() {}

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
		return String.format("W/\"%x@%dv%d\"", code, size, version);
	}
}
