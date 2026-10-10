/* StaticSnark - Main snark startup class for staticly linking with gcj.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

/**
 * Main snark startup class for staticly linking with gcj. It references somee necessary classes
 * that are normally loaded through reflection.
 */
public class StaticSnark {

    /**
     * main() is the whole entry point and it refuses to run, so an instance carries
     * nothing and is never meant to be used.
     */
    public StaticSnark() {}

    /**
     * Refuse to run: gcj static linking is no longer supported.
     *
     * @param args the command line arguments, ignored
     */
    public static void main(String[] args) {
        System.err.println("unsupported");
    }
}
