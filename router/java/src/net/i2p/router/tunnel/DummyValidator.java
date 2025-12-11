package net.i2p.router.tunnel;

/**
 * IV validator that accepts all initialization vectors without validation.
 * Used for testing or scenarios where IV validation is not required.
 * Implements IVValidator interface with permissive validation behavior.
 */
class DummyValidator implements IVValidator {
    private static final DummyValidator _instance = new DummyValidator();
    public static DummyValidator getInstance() { return _instance; }
    private DummyValidator() {}

    public boolean receiveIV(byte ivData[], int ivOffset, byte payload[], int payloadOffset) { return true; }

}