package com.southernstorm.noise.protocol;

/**
 * Interface for objects that implement destroying.
 *
 * Applications that use the Noise protocol can inadvertently leave
 * sensitive data in the heap if steps are not taken to clean up.
 *
 * This interface can be implemented by objects that know how to
 * securely clean up after themselves.
 *
 * The Noise.destroy() function can help with destroying byte arrays
 * that hold sensitive values.
 */
public interface Destroyable {

    /**
     * Destroys all sensitive state in the current object.
     */
    void destroy();
}
