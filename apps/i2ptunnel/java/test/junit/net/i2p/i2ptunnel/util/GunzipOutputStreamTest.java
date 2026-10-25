package net.i2p.i2ptunnel.util;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.junit.Test;

/**
 * Tests the GunzipOutputStream header state machine, in particular the
 * FEXTRA (extra field) handling: the extra-field bytes must be skipped by
 * the header parser, never fed to the inflater, and the optional name,
 * comment and CRC fields must chain after it in RFC 1952 order.
 *
 * @since 0.9.72+
 */
public class GunzipOutputStreamTest {

    /**
     * Build a minimal RFC 1952 gzip stream.
     *
     * @param flags FLG byte (extra field 1&lt;&lt;5, name 1&lt;&lt;4, comment 1&lt;&lt;3, header CRC 1&lt;&lt;6, this stream's convention)
     * @param extra the XLEN-prefixed extra field bytes, used when FEXTRA is set
     * @param name NUL-terminated original name, used when FNAME is set
     * @param comment NUL-terminated comment, used when FCOMMENT is set
     * @param payload the bytes deflated into the body
     * @return the complete gzip stream
     */
    private static byte[] gzip(int flags, byte[] extra, String name, String comment, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1f);
        out.write(0x8b);
        out.write(8);          // CM deflate
        out.write(flags);
        for (int i = 0; i < 4; i++) {
            out.write(0);      // MTIME
        }
        out.write(0);          // XFL
        out.write(3);          // OS unix
        if ((flags & (1 << 5)) != 0) {
            out.write(extra.length & 0xff);           // XLEN low
            out.write((extra.length >>> 8) & 0xff);   // XLEN high
            for (byte b : extra) {
                out.write(b & 0xff);
            }
        }
        if ((flags & (1 << 4)) != 0) {
            for (byte b : name.getBytes()) {
                out.write(b & 0xff);
            }
            out.write(0);
        }
        if ((flags & (1 << 3)) != 0) {
            for (byte b : comment.getBytes()) {
                out.write(b & 0xff);
            }
            out.write(0);
        }
        if ((flags & (1 << 6)) != 0) {
            out.write(0);      // CRC16 low
            out.write(0);      // CRC16 high
        }
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        d.setInput(payload);
        d.finish();
        byte[] buf = new byte[1024];
        while (!d.finished()) {
            int n = d.deflate(buf);
            out.write(buf, 0, n);
        }
        d.end();
        CRC32 crc = new CRC32();
        crc.update(payload);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) {
            out.write((int) ((c >>> (8 * i)) & 0xff));
        }
        int size = payload.length;
        for (int i = 0; i < 4; i++) {
            out.write((size >>> (8 * i)) & 0xff);
        }
        return out.toByteArray();
    }

    private static String gunzip(byte[] gz) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GunzipOutputStream gout = new GunzipOutputStream(baos);
        gout.write(gz);
        gout.close();
        return baos.toString("UTF-8");
    }

    @Test
    public void testPlainStream() throws IOException {
        byte[] payload = "hello i2p".getBytes("UTF-8");
        assertEquals("hello i2p", gunzip(gzip(0, null, null, null, payload)));
    }

    @Test
    public void testFextraFieldSkipped() throws IOException {
        // The old EH2 state chained a second unchained if that overwrote
        // EHDATA, feeding the extra bytes to the inflater — corruption on
        // every FEXTRA stream.
        byte[] extra = {0x01, 0x00, 'A', 'B', 'C', 'D'};
        byte[] payload = "payload after extra field".getBytes("UTF-8");
        assertEquals("payload after extra field", gunzip(gzip(1 << 5, extra, null, null, payload)));
    }

    @Test
    public void testFextraThenNameChained() throws IOException {
        byte[] extra = {0x02, 0x00, 'X', 'Y'};
        byte[] payload = "chained fields".getBytes("UTF-8");
        assertEquals("chained fields", gunzip(gzip((1 << 5) | (1 << 4), extra, "file.txt", null, payload)));
    }

    @Test
    public void testAllOptionalFields() throws IOException {
        byte[] extra = {0x03, 0x00, '1', '2', '3'};
        byte[] payload = "all flags set".getBytes("UTF-8");
        int flags = (1 << 5) | (1 << 4) | (1 << 3) | (1 << 6);
        assertEquals("all flags set", gunzip(gzip(flags, extra, "n.txt", "a comment", payload)));
    }

    @Test
    public void testNameWithoutFextra() throws IOException {
        byte[] payload = "name only".getBytes("UTF-8");
        assertEquals("name only", gunzip(gzip(1 << 4, null, "just.name", null, payload)));
    }
}
