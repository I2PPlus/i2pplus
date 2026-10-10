/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.util.Properties;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.util.EventDispatcher;
import net.i2p.util.EventDispatcherImpl;

/**
 * Base class for I2P tunnel servers and clients.
 * <p>
 * Use caution when extending externally. Should be maintained as stable API,
 * but confirm before use. No startRunning() method; all extending
 * classes implement one.
 */
public abstract class I2PTunnelTask extends EventDispatcherImpl {

    private int id;
    private String name;
    /** true while the tunnel is running and accepting connections */
    protected volatile boolean open;
    /** the I2PTunnel this task runs inside */
    public I2PTunnel tunnel;

    /**
     * Create a task bound to a tunnel.
     *
     * @param name display name for this task, used in logs and console output
     * @param notifyThis dispatcher to which this task sends its events
     * @param tunnel the I2PTunnel this task runs inside
     */
    protected I2PTunnelTask(String name, EventDispatcher notifyThis, I2PTunnel tunnel) {
        attachEventDispatcher(notifyThis);
        this.name = name;
        this.id = -1;
        this.tunnel = tunnel;
    }

    /**
     * Rebind this task to a different tunnel.
     *
     * @param pTunnel the tunnel to run inside, for apps using several instances
     */
    public void setTunnel(I2PTunnel pTunnel) {tunnel = pTunnel;}
    /**
     * The I2PTunnel this task runs inside.
     *
     * @return the tunnel this task runs inside, as set by the constructor or setTunnel()
     */
    public I2PTunnel getTunnel() {return tunnel;}
    /**
     * The id of the client list entry for this task.
     *
     * @return the client list entry id, -1 until setId() is called
     */
    public int getId() {return this.id;}
    /**
     * Whether the tunnel is running and accepting connections.
     *
     * @return true if the tunnel is running and accepting connections
     */
    public boolean isOpen() {return open;}
    /**
     * Record the client list entry id for this task.
     *
     * @param id the client list entry id to record for this task
     */
    public void setId(int id) {this.id = id;}
    /**
     * Record the display name for this task.
     *
     * @param name display name to record for this task
     */
    protected void setName(String name) {this.name = name;}
    /** Tell the tunnel that the router connection has dropped. */
    protected void routerDisconnected() {tunnel.routerDisconnected();}

    /**
     * Note that the tunnel can be reopened after this by calling startRunning().
     * This may not release all resources. In particular, the I2PSocketManager remains
     * and it may have timer threads that continue running.
     *
     * To release all resources permanently, call destroy().
     *
     * @param forced true to interrupt an in-progress build, false to close gracefully
     * @return true if the tunnel was closed and can be reopened by startRunning()
     */
    public abstract boolean close(boolean forced);

    /**
     * Note that the tunnel cannot be reopened after this by calling startRunning(),
     * as it may destroy the underlying socket manager, depending on implementation.
     * This should release all resources.
     *
     * The implementation here simply calls close(true).
     * Extending classes should override to release all resources.
     *
     * @return true if every resource was released
     * @since 0.9.17
     */
    public boolean destroy() {return close(true);}

    /**
     * Notify the task that I2PTunnel's options have been updated.
     * Extending classes should override and call I2PTunnel.getClientOptions(),
     * then update the I2PSocketManager.
     * Does nothing here.
     *
     * @param tunnel the tunnel whose client options were updated
     * @since 0.9.1
     */
    public void optionsUpdated(I2PTunnel tunnel) {}

    /**
     * For tasks that don't call I2PTunnel.addSession() directly
     *
     * @param session the newly connected session to register with the tunnel
     * @since 0.8.13
     */
    public void connected(I2PSession session) {getTunnel().addSession(session);}

    /**
     * Called when a session is disconnected.
     * <p>
     * This method removes the session from the tunnel and notifies
     * the router that disconnection occurred.
     * </p>
     *
     * @param session the session that dropped, removed from the tunnel here
     */
    public void disconnected(I2PSession session) {
        routerDisconnected();
        getTunnel().removeSession(session);
    }

    /**
     * Read a boolean client option from the tunnel's properties.
     *
     * @param opt the property name to read
     * @param dflt the value to return when the property is not set
     * @return the boolean option
     * @since 0.9.62
     */
    protected boolean getBooleanOption(String opt, boolean dflt) {
        Properties opts = getTunnel().getClientOptions();
        String o = opts.getProperty(opt);
        if (o != null) {return Boolean.parseBoolean(o);}
        return dflt;
    }

    /**
     * Does nothing here. Extending classes may override.
     *
     * @param session the session that reported the error
     * @param message a human-readable description of the error
     * @param error the error that was thrown, or null if none was reported
     */
    public void errorOccurred(I2PSession session, String message, Throwable error) {}

    /**
     * Does nothing here. Extending classes may override.
     *
     * @param session the session reported for abusive behaviour
     * @param severity the abuse severity, from the I2PClient status codes
     */
    public void reportAbuse(I2PSession session, int severity) {}

    /**
     * Returns the I2PSocketManager for this task, or null if not applicable.
     * Extending classes may override to return their socket manager.
     * @return the socket manager
     * @since 0.9.63
     */
    public I2PSocketManager getSocketManager() {return null;}

    /**
     * The display name given to this task when it was constructed.
     *
     * @return the task name
     */
    @Override
    public String toString() {return name;}

}
