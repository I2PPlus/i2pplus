package net.i2p.router.tasks;

import net.i2p.crypto.CryptoCheck;
import net.i2p.crypto.SigType;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Cryptographic algorithm availability checker and advisor.
 * 
 * This utility checks the availability of all required cryptographic
 * algorithms on the current platform and provides warnings and
 * recommendations when essential crypto is missing. It helps users
 * identify and resolve cryptographic compatibility issues.
 * 
 * <strong>Checks Performed:</strong>
 * <ul>
 *   <li>Signature algorithm availability (EdDSA, ECDSA, DSA)</li>
 *   <li>Java version compatibility checks</li>
 *   <li>Cryptography policy file detection</li>
 *   <li>Platform-specific crypto limitations</li>
 * </ul>
 * 
 * <strong>Recommendations Provided:</strong>
 * <ul>
 *   <li>Java upgrade suggestions for older versions</li>
 *   <li>Instructions for installing unlimited strength crypto</li>
 *   <li>Platform-specific guidance for crypto issues</li>
 *   <li>Future compatibility warnings</li>
 * </ul>
 * 
 * The checker outputs to both router logs and System.out
 * to ensure visibility during startup. It can be run standalone
 * for crypto testing without starting the full router.
 *
 *  @since 0.9.15
 */
public class CryptoChecker {

    private static String JRE6 = "http://www.oracle.com/technetwork/java/javase/downloads/index.html";

    /**
     * Check and warn about unavailable cryptographic algorithms.
     * 
     * This method scans all available signature types and logs warnings for any
     * that are not available on the current platform. It also provides
     * recommendations for Java upgrades and cryptography policy files
     * when needed.
     * 
     * The method outputs warnings to both the router log (if context provided)
     * and System.out for visibility during startup.
     * 
     * @param ctx router context for logging; if null, logs only to System.out
     *             (used when called from main method during startup)
     * @since 0.9.15
     */
    public static void warnUnavailableCrypto(RouterContext ctx) {
        if (SystemVersion.isAndroid())
            return;
        boolean unavail = false;
        Log log = null;
        for (SigType t : SigType.values()) {
            if (!t.isAvailable()) {
                if (!unavail) {
                    unavail = true;
                    if (ctx != null)
                        log = ctx.logManager().getLog(CryptoChecker.class);
                }
                String s = "Crypto " + t + " is not available";
                if (log != null)
                    log.logAlways(Log.WARN, s);
                System.out.println("Warning: " + s); // NOSONAR startup visibility
            }
        }
        if (unavail) {
            String s = "Java version: " + System.getProperty("java.version") +
                       " OS: " + System.getProperty("os.name") + ' ' +
                       System.getProperty("os.arch") + ' ' +
                       System.getProperty("os.version");
            if (log != null)
                log.logAlways(Log.WARN, s);
            System.out.println("Warning: " + s); // NOSONAR startup visibility
            if (!SystemVersion.isJava7()) {
                s = "Please consider upgrading to a newer version of Java";
                if (log != null)
                    log.logAlways(Log.WARN, s);
                System.out.println(s); // NOSONAR startup visibility
            }
            if (!CryptoCheck.isUnlimited() && !SystemVersion.isJava9()) {
                s = "Please consider installing the Java Cryptography Unlimited Strength Jurisdiction Policy Files from ";
                s  += JRE6;
                if (log != null)
                    log.logAlways(Log.WARN, s);
                System.out.println(s); // NOSONAR startup visibility
            }
            s = "This crypto will be required in a future release";
            if (log != null)
                log.logAlways(Log.WARN, s);
            System.out.println("Warning: " + s); // NOSONAR startup visibility
        } else if (ctx == null) {
            // called from main()
            System.out.println("All crypto available"); // NOSONAR CLI tool
        }
    }

    /**
     * Main method for standalone crypto checking.
     * 
     * This allows the crypto checker to be run independently to test
     * cryptographic algorithm availability without starting the full router.
     * 
     * @param args command line arguments (ignored)
     */
    public static void main(String[] args) {
        warnUnavailableCrypto(null);
    }
}

