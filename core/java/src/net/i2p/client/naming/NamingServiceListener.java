package net.i2p.client.naming;

import net.i2p.data.Destination;

import java.util.Properties;

/**
 * Listener for naming service events.
 *
 * @since 0.8.7
 */
public interface NamingServiceListener {

    /**
     * Also called when a NamingService is added or removed.
     *
     * @param ns the naming service
     */
    public void configurationChanged(NamingService ns);

    /**
     *  Called when a new naming service entry is added.
     *
     *  @param ns the naming service
     *  @param hostname the hostname
     *  @param dest the destination
     *  @param options NamingService-specific, can be null
     */
    public void entryAdded(NamingService ns, String hostname, Destination dest, Properties options);

    /**
     *  Called when a naming service entry changes.
     *
     *  @param ns the naming service
     *  @param hostname the hostname
     *  @param dest null if unchanged
     *  @param options NamingService-specific, can be null
     */
    public void entryChanged(NamingService ns, String hostname, Destination dest, Properties options);

    /**
     * Called when a naming service entry is removed.
     *
     * @param ns the naming service
     * @param hostname the hostname
     */
    public void entryRemoved(NamingService ns, String hostname);
}
