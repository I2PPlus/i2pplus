/* InvalidBEncodingException - Thrown when a bencoded stream is corrupted.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark.bencode;

import java.io.IOException;

/**
 * Exception thrown when a bencoded stream is corrupted.
 *
 * @author Mark Wielaard (mark@klomp.org)
 */
public class InvalidBEncodingException extends IOException
{
  public InvalidBEncodingException(String message)
  {
    super(message);
  }
}
