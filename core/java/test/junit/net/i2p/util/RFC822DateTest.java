package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class RFC822DateTest {

    @Test
    public void testParse822Date() {
        long t = RFC822Date.parse822Date("Sun, 06 Nov 1994 08:49:37 GMT");
        assertTrue(t > 0);
    }

    @Test
    public void testParse822DateRoundTrip() {
        long now = 1700000000000L;
        String formatted = RFC822Date.to822Date(now);
        long parsed = RFC822Date.parse822Date(formatted);
        // Round-trip should be within 1 second (ms precision loss)
        assertTrue(Math.abs(parsed - now) < 1000);
    }

    @Test
    public void testTo822DateNonEmpty() {
        String result = RFC822Date.to822Date(1700000000000L);
        assertNotNull(result);
        assertTrue(result.length() > 0);
    }

    @Test(expected = NullPointerException.class)
    public void testParseNullDateThrows() {
        RFC822Date.parse822Date(null);
    }

    @Test
    public void testParseInvalidDate() {
        assertEquals(-1, RFC822Date.parse822Date("not a date"));
    }

    @Test
    public void testParseRFC822WithoutDayName() {
        // RFC 822 allows date without day name
        long t = RFC822Date.parse822Date("06 Nov 1994 08:49:37 GMT");
        assertTrue(t > 0);
    }

    @Test
    public void testParseTwoDigitYear() {
        long t = RFC822Date.parse822Date("06 Nov 94 08:49:37 GMT");
        assertTrue(t > 0);
    }

    @Test
    public void testParseTimezone() {
        long utc = RFC822Date.parse822Date("06 Nov 1994 08:49:37 GMT");
        long utc2 = RFC822Date.parse822Date("06 Nov 1994 08:49:37 +0000");
        assertEquals(utc, utc2);
    }
}
