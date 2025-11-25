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
     *  @return update type
     */
    public UpdateType getType();

    /**
     *  Get the update method.
     *
     *  @return update method
     */
    public UpdateMethod getMethod();

   /**
     *  The current URI being checked or downloaded from.
     *  Can change if there are multiple URIs to try.
     *
     *  @return current URI
     */
    public URI getURI();

   /**
     *  Valid for plugins
     *
     *  @return plugin ID
     */
    public String getID();
}
