/* StaticSnark - Main snark startup class for staticly linking with gcj.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

/**
 * Main snark startup class for staticly linking with gcj. It references somee necessary classes
 * that are normally loaded through reflection.
 *
 * @author Mark Wielaard (mark@klomp.org)
 */
public class StaticSnark {
    public static void main(String[] args) {
        // The GNU security provider is needed for SHA-1 MessageDigest checking.
        // So make sure it is available as a security provider.
        // Provider gnu = new gnu.java.security.provider.Gnu();
        // Security.addProvider(gnu);

        // And finally call the normal starting point.
        // Snark.main(args);
        System.err.println("unsupported");
    }
}
