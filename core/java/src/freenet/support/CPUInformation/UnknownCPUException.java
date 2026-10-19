/*
 * Created on Jul 16, 2004
 */
package freenet.support.CPUInformation;

/**
 * Exception for unknown CPU types.
 */
/**
 * UnknownCPUException Extends RuntimeException.
 */
public class UnknownCPUException extends RuntimeException {
    /**
     * Serial Version UID.
     */
    private static final long serialVersionUID = 5166144274582583742L;

    /**
     * Raise the failure with no detail beyond the type.
     */
    public UnknownCPUException() {
        super();
    }

    /**
     * Raise the failure, naming why the CPU type is unknown.
     * @param message human readable reason the CPU type could not be determined
     */
    public UnknownCPUException(String message) {
        super(message);
    }
}
