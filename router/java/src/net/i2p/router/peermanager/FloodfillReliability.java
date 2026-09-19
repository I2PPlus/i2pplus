package net.i2p.router.peermanager;

/**
 * Reliability classification for floodfill peers based on proven request-handling history.
 *
 * <p>Floodfills start as {@link #UNKNOWN} when they advertise the floodfill capability
 * but have no verified lookup/store handling history. They are promoted through
 * {@link #OK} to {@link #GOOD} based on successful lookup responses tracked in
 * {@link DBHistory}.
 *
 * <p>This categorization is used to limit how many lookups are sent to unproven
 * floodfills, preventing overload while still probing them sporadically.
 *
 * @since 0.9.71+
 */
public enum FloodfillReliability {
    /**
     * Has demonstrated repeated failures to respond to lookups or stores.
     * Excluded from tunnel building and lookup routing.
     */
    BAD,
    /**
     * Advertises the floodfill capability but has no proven request-handling history.
     * Lookups are sent only sporadically (1 in N) to probe reliability.
     */
    UNKNOWN,
    /**
     * Has been probed and shown to respond to lookups reliably enough,
     * but has not yet accumulated sufficient history for full trust.
     * Receives a moderate share of lookups.
     */
    OK,
    /**
     * Has a proven track record of handling lookups and stores reliably.
     * Treated as a trusted floodfill; eligible for full lookup routing.
     */
    GOOD
}
