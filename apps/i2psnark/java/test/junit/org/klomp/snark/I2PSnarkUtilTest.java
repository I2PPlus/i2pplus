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
        String name = "1234567890123456789012345678901234567890123456789012345678901234";
        assertEquals(64, name.length());
        assertEquals("I2PSnark - " + name, I2PSnarkUtil.getNickname(name));
    }

    @Test
    public void testGetNicknameTruncated() {
        String name = "12345678901234567890123456789012345678901234567890123456789012345";
        assertEquals(65, name.length());
        assertEquals("I2PSnark - 1234567890123456789012345678901234567890123456789012345678901234...", I2PSnarkUtil.getNickname(name));
    }

    @Test
    public void testGetNicknameStripsParentheticals() {
        assertEquals("I2PSnark - Astra",
                     I2PSnarkUtil.getNickname("Astra (2027) [1080p] [WEBRip] [5.1] [SOLAR.NE - NOVA.BX]"));
    }

    @Test
    public void testGetNicknameStripsLeadingGroup() {
        assertEquals("I2PSnark - foo", I2PSnarkUtil.getNickname("[Release] foo"));
    }

    @Test
    public void testGetNicknameTrimsAfterStrip() {
        assertEquals("I2PSnark - foo", I2PSnarkUtil.getNickname(" foo (extra)  "));
        assertEquals("I2PSnark - ", I2PSnarkUtil.getNickname("(only) [content]"));
    }

    @Test
    public void testGetNicknameSceneName() {
        assertEquals("I2PSnark - Zenith.Rises.2031", I2PSnarkUtil.getNickname("Zenith.Rises.2031.1080p.WEBRip.x265-Nocturne.mkv"));
    }

    @Test
    public void testGetNicknameCutsDashTail() {
        assertEquals("I2PSnark - Stratospheric.Courts.S02E04", I2PSnarkUtil.getNickname("Stratospheric.Courts.S02E04.720p.WEBRip-xRip"));
        assertEquals("I2PSnark - Zephyr.2089", I2PSnarkUtil.getNickname("Zephyr.2089.720p-Wrx"));
    }

    @Test
    public void testGetNicknameCutsLongTail() {
        assertEquals("I2PSnark - 500 Postcards",
                     I2PSnarkUtil.getNickname("500 Postcards - A Most Beautiful Cartography of All Time by the Editors of Atlas"));
    }

    @Test
    public void testGetNicknameCutsShortQualityTail() {
        assertEquals("I2PSnark - Night.S02E01", I2PSnarkUtil.getNickname("Night.S02E01.720p.HDTV.x265-Nightly"));
    }

    @Test
    public void testGetNicknameKeepsTitleDash() {
        assertEquals("I2PSnark - Arcadia in the Desert - Season 2", I2PSnarkUtil.getNickname("Arcadia in the Desert - Season 2"));
        assertEquals("I2PSnark - Half-Moon Bay", I2PSnarkUtil.getNickname("Half-Moon Bay.mkv"));
        assertEquals("I2PSnark - Orion.Ascendants", I2PSnarkUtil.getNickname("Orion.Ascendants-fxl"));
    }

    @Test
    public void testGetNicknameQualityTokens() {
        assertEquals("I2PSnark - Eclipse Protocol 2x08", I2PSnarkUtil.getNickname("Eclipse Protocol 2x08 1080p WEBRip x265"));
        assertEquals("I2PSnark - Stellar.Station.SE1", I2PSnarkUtil.getNickname("Stellar.Station.SE1.2160p.HDR10.DDP5.1"));
    }

    @Test
    public void testGetNicknameLongTitleTruncated() {
        assertEquals("I2PSnark - Voyagers Beyond the Event Horizon Forge II Public Domain Cut 202...",
                     I2PSnarkUtil.getNickname("Voyagers Beyond the Event Horizon Forge II Public Domain Cut 2029"));
    }
}