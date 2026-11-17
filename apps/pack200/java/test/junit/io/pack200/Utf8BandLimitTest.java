package io.pack200;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import junit.framework.TestCase;

/**
 * Bounds the reader puts on the cp_Utf8 bands.
 *
 * <p>A prefix is the run of characters a constant shares with the previous
 * constant, so it cannot be longer than that previous constant, and JVMS 4.4.7
 * caps a UTF8 entry at 65535 bytes. A pack that ignores either rule was not
 * rejected: it names a number of characters quadratic in the size of the pack,
 * and the reader allocates for every one of them. A 48 KB pack measured 126 MB
 * of heap before the checks landed.
 */
public class Utf8BandLimitTest extends TestCase {

    /**
     * The shape a real packer produces: a prefix no longer than the string it
     * is shared with, and a total within the class file's own limit.
     */
    public void testAcceptsWhatThePackerWrites() throws IOException {
        PackageReader.checkUtf8Run(0, 0, 0);
        PackageReader.checkUtf8Run(0, 5, 0);
        PackageReader.checkUtf8Run(5, 3, 5);
        PackageReader.checkUtf8Run(5, 0, 5);
        int max = PackageReader.MAX_UTF8_CHARS;
        PackageReader.checkUtf8Run(max, 0, max);
        PackageReader.checkUtf8Run(0, max, 0);
    }

    /**
     * The attack: a prefix longer than the string the buffer still holds. The
     * rebuild copies that prefix out of the buffer, so it is paid for even
     * though the pack never carries those characters.
     */
    public void testRejectsAPrefixLongerThanThePreviousString() {
        try {
            PackageReader.checkUtf8Run(6, 1, 5);
            fail("prefix longer than the previous string was accepted");
        } catch (IOException expected) {
            // expected
        }
    }

    /**
     * A UTF8 constant cannot exceed the class file's own limit, so a pack that
     * names a longer one cannot be unpacked into anything a JVM would load.
     */
    public void testRejectsAStringPastTheClassFileLimit() {
        int max = PackageReader.MAX_UTF8_CHARS;
        assertRejected(max, 1, max);
        assertRejected(1, max, max);
    }

    /**
     * A band value decoded from a delta can come out negative, and a negative
     * length is not a length.
     */
    public void testRejectsNegativeLengths() {
        assertRejected(-1, 0, 0);
        assertRejected(0, -1, 0);
    }

    /**
     * The total budget has to sit well above the largest class file we ship, or
     * a routine update starts failing on a legitimate pack.
     */
    public void testTotalBudgetIsAboveAnyRealClassFile() {
        assertTrue("total budget " + PackageReader.MAX_TOTAL_CHARS +
                   " leaves too little room for a real class file",
                   PackageReader.MAX_TOTAL_CHARS > 4 * PackageReader.MAX_UTF8_CHARS);
    }

    /**
     * A real class file, with the shared prefixes its neighbours give it, still
     * packs and unpacks. This is what shows the checks above are not rejecting
     * legitimate packs. The unpacked class is not byte-identical to the input:
     * the packer reorders the constant pool, so the checks here are that a
     * class file came out, and that it is the same class file in size.
     */
    public void testRealClassStillRoundTrips() throws Exception {
        String name = "io/pack200/PackageReader.class";
        byte[] original = readClass(name);
        byte[] packed = pack(makeJar(name, original));
        byte[] unpacked = unpackEntry(makeUnpackedJar(packed), name);
        assertTrue("unpacked class " + unpacked.length +
                   " bytes, from an original of " + original.length,
                   unpacked.length > original.length / 2 && unpacked.length < 2 * original.length);
        assertEquals("class-file magic", (byte) 0xCA, unpacked[0]);
        assertTrue("no " + name + " in the unpacked class",
                   indexOf(unpacked, "io/pack200/PackageReader".getBytes("US-ASCII")) >= 0);
    }

    /**
     * @param prefix characters shared with the previous string
     * @param suffix characters unique to this string
     * @param prevChars length of the previous string
     */
    private static void assertRejected(int prefix, int suffix, int prevChars) {
        try {
            PackageReader.checkUtf8Run(prefix, suffix, prevChars);
            fail("prefix " + prefix + " suffix " + suffix +
                 " after a string " + prevChars + " long was accepted");
        } catch (IOException expected) {
            // expected
        }
    }

    /**
     * The bytes of a compiled class already on the test classpath.
     *
     * @param name slash-separated class name
     * @return the class file contents
     */
    private static byte[] readClass(String name) throws IOException {
        InputStream in = Utf8BandLimitTest.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            fail("no " + name + " on the test classpath");
            return null;
        }
        try {
            return drain(in);
        } finally {
            in.close();
        }
    }

    /**
     * A jar holding one entry.
     *
     * @param name the entry name
     * @param data the entry contents
     * @return the jar bytes
     */
    private static byte[] makeJar(String name, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(bytes);
        try {
            jos.putNextEntry(new JarEntry(name));
            jos.write(data);
            jos.closeEntry();
        } finally {
            jos.close();
        }
        return bytes.toByteArray();
    }

    /**
     * @param jar the jar bytes to compress
     * @return the pack200 container for them
     */
    private static byte[] pack(byte[] jar) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar));
        OutputStream out = packed;
        try {
            Pack200.newPacker().pack(in, out);
        } finally {
            in.close();
            out.close();
        }
        return packed.toByteArray();
    }

    /**
     * @param packed the pack200 container to expand
     * @return the jar bytes it names
     */
    private static byte[] makeUnpackedJar(byte[] packed) throws IOException {
        ByteArrayOutputStream jar = new ByteArrayOutputStream();
        InputStream in = new ByteArrayInputStream(packed);
        JarOutputStream out = new JarOutputStream(jar);
        try {
            Pack200.newUnpacker().unpack(in, out);
        } finally {
            in.close();
            out.close();
        }
        return jar.toByteArray();
    }

    /**
     * @param jar the jar bytes to read
     * @param name the entry to return
     * @return that entry's contents
     */
    private static byte[] unpackEntry(byte[] jar, String name) throws IOException {
        JarInputStream jin = new JarInputStream(new ByteArrayInputStream(jar));
        try {
            JarEntry e;
            while ((e = jin.getNextJarEntry()) != null) {
                if (name.equals(e.getName())) {
                    return drain(jin);
                }
            }
        } finally {
            jin.close();
        }
        fail("no " + name + " in the unpacked jar");
        return null;
    }

    /**
     * @param in the stream to read to the end
     * @return everything it held
     */
    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * @param data the bytes to search
     * @param pattern the sequence to look for
     * @return the offset of the first occurrence, or -1 if there is none
     */
    private static int indexOf(byte[] data, byte[] pattern) {
        for (int i = 0; i + pattern.length <= data.length; i++) {
            int j = 0;
            while (j < pattern.length && data[i + j] == pattern[j]) {
                j++;
            }
            if (j == pattern.length) {
                return i;
            }
        }
        return -1;
    }
}
