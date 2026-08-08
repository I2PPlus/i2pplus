package org.klomp.snark;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the per-torrent destination support in I2PSnarkUtil that need no router:
 * the tunnel nickname generation.
 *
 * @since 0.9.71+
 */
public class I2PSnarkUtilTest {

    @Test
    public void testGetNicknameShort() {
        assertEquals("I2PSnark - foo", I2PSnarkUtil.getNickname("foo"));
    }

    @Test
    public void testGetNicknameNullAndEmpty() {
        assertEquals("I2PSnark - ", I2PSnarkUtil.getNickname(null));
        assertEquals("I2PSnark - ", I2PSnarkUtil.getNickname(""));
    }

    @Test
    public void testGetNicknameExactMaxLength() {
        String name = "12345678901234567890123456789012";
        assertEquals(32, name.length());
        assertEquals("I2PSnark - " + name, I2PSnarkUtil.getNickname(name));
    }

    @Test
    public void testGetNicknameTruncated() {
        String name = "123456789012345678901234567890123";
        assertEquals(33, name.length());
        assertEquals("I2PSnark - 12345678901234567890123456789012...", I2PSnarkUtil.getNickname(name));
    }
}
