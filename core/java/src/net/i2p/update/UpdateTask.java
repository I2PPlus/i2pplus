package net.i2p.update;

import java.net.URI;

/**
 *  A running check or download. Cannot be restarted.
 *
 *  @since 0.9.4
 */
public interface UpdateTask {

    /**
     *  Tasks must not start themselves in the constructor. Do it here.
     */
    public void start();

    /**
     *  Shutdown the task.
     */
    public void shutdown();

    /**
     *  Check if the task is currently running.
     *
     *  @return true if running
     */
    public boolean isRunning();

    /**
     *  Get the update type.
     *
     *  @return what is being updated, as one of the UpdateType constants
     */
    public UpdateType getType();

    /**
     *  Get the update method.
     *
     *  @return how the update is retrieved, as one of the UpdateMethod constants
     */
    public UpdateMethod getMethod();

    /**
     *  The current URI being checked or downloaded from.
     *  Can change if there are multiple URIs to try.
     *
     *  @return the URI being checked or downloaded from at the moment
     */
    public URI getURI();

    /**
     * Valid for plugins.
     *
     * @return the identifier of the plugin being updated, empty if this is not a plugin update
     */
    public String getID();
}
