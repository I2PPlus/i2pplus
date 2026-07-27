package com.docuverse.identicon;

/**
 * Represents a I2P identiconcache.
 */

public interface IdenticonCache {
	/**
	 * Retrieves cached image data for the given key.
	 *
	 * @param key the cache key
	 * @return the image data, or null if not cached
	 */
	public byte[] get(String key);

	/**
	 * Adds image data to the cache.
	 *
	 * @param key the cache key
	 * @param imageData the image data to cache
	 */
	public void add(String key, byte[] imageData);

	/**
	 * Removes cached image data for the given key.
	 *
	 * @param key the cache key
	 */
	public void remove(String key);

	/**
	 * Removes all entries from the cache.
	 */
	public void removeAll();
}
