package io.pack200;

import junit.framework.TestCase;

/**
 * Field-descriptor dispatch for {@code ConstantValue} attributes.
 *
 * <p>Regression cover for a real corruption bug: {@code getLiteralTag()}'s
 * {@code 'L'} branch once lost its {@code return}, so long/double constant
 * handling silently fell through to {@code CONSTANT_None}. That produced
 * {@code ClassFormatError: Bad initial value index 0 in ConstantValue attribute}
 * for every class carrying a static final String, and it still compiled and
 * packed cleanly - only unpacking the result exposed it.
 *
 * @since EOL fork
 */
public class ConstantPoolLiteralTagTest extends TestCase {

    /** JVMS 4.7.2: ConstantValue is only valid for these field types. */
    public void testPrimitiveDescriptorsMapToTheirTags() {
        assertTag("I", Constants.CONSTANT_Integer);
        assertTag("J", Constants.CONSTANT_Long);
        assertTag("F", Constants.CONSTANT_Float);
        assertTag("D", Constants.CONSTANT_Double);
        // Byte/Short/Char/Boolean have no distinct constant-pool tag.
        assertTag("B", Constants.CONSTANT_Integer);
        assertTag("S", Constants.CONSTANT_Integer);
        assertTag("C", Constants.CONSTANT_Integer);
        assertTag("Z", Constants.CONSTANT_Integer);
    }

    /**
     * The regression itself. 'L' is the only reference descriptor that can
     * appear, and the only reference type a ConstantValue may hold is String -
     * so this must be CONSTANT_String and never CONSTANT_None.
     */
    public void testReferenceDescriptorMapsToString() {
        assertTag("Ljava/lang/String;", Constants.CONSTANT_String);
    }

    private void assertTag(String descriptor, byte expected) {
        ConstantPool.SignatureEntry e = ConstantPool.getSignatureEntry(descriptor);
        byte actual = e.getLiteralTag();
        assertEquals(descriptor + " -> CONSTANT_None (would corrupt the unpacked class)",
                     expected, actual);
        assertFalse(descriptor + " must not resolve to CONSTANT_None",
                    actual == Constants.CONSTANT_None);
    }

    /**
     * ConstantPool's entry factory reads {@link Utils#getTLGlobals()}, which is
     * only populated while a pack or unpack is in flight. Stand up the same
     * context the engine would.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Utils.currentInstance.set(new PackerImpl());
    }

    @Override
    protected void tearDown() throws Exception {
        Utils.currentInstance.set(null);
        super.tearDown();
    }
}