// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.webmail;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

/**
 * Pins what the session's re-authorization check accepts.
 *
 * <p>The webmail hashes the login password at sign-in and asks the operator to offer
 * it again before the settings that decide where mail goes are rewritten. The pair
 * used to compare the two hashes with String.equals(), and a hash that failed to
 * derive was stored as the empty string - so on a JDK that could not do the
 * derivation, the stored hash and the offered one were both empty and any password
 * at all passed the check. The derivation and the comparison now live here, with the
 * cost named rather than buried in a literal.
 *
 * @since 0.9.72+
 */
public class LocalPasswordTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public LocalPasswordTest() {}

    /**
     * The rounds used by the tests, low enough to keep the suite quick while still
     * exercising the real derivation.
     */
    private static final int TEST_ITERATIONS = 1000;

    /**
     * The stored key, read through the private field so two hashes can be compared.
     *
     * @param password the hash to look inside
     * @return the hex key it holds
     */
    private static String keyOf(LocalPassword password) {
        try {
            Field field = LocalPassword.class.getDeclaredField("_key");
            field.setAccessible(true);
            return (String) field.get(password);
        } catch (ReflectiveOperationException roe) {
            throw new AssertionError("no key field to compare", roe);
        }
    }

    /**
     * The password a session was signed in with is accepted.
     */
    @Test
    public void testTheSignedInPasswordMatches() {
        LocalPassword stored = LocalPassword.create("hunter2", "1a2b3c4d", TEST_ITERATIONS);
        assertNotNull(stored);
        assertTrue(stored.matches("hunter2"));
    }

    /**
     * A wrong password, an empty one and none at all are all refused.
     */
    @Test
    public void testAWrongOrMissingPasswordDoesNotMatch() {
        LocalPassword stored = LocalPassword.create("hunter2", "1a2b3c4d", TEST_ITERATIONS);
        assertFalse(stored.matches("hunter3"));
        assertFalse(stored.matches("HUNTER2"));
        assertFalse(stored.matches("hunter2 "));
        assertFalse(stored.matches(""));
        assertFalse(stored.matches(null));
    }

    /**
     * The salt is part of the hash: the same password signed in under two salts gives
     * two different keys, so a key captured from one session cannot be replayed into
     * another, and a rainbow table of common passwords does not apply.
     */
    @Test
    public void testADifferentSaltGivesADifferentHash() {
        LocalPassword first = LocalPassword.create("hunter2", "1a2b3c4d", TEST_ITERATIONS);
        LocalPassword second = LocalPassword.create("hunter2", "5e6f7081", TEST_ITERATIONS);
        assertNotEquals(keyOf(first), keyOf(second));
        assertTrue(first.matches("hunter2"));
        assertTrue(second.matches("hunter2"));
    }

    /**
     * Nothing is stored for a password that is not there, or for unusable arguments,
     * so the caller sees no check rather than a check that can never be passed.
     */
    @Test
    public void testUnusableArgumentsStoreNothing() {
        assertNull(LocalPassword.create(null, "1a2b3c4d", TEST_ITERATIONS));
        assertNull(LocalPassword.create("", "1a2b3c4d", TEST_ITERATIONS));
        assertNull(LocalPassword.create("hunter2", null, TEST_ITERATIONS));
        assertNull(LocalPassword.create("hunter2", "1a2b3c4d", 0));
        assertNull(LocalPassword.create("hunter2", "1a2b3c4d", -1));
    }

    /**
     * The cost of the check is a named constant, so the price paid on the login path
     * is visible in review, and the key it derives is the length it claims to be.
     */
    @Test
    public void testTheCostIsNamedAndTheKeyIsTheRightLength() {
        assertEquals(1000000, LocalPassword.ITERATIONS);
        assertEquals(256, LocalPassword.KEY_BITS);
        LocalPassword stored = LocalPassword.create("hunter2", "1a2b3c4d", TEST_ITERATIONS);
        String key = keyOf(stored);
        assertEquals(LocalPassword.KEY_BITS / 4, key.length());
        assertTrue("not hex: " + key, key.matches("[0-9a-f]+"));
    }

    /**
     * The real cost still verifies, at the iteration count a session actually uses.
     */
    @Test
    public void testTheDefaultCostStillVerifies() {
        LocalPassword stored = LocalPassword.create("hunter2", "1a2b3c4d");
        assertTrue(stored.matches("hunter2"));
        assertFalse(stored.matches("hunter3"));
    }

    /**
     * A password outside ASCII is stored and checked the same way, since PBKDF2 takes
     * the characters, not bytes, and the mailbox accepts them on the login form.
     */
    @Test
    public void testANonAsciiPasswordMatches() {
        String password = "p\u00e4ssw\u00f6rd-\u4e2d\u6587";
        LocalPassword stored = LocalPassword.create(password, "1a2b3c4d", TEST_ITERATIONS);
        assertTrue(stored.matches(password));
        assertFalse(stored.matches("password-"));
    }

    /**
     * A new salt is hex and does not repeat, which is what keeps two sessions on one
     * account from sharing a hash.
     */
    @Test
    public void testANewSaltIsHexAndDoesNotRepeat() {
        String first = LocalPassword.newSalt();
        String second = LocalPassword.newSalt();
        assertTrue("not hex: " + first, first.matches("[0-9a-f]+"));
        assertNotEquals(first, second);
    }
}
