package net.i2p.router.tunnel;

/**
 * IV validator that accepts all initialization vectors without validation.
 * Used for testing or scenarios where IV validation is not required.
 * Implements IVValidator interface with permissive validation behavior.
 */
class DummyValidator implements IVValidator {
    /** The single instance, shared by all callers. */
    private static final DummyValidator _instance = new DummyValidator();
    /**
     * The shared DummyValidator instance.
     *
     * @return the shared stateless instance
     */
    public static DummyValidator getInstance() { return _instance; }
    /** Utility class, not intended for instantiation. */
    private DummyValidator() {}

    /**
     * Accepts all initialization vectors as valid.
     */
    public boolean receiveIV(byte[] ivData, int ivOffset, byte[] payload, int payloadOffset) { return true; }

}
