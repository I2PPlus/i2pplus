/*
 * Created on Jul 16, 2004
 */
package freenet.support.CPUInformation;

/**
 * Exception for unknown CPU types.
 * @author Iakin
 */
/**
 * UnknownCPUException Extends RuntimeException.
 */
public class UnknownCPUException extends RuntimeException {
    /**
     *
     */
    private static final long serialVersionUID = 5166144274582583742L;

    /**
     * UnknownCPUException.
     */
    public UnknownCPUException() {
        super();
    }

    /**
     * UnknownCPUException.
     */
    public UnknownCPUException(String message) {
        super(message);
    }
}
