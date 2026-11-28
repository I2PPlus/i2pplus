package org.klomp.snark;

/*
 *  Copyright 2026 the original author or authors.
 *  This file is part of the I2P software suite <https://geti2p.net/>.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

import net.i2p.data.Hash;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Pins which log values may carry brackets. The console log viewer links any
 * bracketed six characters as a router and any bracketed eight as a lease set
 * (apps/routerconsole/jsp/js/refreshLogs.js), so a hash-shaped run inside a
 * torrent name or an exception message becomes a link to an object that does
 * not exist, while the brackets this class puts around a real destination hash
 * are the only ones that must survive.
 */
public class FreeTextLogFormTest {

    /**
     * One run of the checks over which log values may carry brackets and which must not.
     */
    public FreeTextLogFormTest() {
    }

    /**
     * A 32-byte destination hash, the shortest thing worth naming.
     *
     * @return the hash, filled with a counting pattern
     */
    private static Hash hash() {
        byte[] data = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        return new Hash(data);
    }

    /**
     * A scene release name carries its group tag in brackets, and a tag like
     * [WEBRip] is exactly six characters, so it is link-shaped and the brackets
     * come off.
     */
    @Test
    public void testSixCharacterGroupTagIsNotLinked() {
        assertEquals("Some Movie WEBRip", SnarkLogFormatter.name("Some Movie [WEBRip]"));
    }

    /** An eight-character bracketed run is lease-set shaped, so it too is cut. */
    @Test
    public void testEightCharacterTagIsNotLinked() {
        assertEquals("release lxyzdo5p", SnarkLogFormatter.name("release [lxyzdo5p]"));
    }

    /**
     * A run that is not six or eight characters cannot be read as a hash, so the
     * name is left exactly as the user wrote it.
     */
    @Test
    public void testNonHashShapedBracketsAreLeftAlone() {
        assertEquals("Movie [Grupe] x", SnarkLogFormatter.name("Movie [Grupe] x"));
        assertEquals("Movie [x265] x", SnarkLogFormatter.name("Movie [x265] x"));
        assertEquals("a [ab] c", SnarkLogFormatter.name("a [ab] c"));
    }

    /**
     * A destination this class renders keeps its brackets - that is the form the
     * viewer links, and the whole point of the renderer.
     */
    @Test
    public void testADestinationKeepsItsBrackets() {
        String s = SnarkLogFormatter.dest(hash());
        assertEquals(1, s.length() - s.replace("[", "").length());
        assertEquals(1, s.length() - s.replace("]", "").length());
        assertEquals(10, s.length());
    }

    /** An exception message is free text, so a hash-shaped tag in it is cut. */
    @Test
    public void testReasonLosesHashShapedBrackets() {
        assertEquals("closed by [x] peer Gk4vTq",
                     SnarkLogFormatter.reason("closed by [x] peer [Gk4vTq]"));
    }

    /**
     * The name budget is spent on the text that is actually shown, not on
     * brackets that were going to be removed anyway.
     */
    @Test
    public void testBudgetIsCountedAfterBracketsGo() {
        String name = SnarkLogFormatter.name("01234567890123456789[WEBRip]");
        assertEquals(SnarkLogFormatter.NAME_CHARS, name.length());
        assertFalse(name, name.contains("["));
        assertTrue(name, name.endsWith("..."));
    }

    /** Nothing to name is still "none", brackets or no brackets. */
    @Test
    public void testNothingToName() {
        assertEquals("none", SnarkLogFormatter.name(null));
        assertEquals("none", SnarkLogFormatter.reason(null));
        assertEquals("none", SnarkLogFormatter.dest((Hash) null));
    }
}
