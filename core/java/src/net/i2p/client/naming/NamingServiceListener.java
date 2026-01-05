package net.i2p.client.naming;

import java.util.Properties;
import net.i2p.data.Destination;

/**
 * Listener for naming service events.
 * @since 0.8.7
 */
public interface NamingServiceListener {

    /** also called when a NamingService is added or removed */
    public void configurationChanged(NamingService ns);

    /**
      *  Called when a new naming service entry is added.
      *
      *  @param options NamingService-specific, can be null
      */
    public void entryAdded(NamingService ns, String hostname, Destination dest, Properties options);

    /**
      *  Called when a naming service entry changes.
      *
      *  @param dest null if unchanged
      *  @param options NamingService-specific, can be null
      */
    public void entryChanged(NamingService ns, String hostname, Destination dest, Properties options);

    public void entryRemoved(NamingService ns, String hostname);
}

