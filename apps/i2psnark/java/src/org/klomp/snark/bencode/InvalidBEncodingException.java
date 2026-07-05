/* InvalidBEncodingException - Thrown when a bencoded stream is corrupted.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark.bencode;

import java.io.IOException;

/**
 * Exception thrown when a bencoded stream is corrupted or malformed.
 *
 * @author Mark Wielaard (mark@klomp.org)
 */
public class InvalidBEncodingException extends IOException {
    /**
     * Creates a new InvalidBEncodingException with the specified detail message.
     *
     * @param message the detail message
     */
    public InvalidBEncodingException(String message) {
        super(message);
    }
}
