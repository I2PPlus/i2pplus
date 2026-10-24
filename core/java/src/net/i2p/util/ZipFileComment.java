package net.i2p.util;

import net.i2p.data.DataHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipException;

import java.nio.charset.StandardCharsets;
/**
 * Not available in ZipFile until Java 7. Refs:
 * <a href="https://secure.wikimedia.org/wikipedia/en/wiki/ZIP_%28file_format%29">ZIP file format</a>
 * <a href="https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/zip/ZipFile.html">ZipFile</a>
 * <p>
 * Code modified from:
 * <a href="https://web.archive.org/web/2015/http://www.flattermann.net/2009/01/read-a-zip-file-comment-with-java/">read a zip file comment with Java</a>
 * Beerware.
 *
 * @deprecated scheduled for removal late 2017, not for external use
 * @since 0.8.8
 */
@Deprecated
public abstract class ZipFileComment {

    // ZipFile.getComment() was added in Java 7, see
    // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6646605
    private static final int BLOCK_LEN = 22;
    private static final byte[] magicStart = {0x50, 0x4b, 0x03, 0x04};
    private static final int HEADER_LEN = magicStart.length;
    private static final byte[] magicDirEnd = {0x50, 0x4b, 0x05, 0x06};
    private static final int MAGIC_LEN = magicDirEnd.length;

    /**
     * Read the archive comment from the end-of-central-directory record.
     * The string is decoded with UTF-8.
     *
     * @param file the zip file to scan for the end-of-central-directory comment
     * @param max The max length of the comment in bytes.
     * @return empty string if no comment, or the comment.
     * @throws IOException if no valid end-of-central-directory record found
     */
    public static String getComment(File file, int max) throws IOException {
        return getComment(file, max, 0);
    }

    /**
     * Locate the end-of-central-directory record, searching back from the end for
     * a plausible magic number followed by a comment length that fits inside the
     * file.
     *
     * @param file the archive to scan
     * @param max the largest comment to accept
     * @param skip number of bytes to skip at the end before looking for the record
     * @return the comment, or an empty string when there is none
     * @throws FileNotFoundException if the archive does not exist
     * @throws ZipException if the file is shorter than a minimal archive or
     *                      longer than Integer.MAX_VALUE bytes, or if no valid
     *                      end-of-central-directory record is found
     */
    public static String getComment(File file, int max, int skip) throws IOException {
        if (!file.exists()) throw new FileNotFoundException("File not found: " + file);
        long len = file.length();
        if (len < BLOCK_LEN + HEADER_LEN + skip) throw new ZipException("File too short: " + file);
        if (len > Integer.MAX_VALUE) throw new ZipException("File too long: " + file);
        int fileLen = (int) len;
        byte[] buffer = new byte[Math.min(fileLen - skip, max + BLOCK_LEN)];
        try (InputStream in = new FileInputStream(file)) {
            if (skip > 0) DataHelper.skip(in, skip);
            byte[] hdr = new byte[HEADER_LEN];
            DataHelper.read(in, hdr);
            if (!DataHelper.eq(hdr, magicStart)) throw new ZipException("Not a zip file: " + file);
            DataHelper.skip(in, (long) fileLen - (skip + HEADER_LEN + buffer.length));
            DataHelper.read(in, buffer);
            return getComment(buffer);
        }
    }

    /**
     * Go backwards from the end.
     *
     * @param buffer the tail of the zip file, searched for the magic bytes
     * @return the UTF-8 comment stored after the last end-of-central-directory
     *         record
     * @throws IOException never thrown here, declared for the caller's sake
     */
    private static String getComment(byte[] buffer) throws IOException {
        for (int i = buffer.length - BLOCK_LEN; i >= 0; i--) {
            if (DataHelper.eq(buffer, i, magicDirEnd, 0, MAGIC_LEN)) {
                int commentLen = (buffer[i + BLOCK_LEN - 2] & 0xff) + ((buffer[i + BLOCK_LEN - 1] & 0xff) * 256);
                return new String(buffer, i + BLOCK_LEN, commentLen, StandardCharsets.UTF_8);
            }
        }
        throw new ZipException("No comment block found");
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: ZipFileComment file");
            return;
        }
        int skip = 0;
        String file = args[0];
        if (file.endsWith(".sud") || file.endsWith(".su2")) skip = 56;
        String c = getComment(new File(file), 256, skip);
        System.out.println("comment is: \"" + c + '\"');
    }
}
