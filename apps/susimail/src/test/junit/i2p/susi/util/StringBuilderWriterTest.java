package i2p.susi.util;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

/**
 *  Tests for StringBuilderWriter.
 */
public class StringBuilderWriterTest {

    @Test
    public void testWriteString() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        w.write("hello");
        assertEquals("hello", w.toString());
    }

    @Test
    public void testWriteCharArray() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        w.write(new char[] {'a', 'b', 'c'});
        assertEquals("abc", w.toString());
    }

    @Test
    public void testWriteCharArrayPartial() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        w.write(new char[] {'a', 'b', 'c', 'd', 'e'}, 1, 3);
        assertEquals("bcd", w.toString());
    }

    @Test
    public void testWriteInt() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        w.write('X');
        assertEquals("X", w.toString());
    }

    @Test
    public void testAppendChar() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        w.append('a');
        w.append('b');
        assertEquals("ab", w.toString());
    }

    @Test
    public void testAppendCharSequence() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        w.append("hello");
        assertEquals("hello", w.toString());
    }

    @Test
    public void testAppendSubSequence() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        w.append("hello world", 6, 11);
        assertEquals("world", w.toString());
    }

    @Test
    public void testMultipleWrites() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        w.write("one");
        w.write(' ');
        w.write("two");
        assertEquals("one two", w.toString());
    }

    @Test
    public void testDefaultCapacity() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        assertNotNull(w.toString());
        assertEquals("", w.toString());
    }

    @Test
    public void testCustomCapacity() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter(1024);
        w.write("test");
        assertEquals("test", w.toString());
    }

    @Test
    public void testFlush() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        w.write("test");
        w.flush();
        assertEquals("test", w.toString());
    }

    @Test
    public void testWriteStringPartial() throws Exception {
        StringBuilderWriter w = new StringBuilderWriter();
        w.write("hello world", 6, 5);
        assertEquals("world", w.toString());
    }
}
